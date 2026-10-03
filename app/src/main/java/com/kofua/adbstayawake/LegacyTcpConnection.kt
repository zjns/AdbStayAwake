package com.kofua.adbstayawake

/** 根据传统 adbd 的端口配置和内核 socket 快照判断 TCP 传输是否存在，不依赖认证密钥。 */
internal object LegacyTcpConnection {
    /**
     * 非空 service 属性覆盖 persist，包括 -1（关闭），避免关闭后被持久配置重新判为开启。
     * 返回 0 表示未配置有效端口，调用者可以跳过本轮内核查询。
     */
    fun port(service: String, persistent: String): Int =
        (service.ifEmpty { persistent }).toIntOrNull()?.takeIf { it in 1..65535 } ?: 0

    fun isConnected(port: Int, sockets: List<TcpSocketDiagnostics.Socket>): Boolean {
        if (port !in 1..65535) return false
        // TCP_LISTEN = 10。adbd 通常运行在 root(0) 或 shell(2000) 下；
        // 先确认配置端口确有对应 UID 的监听者，过滤普通应用占用同一端口的情况。
        val listenerUids = sockets.asSequence()
            .filter { it.port == port && it.state == 10 && (it.uid == 0 || it.uid == 2000) }
            .map { it.uid }.toSet()
        // TCP_ESTABLISHED = 1。本地端口和 UID 必须匹配监听端；任一客户端仍在连接即保活。
        // 这是传输层判断，尚未完成 ADB 授权的 TCP 连接也可能满足条件。
        return sockets.any { it.port == port && it.state == 1 && it.uid in listenerUids }
    }
}
