package com.kofua.adbstayawake

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerPolicyHookTest {
    private val hooks = mutableMapOf<String, Hooker>()
    private var connected = true

    @Test
    fun `legacy service keeps awake but respects changing admin policy`() {
        val service = LegacyService()
        install(service.javaClass)

        assertDecisions(service, original = false, expected = true)
        service.enforced = true
        assertDecisions(service, original = false, expected = false)
    }

    @Test
    fun `Android 17 delegated policy installs hooks and reads current admin state`() {
        val service = ModernService()
        install(service.javaClass)

        assertEquals(
            setOf("onBootPhase", "isBeingKeptAwakeLocked", "isBeingKeptFromInattentiveSleepLocked"),
            hooks.keys,
        )
        assertDecisions(service, original = false, expected = true)
        service.constants.enforced = true
        assertDecisions(service, original = false, expected = false)
        service.constants.enforced = false
        assertDecisions(service, original = false, expected = true)
    }

    @Test
    fun `Android 17 disconnect restores original decisions`() {
        val service = ModernService()
        install(service.javaClass)

        assertDecisions(service, original = false, expected = true)
        connected = false
        assertDecisions(service, original = false, expected = false)
        assertDecisions(service, original = true, expected = true)
    }

    @Test
    fun `Android 17 policy failure preserves original decisions`() {
        val service = ModernService()
        install(service.javaClass)
        service.constants.fail = true

        assertDecisions(service, original = false, expected = false)
        assertDecisions(service, original = true, expected = true)
    }

    private fun install(serviceClass: Class<*>) {
        // 只替代 LSPosed 的安装边界，执行真实 PowerPolicyHook 的反射解析和拦截回调。
        val framework = proxy<XposedInterface> { _, method, args ->
            when (method.name) {
                "hook" -> {
                    val target = args!![0] as Method
                    proxy<HookBuilder> { builder, call, values ->
                        when (call.name) {
                            "setExceptionMode" -> builder
                            "intercept" -> {
                                hooks[target.name] = values!![0] as Hooker
                                null
                            }
                            else -> error("Unexpected builder call: ${call.name}")
                        }
                    }
                }
                "deoptimize" -> true
                "log" -> null
                else -> error("Unexpected framework call: ${method.name}")
            }
        }
        val module = object : XposedModule() {}
        module.attachFramework(framework) {}
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = when (name) {
                "com.android.server.power.PowerManagerService" -> serviceClass
                "com.android.server.power.PowerGroup" -> PowerGroup::class.java
                else -> super.loadClass(name)
            }
        }
        PowerPolicyHook(module, loader).install { connected }
    }

    private fun assertDecisions(service: Any, original: Boolean, expected: Boolean) {
        for (name in listOf("isBeingKeptAwakeLocked", "isBeingKeptFromInattentiveSleepLocked")) {
            val chain = proxy<Chain> { _, method, _ ->
                when (method.name) {
                    "getThisObject" -> service
                    "proceed" -> original
                    else -> error("Unexpected chain call: ${method.name}")
                }
            }
            assertEquals(name, expected, hooks.getValue(name).intercept(chain))
        }
    }

    private inline fun <reified T> proxy(crossinline invoke: (Any, Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { obj, method, args ->
            invoke(obj, method, args)
        } as T

    private class PowerGroup

    @Suppress("UNUSED_PARAMETER", "unused")
    private class LegacyService {
        var enforced = false
        private fun isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() = enforced
        fun onBootPhase(phase: Int) = Unit
        private fun isBeingKeptAwakeLocked(group: PowerGroup) = false
        private fun isBeingKeptFromInattentiveSleepLocked() = false
        private fun userActivityInternal(display: Int, time: Long, event: Int, flags: Int, uid: Int) = Unit
    }

    @Suppress("UNUSED_PARAMETER", "unused")
    private class ModernService {
        private val mScreenTimeoutConstants = ScreenTimeoutConstants()
        val constants get() = mScreenTimeoutConstants
        fun onBootPhase(phase: Int) = Unit
        private fun isBeingKeptAwakeLocked(group: PowerGroup) = false
        private fun isBeingKeptFromInattentiveSleepLocked() = false
        private fun userActivityInternal(display: Int, time: Long, event: Int, flags: Int, uid: Int) = Unit
    }

    @Suppress("unused")
    private class ScreenTimeoutConstants {
        var enforced = false
        var fail = false
        fun isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked(): Boolean {
            check(!fail) { "Policy unavailable" }
            return enforced
        }
    }
}
