package com.kofua.adbstayawake

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 将传统 TCP 传输状态定期发布到保活状态；生命周期随 system_server 进程结束。 */
internal class LegacyTcpDebuggingHook(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) {
    fun install(state: AdbConnectionState) {
        val getProperty = classLoader.loadClass("android.os.SystemProperties")
            .getDeclaredMethod("get", String::class.java)
        val reader = TcpSocketReader(classLoader)
        // null 确保首轮即使未连接也输出状态；这两个变量仅由单个监测线程访问。
        var previous: Boolean? = null
        var failed = false
        // 不依赖认证或 USB Handler 事件，覆盖启动时已有连接以及免授权 adbd。
        val worker = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "AdbStayAwake-TCP").apply { isDaemon = true }
        }
        // 立即执行首轮；每轮结束两秒后再执行，避免慢查询导致积压或并行查询。
        worker.scheduleWithFixedDelay({
            try {
                // 每轮重新读属性，允许运行中开启、关闭或切换传统 ADB 端口。
                val port = LegacyTcpConnection.port(
                    getProperty.invoke(null, "service.adb.tcp.port") as String,
                    getProperty.invoke(null, "persist.adb.tcp.port") as String,
                )
                // 端口无效时短路，不读取内核；端口有效也必须存在已建立连接才保活。
                val available = port != 0 && LegacyTcpConnection.isConnected(port, reader.read())
                // 仅首轮、连接状态变化或故障恢复时记录，避免两秒一次的重复日志。
                if (previous != available || failed) {
                    module.log(Log.INFO, TAG, "TCP state: port=$port, available=$available")
                }
                previous = available
                failed = false
                // 只更新 TCP 分量，USB 和配对无线调试的状态由各自的事件维护。
                state.updateTcpTransport(available)
            } catch (error: Exception) {
                // 连续失败仅记录一次；捕获异常后定时任务仍会在下一轮重试。
                if (!failed) module.log(Log.ERROR, TAG, "failed to read legacy ADB TCP state", error)
                failed = true
                previous = false
                // 不沿用上次成功读取的 true，避免故障期间残留 TCP 保活状态。
                state.updateTcpTransport(false)
            }
        }, 0, 2, TimeUnit.SECONDS)
    }

    private companion object {
        const val TAG = "AdbStayAwake"
    }
}
