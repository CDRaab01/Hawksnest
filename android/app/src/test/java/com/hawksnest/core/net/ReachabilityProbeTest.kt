package com.hawksnest.core.net

import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins the reachability classification the Settings "Test" button and the offline hint share:
 * ANY HTTP response (even 401/404) = reachable; a transport failure or malformed URL = not.
 */
class ReachabilityProbeTest {

    /** A Call.Factory whose calls either return [code] or throw [failure] on execute. */
    private class FakeCallFactory(
        private val code: Int = 200,
        private val failure: IOException? = null,
    ) : Call.Factory {
        var lastUrl: String? = null

        override fun newCall(request: Request): Call {
            lastUrl = request.url.toString()
            return object : Call {
                override fun execute(): Response {
                    failure?.let { throw it }
                    // A real execute() always yields a body; Response.close() throws on a
                    // body-less response, so the fake must carry one for the impl's `.use`.
                    return Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("mock")
                        .body("".toResponseBody(null))
                        .build()
                }
                override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()
                override fun cancel() {}
                override fun isCanceled(): Boolean = false
                override fun isExecuted(): Boolean = false
                override fun request(): Request = request
                override fun timeout(): Timeout = Timeout.NONE
                override fun clone(): Call = this
            }
        }
    }

    @Test
    fun `any HTTP response means reachable — even an auth challenge`() = runTest {
        assertTrue(ReachabilityProbe(FakeCallFactory(code = 401)).isReachable("http://ha.local:8123"))
        assertTrue(ReachabilityProbe(FakeCallFactory(code = 404)).isReachable("http://ha.local:8123"))
        assertTrue(ReachabilityProbe(FakeCallFactory(code = 502)).isReachable("http://ha.local:8123"))
    }

    @Test
    fun `a transport failure means unreachable`() = runTest {
        val probe = ReachabilityProbe(FakeCallFactory(failure = IOException("no route to host")))
        assertFalse(probe.isReachable("http://100.64.0.9:8123"))
    }

    @Test
    fun `a malformed URL is unreachable, not a crash`() = runTest {
        assertFalse(ReachabilityProbe(FakeCallFactory()).isReachable("not a url"))
        assertFalse(ReachabilityProbe(FakeCallFactory()).isReachable(""))
    }

    @Test
    fun `probes the base URL root with trailing slashes normalized`() = runTest {
        val factory = FakeCallFactory(code = 200)
        ReachabilityProbe(factory).isReachable("http://ha.local:8123///")
        assertEquals("http://ha.local:8123/", factory.lastUrl)
    }
}
