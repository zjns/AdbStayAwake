package com.kofua.adbstayawake

import java.util.concurrent.atomic.AtomicBoolean

internal class AdbConnectionState(
    private val onChanged: (Boolean) -> Unit,
) {
    private val connected = AtomicBoolean(false)

    fun update(
        wifiConnections: Int,
        usbTransportConnected: Boolean,
    ): Boolean {
        // 免授权 ADB 可能没有认证密钥记录；USB 以已配置且启用 ADB 的传输状态为准。
        val next = usbTransportConnected || wifiConnections > 0
        val previous = connected.getAndSet(next)
        if (previous != next) onChanged(next)
        return next
    }

    fun isConnected(): Boolean = connected.get()
}
