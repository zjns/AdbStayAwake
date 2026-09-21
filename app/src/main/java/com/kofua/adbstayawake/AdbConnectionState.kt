package com.kofua.adbstayawake

import java.util.concurrent.atomic.AtomicBoolean

internal class AdbConnectionState(
    private val onChanged: (Boolean) -> Unit,
) {
    private val connected = AtomicBoolean(false)

    fun update(
        usbConnections: Int,
        wifiConnections: Int,
        usbTransportConnected: Boolean,
    ): Boolean {
        val next = (usbTransportConnected && usbConnections > 0) || wifiConnections > 0
        val previous = connected.getAndSet(next)
        if (previous != next) onChanged(next)
        return next
    }

    fun isConnected(): Boolean = connected.get()
}
