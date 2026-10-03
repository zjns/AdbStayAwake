package com.kofua.adbstayawake

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Linux INET_DIAG 请求编码与响应解析；与 Android I/O 分离，便于直接执行 JVM 回归测试。 */
internal object TcpSocketDiagnostics {
    /** 只保留判定所需的本地端口、TCP 状态和所属 UID，不保存远端地址等明细。 */
    data class Socket(val port: Int, val state: Int, val uid: Int)
    /** 一次 Netlink 接收可能只有部分 socket；done 表示已收到整次 dump 的结束消息。 */
    data class Chunk(val sockets: List<Socket>, val done: Boolean)

    /** 构造 16 字节 nlmsghdr + 56 字节 inet_diag_req_v2，不限制具体 socket 地址。 */
    fun request(family: Int): ByteArray = ByteArray(72).also {
        // Linux AF_INET = 2（IPv4）、AF_INET6 = 10（IPv6）。
        require(family == 2 || family == 10)
        // 消息头：长度位于 0，类型位于 4（SOCK_DIAG_BY_FAMILY = 20），
        // 标志位于 6（NLM_F_REQUEST | NLM_F_DUMP = 0x301）；其余头字段保持 0。
        // 请求体从 16 开始：地址族、IPPROTO_TCP(6)，以及位于 20 的状态位图。
        // 状态位图只包含 ESTABLISHED(1) 和 LISTEN(10)，减少内核返回的数据量。
        ByteBuffer.wrap(it).order(ByteOrder.nativeOrder())
            .putInt(0, 72).putShort(4, 20).putShort(6, 0x301)
            .put(16, family.toByte()).put(17, 6).putInt(20, (1 shl 1) or (1 shl 10))
    }

    /** 解析一个数据报中的多条消息；不完整或失败的 dump 抛异常，由监测端取消 TCP 保活。 */
    fun parse(data: ByteArray): Chunk {
        // Netlink 头和普通整数字段采用本机字节序，socket 的端口字段采用网络字节序。
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
        val sockets = mutableListOf<Socket>()
        var offset = 0
        var done = false
        while (offset < data.size) {
            // nlmsg_len 包含 16 字节消息头；先验证长度，再访问后续字段，避免截断数据越界。
            require(data.size - offset >= 16) { "Truncated netlink header" }
            val length = buffer.getInt(offset)
            require(length >= 16 && length <= data.size - offset) { "Truncated netlink message" }
            val type = buffer.getShort(offset + 4).toInt() and 0xffff
            val flags = buffer.getShort(offset + 6).toInt() and 0xffff
            // NLM_F_DUMP_INTR 表示内核未提供一致的完整快照，不能用于连接判定。
            require(flags and 0x10 == 0) { "Interrupted socket dump" }
            when (type) {
                2, 3 -> {
                    // NLMSG_ERROR(2) / NLMSG_DONE(3) 的首个正文整数为状态：0 成功，非 0 失败。
                    // 成功的 ACK 不代表 dump 结束，只有 DONE 才能结束整次读取。
                    require(length >= 20) { "Missing netlink status" }
                    require(buffer.getInt(offset + 16) == 0) { "Socket dump failed" }
                    if (type == 3) done = true
                }
                20 -> {
                    // SOCK_DIAG_BY_FAMILY：16 字节消息头 + 至少 72 字节 inet_diag_msg。
                    require(length >= 88) { "Truncated inet_diag_msg" }
                    val family = buffer.get(offset + 16).toInt() and 0xff
                    require(family == 2 || family == 10) { "Unexpected socket family" }
                    // 相对当前消息的偏移：family=16、state=17、源端口=20、uid=80。
                    // 源端口即设备本地端口，用高字节在前的网络字节序还原。
                    val port = ((data[offset + 20].toInt() and 0xff) shl 8) or
                        (data[offset + 21].toInt() and 0xff)
                    sockets += Socket(port, buffer.get(offset + 17).toInt() and 0xff,
                        buffer.getInt(offset + 80))
                }
                else -> throw IllegalArgumentException("Unexpected netlink message type: $type")
            }
            // 每条 Netlink 消息按 4 字节边界对齐，下一条从填充字节之后开始。
            val aligned = (length + 3) and -4
            require(aligned <= data.size - offset) { "Truncated netlink padding" }
            offset += aligned
        }
        return Chunk(sockets, done)
    }
}
