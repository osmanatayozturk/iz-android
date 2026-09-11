package org.iz.navigation.integration

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class OsmReadHttpClientTest {
    @Test fun nominatimGetDoesNotReplayAfterServiceUnavailableWithImmediateRetryHint() {
        assertSingleRequest("GET")
    }

    @Test fun overpassPostDoesNotReplayAfterServiceUnavailableWithImmediateRetryHint() {
        assertSingleRequest("POST")
    }

    private fun assertSingleRequest(method: String) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("unexpected replay"))
            val request = Request.Builder().url(server.url(if (method == "GET") "/search?q=cafe" else "/api/interpreter"))
                .apply { if (method == "POST") post(FormBody.Builder().add("data", "[out:json];node(1);out;").build()) }
                .build()
            // The exact shared client used by search() and nearby(), including OkHttp's network follow-up machinery.
            // Reflection avoids adding a test-only accessor or duplicating the production builder.
            val field = OsmPlaces::class.java.getDeclaredField("client").apply { isAccessible = true }
            val client = field.get(null) as OkHttpClient
            client.newCall(request).execute().use { response ->
                assertEquals("One user action must consume exactly one network request", 1, server.requestCount)
                assertEquals("The original service failure must reach the caller", 503, response.code)
                assertEquals("busy", response.body!!.string())
            }
            assertEquals(method, server.takeRequest().method)
        }
    }
}
