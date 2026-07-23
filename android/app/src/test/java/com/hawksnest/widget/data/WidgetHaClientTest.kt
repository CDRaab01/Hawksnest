package com.hawksnest.widget.data

import com.hawksnest.core.logic.WidgetBlocker
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The widgets' REST path against a real socket. Every branch here is a face the widget has to
 * draw — a reading, a rejected token, a missing entity, a tailnet that isn't up — so each one is
 * checked as the [WidgetBlocker] it becomes rather than as an exception.
 */
class WidgetHaClientTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class FakeCredentials(var value: HaCredentials?) : CredentialSource {
        override suspend fun current(): HaCredentials? = value
    }

    private fun client(credentials: CredentialSource) =
        WidgetHaClient(OkHttpClient(), json, credentials)

    private fun credentialsFor(server: MockWebServer) =
        FakeCredentials(HaCredentials(server.url("/").toString().trimEnd('/'), "test-token"))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reads an entity and its attributes`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"entity_id":"lock.front_door","state":"locked","attributes":{"friendly_name":"Front Door"}}"""
            )
        )
        val result = client(credentialsFor(server)).state("lock.front_door")
        assertTrue(result is HaCall.Ok)
        assertEquals("locked", result.value.state)
        assertEquals("Front Door", result.value.attributes["friendly_name"]?.jsonPrimitive?.content)

        val request = server.takeRequest()
        assertEquals("/api/states/lock.front_door", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun `a rejected token is reported as such, not as unreachable`() = runTest {
        // The two need different words: one is fixed in Settings, the other by turning on the VPN.
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client(credentialsFor(server)).state("lock.front_door")
        assertEquals(HaCall.Failed(WidgetBlocker.UNAUTHORIZED), result)
    }

    @Test
    fun `a deleted entity is reported as missing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            HaCall.Failed(WidgetBlocker.ENTITY_MISSING),
            client(credentialsFor(server)).state("lock.gone"),
        )
    }

    @Test
    fun `a server error is unreachable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502))
        assertEquals(
            HaCall.Failed(WidgetBlocker.UNREACHABLE),
            client(credentialsFor(server)).state("lock.front_door"),
        )
    }

    @Test
    fun `a dead host is unreachable, not a crash`() = runTest {
        val credentials = credentialsFor(server)
        server.shutdown()
        assertEquals(
            HaCall.Failed(WidgetBlocker.UNREACHABLE),
            client(credentials).state("lock.front_door"),
        )
    }

    @Test
    fun `a 200 that isn't the expected shape is unreachable, not a parse crash`() = runTest {
        // A proxy error page served with a 200 is a real possibility behind a tunnel.
        server.enqueue(MockResponse().setBody("<html>gateway</html>"))
        assertEquals(
            HaCall.Failed(WidgetBlocker.UNREACHABLE),
            client(credentialsFor(server)).state("lock.front_door"),
        )
    }

    @Test
    fun `no saved credentials means signed out, and no request is made`() = runTest {
        val result = client(FakeCredentials(null)).state("lock.front_door")
        assertEquals(HaCall.Failed(WidgetBlocker.SIGNED_OUT), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a service call posts the entity and its extras`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        val result = client(credentialsFor(server)).callService(
            domain = "light",
            service = "turn_on",
            entityId = "light.kitchen",
            extra = mapOf("brightness_pct" to 40),
        )
        assertTrue(result is HaCall.Ok)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/services/light/turn_on", request.path)
        val body = json.decodeFromString(JsonObject.serializer(), request.body.readUtf8())
        assertEquals("light.kitchen", body["entity_id"]?.jsonPrimitive?.content)
        assertEquals("40", body["brightness_pct"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a null extra is omitted rather than sent as null`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        client(credentialsFor(server)).callService(
            domain = "lock",
            service = "lock",
            entityId = "lock.front_door",
            extra = mapOf("code" to null),
        )
        val body = json.decodeFromString(JsonObject.serializer(), server.takeRequest().body.readUtf8())
        assertTrue("code" !in body)
    }

    @Test
    fun `a trailing slash on the saved URL doesn't double up in the path`() = runTest {
        server.enqueue(MockResponse().setBody("""{"entity_id":"light.kitchen","state":"on"}"""))
        val credentials = FakeCredentials(HaCredentials(server.url("/").toString(), "test-token"))
        client(credentials).state("light.kitchen")
        assertEquals("/api/states/light.kitchen", server.takeRequest().path)
    }

    @Test
    fun `the entity list comes back parsed`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"entity_id":"light.kitchen","state":"on"},{"entity_id":"lock.front_door","state":"locked"}]"""
            )
        )
        val result = client(credentialsFor(server)).states()
        assertTrue(result is HaCall.Ok)
        assertEquals(listOf("light.kitchen", "lock.front_door"), result.value.map { it.entityId })
    }
}
