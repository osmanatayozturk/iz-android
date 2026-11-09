package org.iz.navigation.integration.osm

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmGpxHistoryClientTest {
    @Test fun readsAllMetadataInTheOfficialSingleResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("""
                {"version":"0.6","generator":"OpenStreetMap server","traces":[
                  {"id":4321,"name":"sample.gpx","uid":41,"user":"Fixture","visibility":"identifiable",
                   "pending":true,"timestamp":"2026-11-08T00:00:00Z","description":"fixture","tags":[]},
                  {"id":4322,"name":"second.gpx","uid":41,"user":"Fixture","visibility":"trackable",
                   "pending":false,"timestamp":"2026-11-08T01:00:00+01:00","lat":41.0,"lon":29.0,
                   "description":"fixture","tags":["test"]}
                ]}
            """.trimIndent()))
            assertEquals(listOf(
                OsmGpxHistoryEntry(4321, "sample.gpx", 1794096000000L, true),
                OsmGpxHistoryEntry(4322, "second.gpx", 1794096000000L, false),
            ), client(server).list("fixture-token", 41))
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/api/0.6/user/gpx_files.json", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rejectsIncompleteMalformedAndOversizedResponsesInsteadOfReturningPartialHistory() {
        MockWebServer().use { server ->
            val badResponses = listOf(
                """{"traces":[{"id":4321,"name":"sample.gpx","uid":41,"timestamp":"2026-11-08T00:00:00Z","pending":false},{}]}""",
                """{"traces":[{"id":"4321","name":"sample.gpx","uid":41,"timestamp":"2026-11-08T00:00:00Z","pending":"false"}]}""",
                """{"traces":[]} trailing""",
                " ".repeat(4 * 1024 * 1024 + 1),
            )
            val client = client(server)
            for (body in badResponses) {
                server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setChunkedBody(body, 4096))
                assertThrows(IOException::class.java) { runBlocking { client.list("fixture-token", 41) } }
            }
            assertEquals(badResponses.size, server.requestCount)
        }
    }

    @Test fun rejectsEntireSnapshotWhenAnyTraceLacksTheExpectedIntegralOwnerId() {
        MockWebServer().use { server ->
            val client = client(server)
            val badOwnerFields = listOf("\"uid\":42,", "", "\"uid\":null,", "\"uid\":\"41\",", "\"uid\":41.0,")
            for (ownerField in badOwnerFields) {
                server.enqueue(jsonResponse("""
                    {"traces":[
                      {"id":4321,"name":"owned.gpx","uid":41,"timestamp":"2026-11-08T00:00:00Z","pending":false},
                      {"id":4322,"name":"other.gpx",$ownerField"timestamp":"2026-11-08T00:00:00Z","pending":false}
                    ]}
                """.trimIndent()))
                assertThrows(IOException::class.java) { runBlocking { client.list("fixture-token", 41) } }
            }
            assertEquals(badOwnerFields.size, server.requestCount)
        }
    }

    @Test fun partialHttpSuccessDoesNotCountAsCompleteHistory() {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("""{"traces":[]}""").setResponseCode(206))
            val error = assertThrows(OsmGpxHistoryHttpException::class.java) {
                runBlocking { client(server).list("fixture-token", 41) }
            }
            assertEquals(206, error.statusCode)
        }
    }

    @Test fun cancellationEndsThePendingReadWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = async(start = CoroutineStart.UNDISPATCHED) { client(server).list("fixture-token", 41) }
            checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(3_000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unauthorizedResponseRetainsItsStatusWithoutExposingTheBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private fixture"))
            val error = assertThrows(OsmGpxHistoryHttpException::class.java) {
                runBlocking { client(server).list("fixture-token", 41) }
            }
            assertEquals(401, error.statusCode)
            assertEquals("OpenStreetMap HTTP 401", error.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rejectsRedirectAndServerRequestedReplayWithoutAnotherExchange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/redirect")))
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
            server.enqueue(jsonResponse("""{"traces":[]}"""))
            val client = client(server)
            for (status in listOf(302, 503)) {
                val error = assertThrows(OsmGpxHistoryHttpException::class.java) {
                    runBlocking { client.list("fixture-token", 41) }
                }
                assertEquals(status, error.statusCode)
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun rejectsNonOfficialRemoteEndpointBeforeAnyRequest() {
        assertThrows(IllegalArgumentException::class.java) {
            OsmGpxHistoryClient("https://fixture.invalid/api/0.6")
        }
    }

    private fun client(server: MockWebServer) = OsmGpxHistoryClient(server.url("/api/0.6").toString())

    private fun jsonResponse(body: String) = MockResponse()
        .addHeader("Content-Type", "application/json").setBody(body)
}
