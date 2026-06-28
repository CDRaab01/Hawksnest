package com.hawksnest.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
