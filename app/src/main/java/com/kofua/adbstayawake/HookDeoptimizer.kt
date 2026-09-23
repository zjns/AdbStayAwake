package com.kofua.adbstayawake

import android.util.Log
import io.github.libxposed.api.XposedInterface

internal class HookDeoptimizer(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
) {
    fun deoptimizeCallers(className: String, vararg methodNames: String) {
        val methods = runCatching { classLoader.loadClass(className).declaredMethods }
            .getOrElse { error ->
                module.log(Log.WARN, TAG, "deoptimize lookup failed: $className", error)
                return
            }
        // 按已核实的调用者名称覆盖 ROM 的重载；单项失败不影响其他调用者和 hook 安装。
        for (name in methodNames) {
            val callers = methods.filter { it.name == name }
            if (callers.isEmpty()) {
                module.log(Log.WARN, TAG, "deoptimize caller not found: $className#$name")
            }
            for (caller in callers) {
                runCatching { module.deoptimize(caller) }
                    .onSuccess { success ->
                        module.log(
                            if (success) Log.INFO else Log.WARN,
                            TAG,
                            "deoptimize ${if (success) "success" else "returned false"}: $caller",
                        )
                    }
                    .onFailure { error ->
                        module.log(Log.WARN, TAG, "deoptimize failed: $caller", error)
                    }
            }
        }
    }

    private companion object {
        const val TAG = "AdbStayAwake"
    }
}
