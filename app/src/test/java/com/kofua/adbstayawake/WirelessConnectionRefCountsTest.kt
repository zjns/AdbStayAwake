package com.kofua.adbstayawake

import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessConnectionRefCountsTest {
    @Test
    fun `same key stays connected until its last transport disconnects`() {
        val counts = WirelessConnectionRefCounts()

        assertEquals(1, counts.onConnected("shared-key"))
        assertEquals(2, counts.onConnected("shared-key"))
        assertEquals(1, counts.onDisconnected("shared-key"))
        assertEquals(0, counts.onDisconnected("shared-key"))
    }

    @Test
    fun `different keys contribute separate transports`() {
        val counts = WirelessConnectionRefCounts()

        counts.onConnected("key-a")
        counts.onConnected("key-b")

        assertEquals(2, counts.connectionCount())
        assertEquals(1, counts.onDisconnected("key-a"))
    }

    @Test
    fun `unknown disconnect never makes the count negative`() {
        val counts = WirelessConnectionRefCounts()

        assertEquals(0, counts.onDisconnected("unknown"))
        assertEquals(0, counts.connectionCount())
    }

    @Test
    fun `clear removes every active transport`() {
        val counts = WirelessConnectionRefCounts()
        counts.onConnected("key-a")
        counts.onConnected("key-a")
        counts.onConnected("key-b")

        counts.clear()

        assertEquals(0, counts.connectionCount())
    }
}
