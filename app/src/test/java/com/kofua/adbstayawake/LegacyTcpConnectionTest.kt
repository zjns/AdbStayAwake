package com.kofua.adbstayawake

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LegacyTcpConnectionTest {
    @Test
    fun `port property follows adbd service override and persistent fallback`() {
        assertEquals(5555, LegacyTcpConnection.port("5555", "5556"))
        assertEquals(5556, LegacyTcpConnection.port("", "5556"))
        for (value in listOf("-1", "0", "65536", "bad")) {
            assertEquals(0, LegacyTcpConnection.port(value, "5556"))
        }
    }

    @Test
    fun `IPv4 and IPv6 connections work without authentication keys`() {
        for (family in listOf(2, 10)) {
            val sockets = TcpSocketDiagnostics.parse(socket(family, 10) + socket(family, 1)).sockets
            assertTrue(LegacyTcpConnection.isConnected(5555, sockets))
        }
    }

    @Test
    fun `listening alone closing connections and disabled port do not keep awake`() {
        val listening = TcpSocketDiagnostics.parse(socket(10, 10)).sockets
        assertFalse(LegacyTcpConnection.isConnected(5555, listening))
        val closing = TcpSocketDiagnostics.parse(socket(10, 10) + socket(10, 8)).sockets
        assertFalse(LegacyTcpConnection.isConnected(5555, closing))
        val active = TcpSocketDiagnostics.parse(socket(10, 10) + socket(10, 1)).sockets
        assertFalse(LegacyTcpConnection.isConnected(0, active))
    }

    @Test
    fun `other ports app sockets and connections without listener are ignored`() {
        assertFalse(LegacyTcpConnection.isConnected(5556,
            TcpSocketDiagnostics.parse(socket(2, 10) + socket(2, 1)).sockets))
        assertFalse(LegacyTcpConnection.isConnected(5555,
            TcpSocketDiagnostics.parse(socket(2, 10, uid = 10001) + socket(2, 1, uid = 10001)).sockets))
        assertFalse(LegacyTcpConnection.isConnected(5555,
            TcpSocketDiagnostics.parse(socket(2, 1)).sockets))
        assertTrue(LegacyTcpConnection.isConnected(5555,
            TcpSocketDiagnostics.parse(socket(2, 10, uid = 2000) + socket(2, 1, uid = 2000)).sockets))
    }

    @Test
    fun `multiple clients remain connected until the last disconnect`() {
        val listener = TcpSocketDiagnostics.parse(socket(10, 10)).sockets
        val client = TcpSocketDiagnostics.parse(socket(10, 1)).sockets
        val events = mutableListOf<Boolean>()
        val state = AdbConnectionState(events::add)
        for (sockets in listOf(listener + client + client, listener + client, listener)) {
            state.updateTcpTransport(LegacyTcpConnection.isConnected(5555, sockets))
        }
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun `TCP overlaps USB and paired wireless without premature disconnect`() {
        val events = mutableListOf<Boolean>()
        val state = AdbConnectionState(events::add)
        assertTrue(state.updateTcpTransport(true))
        assertEquals(listOf(true), events)
        state.update(0, true)
        state.updateTcpTransport(false)
        assertTrue(state.isConnected())
        state.updateTcpTransport(true)
        state.update(1, false)
        state.updateTcpTransport(false)
        assertTrue(state.isConnected())
        state.update(0, false)
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun `dump request uses TCP established and listening states`() {
        val request = ByteBuffer.wrap(TcpSocketDiagnostics.request(10)).order(ByteOrder.nativeOrder())
        assertEquals(72, request.getInt(0))
        assertEquals(20, request.getShort(4).toInt())
        assertEquals(0x301, request.getShort(6).toInt())
        assertEquals(10, request.get(16).toInt())
        assertEquals(6, request.get(17).toInt())
        assertEquals((1 shl 1) or (1 shl 10), request.getInt(20))
    }

    @Test
    fun `multipart completion and aligned attributes are parsed`() {
        val message = socket(10, 1).copyOf(92)
        ByteBuffer.wrap(message).order(ByteOrder.nativeOrder()).putInt(0, 89)
        val chunk = TcpSocketDiagnostics.parse(message + header(3, 20))
        assertEquals(1, chunk.sockets.size)
        assertTrue(chunk.done)
    }

    @Test
    fun `kernel errors interrupted dumps and truncation are rejected`() {
        val error = header(2, 20)
        ByteBuffer.wrap(error).order(ByteOrder.nativeOrder()).putInt(16, -13)
        val interrupted = header(3, 20)
        ByteBuffer.wrap(interrupted).order(ByteOrder.nativeOrder()).putShort(6, 0x10)
        for (data in listOf(error, interrupted, socket(2, 1).copyOf(60), byteArrayOf(1))) {
            assertThrows(IllegalArgumentException::class.java) { TcpSocketDiagnostics.parse(data) }
        }
    }

    private fun socket(family: Int, state: Int, uid: Int = 0): ByteArray =
        header(20, 88).also {
            // 按真实 inet_diag_msg 布局构造测试数据，覆盖头偏移和混合字节序的解析。
            val buffer = ByteBuffer.wrap(it).order(ByteOrder.nativeOrder())
            buffer.put(16, family.toByte())
            buffer.put(17, state.toByte())
            buffer.putInt(80, uid)
            buffer.order(ByteOrder.BIG_ENDIAN).putShort(20, 5555)
        }

    private fun header(type: Int, size: Int): ByteArray = ByteArray(size).also {
        // 其余字段保持 0；错误测试会覆写状态，multipart 测试会追加对齐填充和 DONE。
        ByteBuffer.wrap(it).order(ByteOrder.nativeOrder()).putInt(0, size).putShort(4, type.toShort())
    }
}
