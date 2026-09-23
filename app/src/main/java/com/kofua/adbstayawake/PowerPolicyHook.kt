package com.kofua.adbstayawake

import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class PowerPolicyHook(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) {
    private val powerManagerService = AtomicReference<Any?>()
    private lateinit var userActivityInternal: Method
    private lateinit var adminTimeoutEnforced: Method

    fun install(isAdbConnected: () -> Boolean) {
        val serviceClass = classLoader.loadClass(POWER_MANAGER_SERVICE)
        val powerGroupClass = classLoader.loadClass(POWER_GROUP)
        val onBootPhase = serviceClass.getDeclaredMethod(
            "onBootPhase",
            Int::class.javaPrimitiveType,
        )
        val keptAwake = serviceClass.getDeclaredMethod(
            "isBeingKeptAwakeLocked",
            powerGroupClass,
        )
        val keptFromInattentiveSleep = serviceClass.getDeclaredMethod(
            "isBeingKeptFromInattentiveSleepLocked",
        )
        userActivityInternal = serviceClass.getDeclaredMethod(
            "userActivityInternal",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        adminTimeoutEnforced = serviceClass.getDeclaredMethod(
            "isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked",
        ).apply { isAccessible = true }

        installBootPhaseHook(onBootPhase, isAdbConnected)
        installDecisionHook(keptAwake, isAdbConnected)
        installDecisionHook(keptFromInattentiveSleep, isAdbConnected)

        val deoptimizer = HookDeoptimizer(module, classLoader)
        // 只反优化被 hook 的小方法或中间方法，无法清除上层调用者中已有的内联副本。
        // 覆盖普通超时、梦境、注意力超时的调用者，并延伸到电源状态更新入口。
        deoptimizer.deoptimizeCallers(
            POWER_MANAGER_SERVICE,
            "isItBedTimeYetLocked",
            "updateWakefulnessLocked",
            "updatePowerStateLocked",
            "canDreamLocked",
            "handleSandman",
            "updateAttentiveStateLocked",
            "maybeHideInattentiveSleepWarningLocked",
            "onDreamSuppressionChangedLocked",
        )
        // 服务实例依赖 onBootPhase 捕获，保护其启动阶段的实际分发入口。
        deoptimizer.deoptimizeCallers("com.android.server.SystemServiceManager", "startBootPhase")
    }

    private fun installBootPhaseHook(method: Method, isAdbConnected: () -> Boolean) {
        val firstHit = AtomicBoolean(false)
        module.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (firstHit.compareAndSet(false, true)) {
                    module.log(Log.INFO, TAG, "hook entered: ${method.name}, phase=${chain.args[0]}")
                }
                val result = chain.proceed()
                powerManagerService.set(chain.thisObject)
                if (isAdbConnected()) onAdbConnectionChanged(true)
                result
            }
    }

    private fun installDecisionHook(method: Method, isAdbConnected: () -> Boolean) {
        val firstHit = AtomicBoolean(false)
        val firstOverride = AtomicBoolean(false)
        module.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (firstHit.compareAndSet(false, true)) {
                    module.log(Log.INFO, TAG, "hook entered: ${method.name}")
                }
                val original = chain.proceed() as Boolean
                runCatching {
                    val adminEnforced = adminTimeoutEnforced.invoke(chain.thisObject) as Boolean
                    val connected = isAdbConnected()
                    val result = PowerPolicyDecision.shouldKeepAwake(
                        original = original,
                        adbConnected = connected,
                        adminTimeoutEnforced = adminEnforced,
                    )
                    if (!original && result && firstOverride.compareAndSet(false, true)) {
                        module.log(Log.INFO, TAG, "ADB keep-awake applied: ${method.name}")
                    }
                    result
                }.getOrElse { error ->
                    module.log(
                        Log.ERROR,
                        TAG,
                        "power decision failed; preserving original result",
                        error,
                    )
                    original
                }
            }
    }

    fun onAdbConnectionChanged(connected: Boolean) {
        if (!::userActivityInternal.isInitialized) return
        val service = powerManagerService.get() ?: return
        runCatching {
            // 与拔掉电源后的系统行为一致：从变化时刻重算超时，但不唤醒已熄屏设备。
            userActivityInternal.invoke(
                service,
                DEFAULT_DISPLAY,
                SystemClock.uptimeMillis(),
                USER_ACTIVITY_EVENT_OTHER,
                0,
                SYSTEM_UID,
            )
            module.log(Log.INFO, TAG, "ADB connected=$connected; screen timeout recalculated")
        }.onFailure { error ->
            module.log(Log.ERROR, TAG, "failed to recalculate screen timeout", error)
        }
    }

    private companion object {
        const val TAG = "AdbStayAwake"
        const val POWER_MANAGER_SERVICE = "com.android.server.power.PowerManagerService"
        const val POWER_GROUP = "com.android.server.power.PowerGroup"
        const val DEFAULT_DISPLAY = 0
        const val USER_ACTIVITY_EVENT_OTHER = 0
        const val SYSTEM_UID = 1000
    }
}
