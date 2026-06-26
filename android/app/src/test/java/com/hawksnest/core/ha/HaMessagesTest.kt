package com.hawksnest.core.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers the WS envelope builders + frame parsers. */
class HaMessagesTest {

    private fun frame(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `auth builder carries the token`() {
        val m = HaMessages.auth("tok123")
        assertEquals("auth", m["type"]?.jsonPrimitive?.content)
        assertEquals("tok123", m["access_token"]?.jsonPrimitive?.content)
    }

    @Test
    fun `callService targets the entity`() {
        val m = HaMessages.callService(3, "lock", "unlock", "lock.front")
        assertEquals(3, HaMessages.frameId(m))
        assertEquals("call_service", HaMessages.frameType(m))
        assertEquals("lock", m["domain"]?.jsonPrimitive?.content)
        assertEquals("unlock", m["service"]?.jsonPrimitive?.content)
        assertEquals("lock.front", m["target"]?.jsonObject?.get("entity_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `callService omits service_data when none given`() {
        val m = HaMessages.callService(1, "switch", "turn_on", "switch.fan")
        assertFalse(m.containsKey("service_data"))
    }

    @Test
    fun `callService includes service_data with extra payload`() {
        val m = HaMessages.callService(
            7, "light", "turn_on", "light.kitchen",
            serviceData = mapOf("brightness_pct" to 50),
        )
        val data = m["service_data"]?.jsonObject
        assertEquals(50, data?.get("brightness_pct")?.jsonPrimitive?.content?.toInt())
        // target still set alongside the data
        assertEquals("light.kitchen", m["target"]?.jsonObject?.get("entity_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `result frame success + type`() {
        val ok = frame("""{"id":1,"type":"result","success":true,"result":null}""")
        assertEquals("result", HaMessages.frameType(ok))
        assertEquals(1, HaMessages.frameId(ok))
        assertTrue(HaMessages.resultSuccess(ok))

        val fail = frame("""{"id":2,"type":"result","success":false,"error":{"message":"nope"}}""")
        assertFalse(HaMessages.resultSuccess(fail))
        assertEquals("nope", HaMessages.errorMessage(fail))
    }

    @Test
    fun `auth_invalid carries a message`() {
        val bad = frame("""{"type":"auth_invalid","message":"Invalid access token"}""")
        assertEquals(HaMessages.TYPE_AUTH_INVALID, HaMessages.frameType(bad))
        assertEquals("Invalid access token", HaMessages.errorMessage(bad))
    }
}
