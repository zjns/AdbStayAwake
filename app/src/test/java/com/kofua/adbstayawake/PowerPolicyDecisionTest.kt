package com.kofua.adbstayawake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPolicyDecisionTest {
    @Test
    fun `original kept-awake result remains true`() {
        assertTrue(
            PowerPolicyDecision.shouldKeepAwake(
                original = true,
                adbConnected = false,
                adminTimeoutEnforced = false,
            ),
        )
    }

    @Test
    fun `ADB connection keeps awake without an admin timeout`() {
        assertTrue(
            PowerPolicyDecision.shouldKeepAwake(
                original = false,
                adbConnected = true,
                adminTimeoutEnforced = false,
            ),
        )
    }

    @Test
    fun `device admin timeout is not bypassed`() {
        assertFalse(
            PowerPolicyDecision.shouldKeepAwake(
                original = false,
                adbConnected = true,
                adminTimeoutEnforced = true,
            ),
        )
    }

    @Test
    fun `disconnected ADB preserves false`() {
        assertFalse(
            PowerPolicyDecision.shouldKeepAwake(
                original = false,
                adbConnected = false,
                adminTimeoutEnforced = false,
            ),
        )
    }
}
