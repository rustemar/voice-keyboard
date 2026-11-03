package com.tyraen.voicekeyboard.feature.ime

import org.junit.Assert.*
import org.junit.Test

class AutoSendPolicyTest {
    @Test fun `disabled option creates no consent`() {
        val policy = AutoSendPolicy()
        assertNull(policy.prepare(false))
        assertFalse(policy.dispatchOnce(null) { error("Must not send") })
    }

    @Test fun `turning timer off revokes pending consent permanently`() {
        val policy = AutoSendPolicy()
        val old = policy.prepare(true)
        policy.invalidate()
        val fresh = policy.prepare(true)
        assertFalse(policy.dispatchOnce(old) { error("Old dictation must not send") })
        assertTrue(policy.dispatchOnce(fresh) { true })
    }

    @Test fun `a new capture cancels the old capture even when new one is ineligible`() {
        val policy = AutoSendPolicy()
        val old = policy.prepare(true)
        assertNull(policy.prepare(false))
        assertFalse(policy.isCurrent(old))
        assertFalse(policy.dispatchOnce(old) { error("Must not send an older draft") })
    }

    @Test fun `provider failure cancels consent without a replacement`() {
        val policy = AutoSendPolicy()
        val request = policy.prepare(true)
        request!!.cancel()
        assertFalse(policy.dispatchOnce(request) { error("Must not send fallback text") })
    }

    @Test fun `accepted rejected and throwing send actions are attempted at most once`() {
        for (outcome in listOf("accepted", "rejected", "throwing")) {
            val policy = AutoSendPolicy()
            val request = policy.prepare(true)
            var calls = 0
            val accepted = policy.dispatchOnce(request) {
                calls++
                if (outcome == "throwing") throw IllegalStateException("Host unavailable")
                outcome == "accepted"
            }
            assertEquals(outcome == "accepted", accepted)
            assertFalse(policy.dispatchOnce(request) { calls++; true })
            assertEquals(1, calls)
        }
    }
}
