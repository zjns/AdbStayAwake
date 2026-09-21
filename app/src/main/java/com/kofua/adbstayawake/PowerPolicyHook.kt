package com.kofua.adbstayawake

import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
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
        val isItBedTime = serviceClass.getDeclaredMethod(
            "isItBedTimeYetLocked",
            powerGroupClass,
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

        if (!module.deoptimize(isItBedTime)) {
            module.log(Log.WARN, TAG, "isItBedTimeYetLocked deoptimize returned false")
        }

        installBootPhaseHook(onBootPhase, isAdbConnected)
        installDecisionHook(keptAwake, isAdbConnected)
        installDecisionHook(keptFromInattentiveSleep, isAdbConnected)
    }

    private fun installBootPhaseHook(method: Method, isAdbConnected: () -> Boolean) {
        module.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                powerManagerService.set(chain.thisObject)
                if (isAdbConnected()) onAdbConnectionChanged(true)
                result
            }
    }

    private fun installDecisionHook(method: Method, isAdbConnected: () -> Boolean) {
        module.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val original = chain.proceed() as Boolean
                runCatching {
                    val adminEnforced = adminTimeoutEnforced.invoke(chain.thisObject) as Boolean
                    PowerPolicyDecision.shouldKeepAwake(
                        original = original,
                        adbConnected = isAdbConnected(),
                        adminTimeoutEnforced = adminEnforced,
                    )
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
