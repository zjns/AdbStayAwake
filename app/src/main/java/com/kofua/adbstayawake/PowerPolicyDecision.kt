package com.kofua.adbstayawake

internal object PowerPolicyDecision {
    fun shouldKeepAwake(
        original: Boolean,
        adbConnected: Boolean,
        adminTimeoutEnforced: Boolean,
    ): Boolean = original || (adbConnected && !adminTimeoutEnforced)
}
