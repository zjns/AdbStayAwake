package com.kofua.adbstayawake

import android.annotation.SuppressLint
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.net.SocketAddress

/** 在后台线程通过内核 Netlink 接口获取 TCP 快照，不建立到 ADB 端口的网络连接。 */
internal class TcpSocketReader(classLoader: ClassLoader) {
    // Netlink port ID=0 指向内核，groups=0 表示不订阅多播；该系统地址类型通过反射构造。
    private val kernelAddress = classLoader.loadClass("android.system.NetlinkSocketAddress")
        .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .newInstance(0, 0) as SocketAddress

    // 合并两个地址族的结果，覆盖 IPv6 以及常见的 IPv4-mapped IPv6 监听/连接。
    // 任一地址族读取异常时，不发布本轮的部分快照，交由上层处理失败。
    fun read(): List<TcpSocketDiagnostics.Socket> = readFamily(2) + readFamily(10)

    // connect(SocketAddress)、setsockoptTimeval 和 StructTimeval 在 API 26 已存在，
    // API 29 才公开；模块在 system_server 内使用这些系统 API。
    @SuppressLint("NewApi")
    private fun readFamily(family: Int): List<TcpSocketDiagnostics.Socket> {
        // NETLINK_INET_DIAG = 4。system_server 可查询；/proc/net/tcp 在部分 ROM 被 SELinux 禁止。
        // Linux AF_NETLINK = 16、SOCK_CLOEXEC = 0x80000（旧版 OsConstants 未暴露后者）。
        val fd = Os.socket(16, OsConstants.SOCK_DGRAM or 0x80000, 4)
        try {
            // 限制单次收发阻塞时间，避免内核无响应时监测线程一直停留在 I/O 中。
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(500))
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO,
                StructTimeval.fromMillis(500))
            Os.connect(fd, kernelAddress)
            val request = TcpSocketDiagnostics.request(family)
            check(Os.write(fd, request, 0, request.size) == request.size) { "Incomplete socket request" }
            val sockets = mutableListOf<TcpSocketDiagnostics.Socket>()
            val bytes = ByteArray(65536)
            // 单次接收有超时仍不足以限制持续返回片段的 dump，因此另设整轮读取期限。
            val deadline = System.nanoTime() + 1_000_000_000L
            while (System.nanoTime() < deadline) {
                // MSG_TRUNC 让返回值反映原始数据报长度，超过缓冲区时明确拒绝截断的快照。
                val length = Os.recvfrom(fd, bytes, 0, bytes.size, OsConstants.MSG_TRUNC, null)
                check(length in 1..bytes.size) { "Truncated socket dump" }
                val chunk = TcpSocketDiagnostics.parse(bytes.copyOf(length))
                sockets += chunk.sockets
                // 持续累积各数据报，直到 NLMSG_DONE；首批 socket 不能代表完整结果。
                if (chunk.done) return sockets
            }
            error("Socket dump timed out")
        } finally {
            // 每个地址族独立创建 socket；包括解析错误和超时在内的所有退出路径都关闭它。
            Os.close(fd)
        }
    }
}
