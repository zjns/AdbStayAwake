package com.kofua.adbstayawake

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class AdbStayAwakeModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "loaded in ${param.processName}, API $apiVersion")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val powerPolicy = PowerPolicyHook(this, param.classLoader)
        val connectionState = AdbConnectionState(powerPolicy::onAdbConnectionChanged)

        runCatching { powerPolicy.install(connectionState::isConnected) }
            .onSuccess { log(Log.INFO, TAG, "PowerManager hooks installed") }
            .onFailure { error ->
                log(Log.ERROR, TAG, "PowerManager hook installation failed", error)
            }

        runCatching { AdbDebuggingHook(this, param.classLoader).install(connectionState) }
            .onSuccess { log(Log.INFO, TAG, "ADB connection hook installed") }
            .onFailure { error ->
                log(Log.ERROR, TAG, "ADB connection hook installation failed", error)
            }
    }

    private companion object {
        const val TAG = "AdbStayAwake"
    }
}
