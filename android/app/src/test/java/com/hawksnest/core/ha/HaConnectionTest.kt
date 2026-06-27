package com.hawksnest.core.ha

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test

/**
 * Regression cover for the auth-handshake race that bricked the live connection: a command issued
 * before `auth_ok` must NOT be written to the socket until the auth gate opens. Sending early made
 * HA reply `auth_invalid` ("Auth message incorrectly formatted…"), which the source treated as a
 * terminal bad token and stopped reconnecting. We drive a mocked OkHttp WebSocket through the
 * handshake and assert frame ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HaConnectionTest {

    @Test
    fun `request issued before auth_ok is withheld until the gate opens`() = runTest {
        val ws = mock<WebSocket>()
        whenever(ws.send(any<String>())).thenReturn(true)
        val client = mock<OkHttpClient>()
        val listenerCaptor = argumentCaptor<WebSocketListener>()
        whenever(client.newWebSocket(any<Request>(), listenerCaptor.capture())).thenReturn(ws)

        val conn = HaConnection(client, Json, "ws://ha.local/api/websocket", "tok")

        // connect() suspends until auth_ok.
        val connectJob = launch { conn.connect() }
        runCurrent()
        val listener = listenerCaptor.firstValue

        // A logbook request races in mid-handshake (the real-world trigger: HistoryViewModel).
        val reqJob = launch { runCatching { conn.request("logbook/get_events") {} } }
        runCurrent()

        // Nothing should have been sent yet — not even the auth frame (no auth_required seen).
        verify(ws, never()).send(any<String>())

        // HA asks for auth → the client answers with exactly the auth frame, still no command.
        listener.onMessage(ws, """{"type":"auth_required"}""")
        runCurrent()
        verify(ws, times(1)).send(any<String>())
        val authCaptor = argumentCaptor<String>()
        verify(ws).send(authCaptor.capture())
        assert(authCaptor.firstValue.contains("\"type\":\"auth\"")) {
            "expected the auth frame, got ${authCaptor.firstValue}"
        }

        // auth_ok opens the gate → the withheld logbook command goes out now (and only now).
        listener.onMessage(ws, """{"type":"auth_ok"}""")
        connectJob.join()
        runCurrent()
        val all = argumentCaptor<String>()
        verify(ws, times(2)).send(all.capture())
        val logbook = all.allValues.last()
        assert(logbook.contains("logbook/get_events")) {
            "expected the logbook command after auth_ok, got $logbook"
        }

        reqJob.cancel()
        connectJob.cancel()
    }

    @Test
    fun `subscribe routes its events by id, entity events reach the sink`() = runTest {
        val ws = mock<WebSocket>()
        whenever(ws.send(any<String>())).thenReturn(true)
        val client = mock<OkHttpClient>()
        val listenerCaptor = argumentCaptor<WebSocketListener>()
        whenever(client.newWebSocket(any<Request>(), listenerCaptor.capture())).thenReturn(ws)

        val conn = HaConnection(client, Json, "ws://ha.local/api/websocket", "tok")
        val entityEvents = mutableListOf<JsonObject>()
        val subEvents = mutableListOf<JsonObject>()
        conn.onEntitiesEvent { entityEvents.add(it) }

        val connectJob = launch { conn.connect() }
        runCurrent()
        val listener = listenerCaptor.firstValue
        listener.onMessage(ws, """{"type":"auth_required"}""")
        listener.onMessage(ws, """{"type":"auth_ok"}""")
        connectJob.join()
        runCurrent()

        // Start a subscribe-style command (the WebRTC offer uses this). It gets id 1 and suspends
        // until HA acks with a `result`.
        val subJob = launch { conn.subscribe("camera/webrtc/offer", onEvent = { subEvents.add(it) }) }
        runCurrent()
        listener.onMessage(ws, """{"id":1,"type":"result","success":true,"result":null}""")
        runCurrent()

        // An event for the subscription id goes to its callback, not the entity sink.
        listener.onMessage(ws, """{"id":1,"type":"event","event":{"type":"answer","answer":"sdp"}}""")
        // An event for an unknown id is the entity stream.
        listener.onMessage(ws, """{"id":7,"type":"event","event":{"a":{"s":"on"}}}""")
        runCurrent()

        assert(subEvents.size == 1) { "subscription should get exactly its event, got ${subEvents.size}" }
        assert(entityEvents.size == 1) { "entity sink should get the non-subscription event only" }

        subJob.cancel()
        connectJob.cancel()
    }
}
