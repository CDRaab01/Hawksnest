package com.hawksnest.push

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NtfyMessageTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parses a real message frame`() {
        val line = """
            {"id":"abc","time":1,"event":"message","topic":"hawksnest-alerts",
             "title":"Doorbell","message":"Someone's at Front Door","tags":["bell"],
             "priority":4,"click":"https://host/"}
        """.trimIndent().replace("\n", "")
        val msg = NtfyMessage.parse(line, json)!!
        assertEquals("abc", msg.id)
        assertEquals("Doorbell", msg.title)
        assertEquals("Someone's at Front Door", msg.body)
        assertEquals(listOf("bell"), msg.tags)
        assertEquals(4, msg.priority)
        assertEquals("https://host/", msg.click)
    }

    @Test
    fun `ignores keepalive and open control frames`() {
        assertNull(NtfyMessage.parse("""{"id":"1","event":"keepalive","topic":"t"}""", json))
        assertNull(NtfyMessage.parse("""{"id":"1","event":"open","topic":"t"}""", json))
    }

    @Test
    fun `ignores a message frame with no body`() {
        assertNull(NtfyMessage.parse("""{"id":"1","event":"message","topic":"t"}""", json))
        assertNull(NtfyMessage.parse("""{"id":"1","event":"message","topic":"t","message":"  "}""", json))
    }

    @Test
    fun `ignores blank and malformed lines`() {
        assertNull(NtfyMessage.parse("", json))
        assertNull(NtfyMessage.parse("   ", json))
        assertNull(NtfyMessage.parse("not json", json))
        assertNull(NtfyMessage.parse("""{"event":"message" """, json)) // truncated
    }

    @Test
    fun `parses an attachment url (doorbell snapshot)`() {
        val line = """
            {"id":"1","event":"message","topic":"t","message":"ding","tags":["bell"],
             "attachment":{"name":"front.jpg","url":"https://h/api/camera_proxy/camera.x?token=z"}}
        """.trimIndent().replace("\n", "")
        val msg = NtfyMessage.parse(line, json)!!
        assertEquals("https://h/api/camera_proxy/camera.x?token=z", msg.attachUrl)
    }

    @Test
    fun `attachUrl is null when there is no attachment`() {
        val msg = NtfyMessage.parse("""{"event":"message","topic":"t","message":"hi"}""", json)!!
        assertNull(msg.attachUrl)
    }

    @Test
    fun `falls back to defaults for title and id`() {
        val msg = NtfyMessage.parse("""{"event":"message","topic":"t","message":"hi"}""", json)!!
        assertEquals("Hawksnest", msg.title)
        assertTrue(msg.id.isNotBlank())
        assertEquals(3, msg.priority) // ntfy default
    }
}
