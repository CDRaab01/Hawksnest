package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lock-state labels — mirrors the web LockCard's jammed/pending wording. */
class LockStateTest {

    @Test
    fun `a jam reads as a clear failure with a retry hint`() {
        // The security-critical case: a jammed bolt must NOT read as "Unlocked".
        assertEquals("Jammed — try again", lockStateLabel("jammed"))
    }

    @Test
    fun `settled states`() {
        assertEquals("Locked", lockStateLabel("locked"))
        assertEquals("Unlocked", lockStateLabel("unlocked"))
    }

    @Test
    fun `transitional states`() {
        assertEquals("Locking…", lockStateLabel("locking"))
        assertEquals("Unlocking…", lockStateLabel("unlocking"))
    }

    @Test
    fun `unknown states fall back to title-case`() {
        assertEquals("Foo", lockStateLabel("foo"))
    }

    // lockVaultView — the vault card's pure view-model

    @Test
    fun `locked wears the secure glow and the bolt is thrown`() {
        val v = lockVaultView("locked")
        assertEquals(LockPhase.LOCKED, v.phase)
        assertEquals(Channel.RECOVERY, v.stateChannel)
        assertTrue(v.boltThrown)
        assertEquals("unlock", v.service)
        assertEquals("Slide to unlock", v.actionLabel)
        // Unlocking is the cautionary action — the track keeps its streak color.
        assertEquals(Channel.STREAK, v.actionChannel)
    }

    @Test
    fun `unlocked offers locking in recovery green, bolt retracted`() {
        val v = lockVaultView("unlocked")
        assertEquals(Channel.RECOVERY, v.actionChannel)
        assertNull(v.stateChannel)
        assertFalse(v.boltThrown)
        assertEquals("lock", v.service)
        assertEquals("Slide to lock", v.actionLabel)
    }

    @Test
    fun `jammed is a streak-flagged failure that retries the lock — never a thrown bolt`() {
        val v = lockVaultView("jammed")
        assertEquals(LockPhase.JAMMED, v.phase)
        assertEquals(Channel.STREAK, v.stateChannel)
        assertFalse(v.boltThrown)
        assertEquals("lock", v.service)
        assertEquals("Jammed — slide to retry", v.actionLabel)
        assertEquals("Jammed — try again", v.label)
        assertTrue(v.enabled)
    }

    @Test
    fun `transitional states hold pending labels and no thrown bolt`() {
        val locking = lockVaultView("locking")
        assertTrue(locking.transitional)
        assertEquals("Locking…", locking.pendingLabel)
        assertFalse(locking.boltThrown)
        val unlocking = lockVaultView("unlocking")
        assertTrue(unlocking.transitional)
        assertEquals("Unlocking…", unlocking.pendingLabel)
    }

    @Test
    fun `gate-pending on a locked bolt reads as unlocking`() {
        // Before the first echo, a committed slide on a locked door is an unlock in flight.
        assertEquals("Unlocking…", lockVaultView("locked").pendingLabel)
    }

    @Test
    fun `unavailable disables the vault, unknown states do not throw`() {
        assertFalse(lockVaultView("unavailable").enabled)
        assertEquals(LockPhase.UNKNOWN, lockVaultView("definitely_not_a_state").phase)
    }
}
