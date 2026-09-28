package com.kofua.adbstayawake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbConnectionStateTest {
    @Test
    fun `USB or Wi-Fi connection is connected`() {
        val state = AdbConnectionState {}

        assertTrue(state.update(0, usbTransportConnected = true))
        assertTrue(state.update(1, usbTransportConnected = false))
        assertTrue(state.update(3, usbTransportConnected = true))
    }

    @Test
    fun `USB ADB without authentication records remains connected`() {
        val state = AdbConnectionState {}

        assertTrue(state.update(0, usbTransportConnected = true))
        assertTrue(state.isConnected())
    }

    @Test
    fun `USB availability changes only notify once`() {
        val events = mutableListOf<Boolean>()
        val state = AdbConnectionState(events::add)

        state.update(0, usbTransportConnected = true)
        state.update(0, usbTransportConnected = true)
        state.update(0, usbTransportConnected = true)

        assertEquals(listOf(true), events)
        assertTrue(state.isConnected())
        state.update(0, usbTransportConnected = false)
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun `unavailable USB and nonpositive Wi-Fi counts are disconnected`() {
        val state = AdbConnectionState {}

        assertFalse(state.update(0, usbTransportConnected = false))
        assertFalse(state.update(-1, usbTransportConnected = false))
    }

    @Test
    fun `overlapping transports and duplicate updates only notify boolean changes`() {
        val events = mutableListOf<Boolean>()
        val state = AdbConnectionState(events::add)

        state.update(0, usbTransportConnected = true)
        state.update(0, usbTransportConnected = true)
        state.update(1, usbTransportConnected = true)
        state.update(1, usbTransportConnected = false)
        state.update(0, usbTransportConnected = false)
        state.update(0, usbTransportConnected = false)

        assertEquals(listOf(true, false), events)
        assertFalse(state.isConnected())
    }

    @Test
    fun `USB disconnect without Wi-Fi stops keep awake`() {
        val events = mutableListOf<Boolean>()
        val state = AdbConnectionState(events::add)

        state.update(wifiConnections = 0, usbTransportConnected = true)
        state.update(wifiConnections = 0, usbTransportConnected = false)

        assertEquals(listOf(true, false), events)
        assertFalse(state.isConnected())
    }
}
