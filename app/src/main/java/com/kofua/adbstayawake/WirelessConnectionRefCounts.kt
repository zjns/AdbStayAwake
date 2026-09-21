package com.kofua.adbstayawake

internal class WirelessConnectionRefCounts {
    private val countsByKey = mutableMapOf<String, Int>()
    private var total = 0

    @Synchronized
    fun onConnected(key: String?): Int {
        if (key.isNullOrEmpty()) return total
        countsByKey[key] = (countsByKey[key] ?: 0) + 1
        total++
        return total
    }

    @Synchronized
    fun onDisconnected(key: String?): Int {
        if (key.isNullOrEmpty()) return total
        val count = countsByKey[key] ?: return total
        if (count == 1) {
            countsByKey.remove(key)
        } else {
            countsByKey[key] = count - 1
        }
        total--
        return total
    }

    @Synchronized
    fun clear() {
        countsByKey.clear()
        total = 0
    }

    @Synchronized
    fun connectionCount(): Int = total
}
