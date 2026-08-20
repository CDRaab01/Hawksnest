package com.hawksnest.ui.cameras

import com.hawksnest.ui.home.CameraUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun cam(id: String) = CameraUi(
    id = "camera.$id",
    entityId = "camera.${id}_live",
    name = id,
    live = true,
)

class CameraSessionTest {

    @Test
    fun `opening a live camera wants PiP, closed session does not`() {
        val session = CameraSession()
        assertFalse(session.wantsPip())
        session.open(listOf(cam("front")), cam("front"))
        assertTrue(session.wantsPip())
        session.close()
        assertFalse(session.wantsPip())
    }

    @Test
    fun `scrubbing to recorded stops wanting PiP, going live restores it`() {
        val session = CameraSession()
        session.open(listOf(cam("front")), cam("front"))
        session.reportLive(false)
        assertFalse(session.wantsPip())
        session.reportLive(true)
        assertTrue(session.wantsPip())
    }

    @Test
    fun `close resets live and video size for the next open`() {
        val session = CameraSession()
        session.open(listOf(cam("front")), cam("front"))
        session.reportLive(false)
        session.reportVideoSize(1920, 1080)
        session.close()
        assertNull(session.open.value)
        assertTrue(session.isLive.value)
        assertNull(session.videoSize.value)
        // A new open starts live even though the last view ended on a recording.
        session.open(listOf(cam("back")), cam("back"))
        assertTrue(session.wantsPip())
    }

    @Test
    fun `reopening bumps the nonce and replaces the target - the doorbell retarget case`() {
        val session = CameraSession()
        session.open(listOf(cam("front")), cam("front"))
        val first = session.open.value!!
        // A doorbell push retargets the already-open lightbox at the same or another camera:
        // the nonce must change so the host resets its switched-to camera even for equal ids.
        session.open(listOf(cam("front"), cam("door")), cam("door"), eventId = "ev1")
        val second = session.open.value!!
        assertTrue(second.nonce > first.nonce)
        assertEquals("camera.door", second.initial.id)
        assertEquals("ev1", second.eventId)
    }

    @Test
    fun `reopen resets a stale recorded state`() {
        val session = CameraSession()
        session.open(listOf(cam("front")), cam("front"))
        session.reportLive(false)
        session.reportVideoSize(1280, 720)
        session.open(listOf(cam("front")), cam("front"))
        assertTrue(session.isLive.value)
        assertNull(session.videoSize.value)
    }

    @Test
    fun `updateCameras refreshes the switcher list and nothing else`() {
        val session = CameraSession()
        session.open(listOf(cam("front")), cam("front"), eventId = "ev1")
        val before = session.open.value!!
        session.updateCameras(listOf(cam("front"), cam("back")))
        val after = session.open.value!!
        assertEquals(2, after.cameras.size)
        assertEquals(before.initial, after.initial)
        assertEquals(before.eventId, after.eventId)
        assertEquals(before.nonce, after.nonce)
    }

    @Test
    fun `updateCameras on a closed session stays closed`() {
        val session = CameraSession()
        session.updateCameras(listOf(cam("front")))
        assertNull(session.open.value)
    }

    @Test
    fun `inPip is a plain flag`() {
        val session = CameraSession()
        assertFalse(session.inPip.value)
        session.setInPip(true)
        assertTrue(session.inPip.value)
        session.setInPip(false)
        assertFalse(session.inPip.value)
    }
}
