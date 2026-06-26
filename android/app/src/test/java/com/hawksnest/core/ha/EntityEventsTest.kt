package com.hawksnest.core.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the compressed `subscribe_entities` parser (`applyEntitiesEvent`). */
class EntityEventsTest {

    private fun frame(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `add brings a full entity`() {
        val map = applyEntitiesEvent(
            emptyMap(),
            frame("""{"a":{"lock.front":{"s":"locked","a":{"friendly_name":"Front"}}}}"""),
        )
        assertEquals("locked", map["lock.front"]?.state)
        assertEquals("Front", map["lock.front"]?.friendlyName())
    }

    @Test
    fun `change applies new state and merges attributes`() {
        var map = applyEntitiesEvent(
            emptyMap(),
            frame("""{"a":{"lock.front":{"s":"locked","a":{"friendly_name":"Front"}}}}"""),
        )
        map = applyEntitiesEvent(
            map,
            frame("""{"c":{"lock.front":{"+":{"s":"unlocked","a":{"battery":"90"}}}}}"""),
        )
        assertEquals("unlocked", map["lock.front"]?.state)
        assertEquals("Front", map["lock.front"]?.friendlyName()) // unchanged attr survives
        assertEquals("90", map["lock.front"]?.stringAttr("battery"))
    }

    @Test
    fun `change can remove an attribute`() {
        var map = applyEntitiesEvent(
            emptyMap(),
            frame("""{"a":{"lock.front":{"s":"locked","a":{"friendly_name":"Front","battery":"90"}}}}"""),
        )
        map = applyEntitiesEvent(map, frame("""{"c":{"lock.front":{"-":{"a":["battery"]}}}}"""))
        assertNull(map["lock.front"]?.stringAttr("battery"))
        assertEquals("Front", map["lock.front"]?.friendlyName())
    }

    @Test
    fun `remove drops the entity`() {
        var map = applyEntitiesEvent(
            emptyMap(),
            frame("""{"a":{"lock.front":{"s":"locked","a":{}}}}"""),
        )
        map = applyEntitiesEvent(map, frame("""{"r":["lock.front"]}"""))
        assertTrue(map.isEmpty())
    }
}
