package com.kofua.adbstayawake

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookDeoptimizerTest {
    private val attempted = mutableListOf<Method>()
    private val logs = mutableListOf<String>()
    private val failures = mutableMapOf<String, Throwable>()
    private val rejected = mutableSetOf<String>()
    private val framework = Proxy.newProxyInstance(
        XposedInterface::class.java.classLoader,
        arrayOf(XposedInterface::class.java),
    ) { _, method, args ->
        when (method.name) {
            "deoptimize" -> {
                val target = args!![0] as Method
                attempted += target
                failures[target.name]?.let { throw it }
                target.name !in rejected
            }
            "log" -> {
                logs += args!![2] as String
                null
            }
            else -> error("Unexpected framework call: ${method.name}")
        }
    } as XposedInterface
    private val deoptimizer = HookDeoptimizer(framework, javaClass.classLoader!!)

    @Test
    fun `deoptimizes all caller overloads without touching unrelated methods`() {
        deoptimizer.deoptimizeCallers(Callers::class.java.name, "caller")

        assertEquals(
            setOf(
                Callers::class.java.getDeclaredMethod("caller"),
                Callers::class.java.getDeclaredMethod("caller", Int::class.javaPrimitiveType),
            ),
            attempted.toSet(),
        )
        assertTrue(logs.any { "caller" in it && "success" in it })
    }

    @Test
    fun `missing method does not prevent remaining callers from being deoptimized`() {
        deoptimizer.deoptimizeCallers(Callers::class.java.name, "missing", "other")

        assertEquals(listOf("other"), attempted.map { it.name })
        assertTrue(logs.any { "missing" in it && "not found" in it })
    }

    @Test
    fun `false result and exception do not prevent remaining callers from being deoptimized`() {
        rejected += "other"
        failures["broken"] = IllegalStateException("ART unavailable")

        deoptimizer.deoptimizeCallers(Callers::class.java.name, "other", "broken", "caller")

        assertEquals(4, attempted.size)
        assertEquals(setOf("other", "broken", "caller"), attempted.map { it.name }.toSet())
        assertTrue(logs.any { "other" in it && "false" in it })
        assertTrue(logs.any { "broken" in it && "failed" in it })
        assertTrue(logs.any { "caller" in it && "success" in it })
    }

    @Test
    fun `missing class is reported and does not block a later class`() {
        deoptimizer.deoptimizeCallers("missing.rom.Class", "caller")
        deoptimizer.deoptimizeCallers(Callers::class.java.name, "other")

        assertEquals(listOf("other"), attempted.map { it.name })
        assertTrue(logs.any { "missing.rom.Class" in it && "failed" in it })
    }

    private class Callers {
        fun caller() = Unit
        fun caller(value: Int): Int = value
        fun other() = Unit
        fun broken() = Unit
    }
}
