package com.kofua.adbstayawake

import java.util.concurrent.atomic.AtomicBoolean

/** 合并各传输状态，仅在总体连接状态变化时通知电源策略重新计算屏幕超时。 */
internal class AdbConnectionState(
    private val onChanged: (Boolean) -> Unit,
) {
    // 电源判断只需读取总体结果，通过原子变量读取可避免在电源锁内获取本对象的锁。
    private val connected = AtomicBoolean(false)
    // 以下分量在同步方法中维护，防止系统 Handler 与 TCP 监测线程交错更新时丢失状态。
    private var wifiConnections = 0
    private var usbTransportConnected = false
    private var tcpTransportConnected = false

    /** 更新系统事件维护的 USB/配对无线状态，保留 TCP 监测线程发布的分量。 */
    @Synchronized
    fun update(
        wifiConnections: Int,
        usbTransportConnected: Boolean,
    ): Boolean {
        this.wifiConnections = wifiConnections
        this.usbTransportConnected = usbTransportConnected
        return publish()
    }

    /** 更新传统 TCP 分量，保留系统 Handler 发布的 USB/配对无线状态。 */
    @Synchronized
    fun updateTcpTransport(available: Boolean): Boolean {
        tcpTransportConnected = available
        return publish()
    }

    // 只在持有本对象锁时调用，串行合并分量并发送变化通知。
    private fun publish(): Boolean {
        // 免授权 ADB 可能没有认证密钥记录；USB 以已配置且启用 ADB 的传输状态为准。
        val next = usbTransportConnected || wifiConnections > 0 || tcpTransportConnected
        // 任一传输仍可用就保活；重复采样或单个分量变化不重复重算屏幕超时。
        val previous = connected.getAndSet(next)
        if (previous != next) onChanged(next)
        return next
    }

    fun isConnected(): Boolean = connected.get()
}
