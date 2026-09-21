package com.kofua.adbstayawake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbConnectionStateTest {
    @Test
    fun `USB or Wi-Fi connection is connected`() {
        val state = AdbConnectionState {}

        assertTrue(state.update(1, 0, usbTransportConnected = true))
        assertTrue(state.update(0, 1, usbTransportConnected = false))
        assertTrue(state.update(2, 3, usbTransportConnected = true))
    }

    @Test
    fun `zero and negative counts are disconnected`() {
        val state = AdbConnectionState {}

        assertFalse(state.update(0, 0, usbTransportConnected = true))
        assertFalse(state.update(-1, -2, usbTransportConnected = true))
    }

    @Test
    fun `USB reference count remains connected until the last transport disconnects`() {
        val events = mutableListOf<Boolean>()
        val state = AdbConnectionState(events::add)

        state.update(2, 0, usbTransportConnected = true)
        state.update(1, 0, usbTransportConnected = true)
        state.update(0, 0, usbTransportConnected = true)

        assertEquals(listOf(true, false), events)
    }

    @Test
    fun `overlapping transports and duplicate updates only notify boolean changes`() {
        val events = mutableListOf<Boolean>()
        val state = AdbConnectionState(events::add)

        state.update(2, 0, usbTransportConnected = true)
        state.update(1, 0, usbTransportConnected = true)
        state.update(1, 1, usbTransportConnected = true)
        state.update(0, 1, usbTransportConnected = false)
        state.update(0, 0, usbTransportConnected = false)
        state.update(0, 0, usbTransportConnected = false)

        assertEquals(listOf(true, false), events)
        assertFalse(state.isConnected())
    }

    @Test
    fun `stale USB key is disconnected when the physical transport is unavailable`() {
        val events = mutableListOf<Boolean>()
        val state = AdbConnectionState(events::add)

        state.update(usbConnections = 1, wifiConnections = 0, usbTransportConnected = true)
        state.update(usbConnections = 1, wifiConnections = 0, usbTransportConnected = false)

        assertEquals(listOf(true, false), events)
        assertFalse(state.isConnected())
    }
}
