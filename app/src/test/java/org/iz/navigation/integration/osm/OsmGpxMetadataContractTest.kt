package org.iz.navigation.integration.osm

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OsmGpxMetadataContractTest {
    @Test fun dataHeaderFormatErrorsStaySeparateFromTransportAndHttpFailures() {
        MockWebServer().use { server ->
            for (encoding in listOf("br", "gzip")) {
                server.enqueue(MockResponse().addHeader("Content-Encoding", encoding).setBody("<gpx/>"))
                assertThrows(OsmGpxGeometryFormatException::class.java) { runBlocking { client(server).data("fixture-token", 4321) } }
            }
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(503, assertThrows(OsmGpxHistoryHttpException::class.java) {
                runBlocking { client(server).data("fixture-token", 4321) }
            }.statusCode)
            server.enqueue(MockResponse().setBody("x".repeat(8192)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val transport = assertThrows(IOException::class.java) { runBlocking { client(server).data("fixture-token", 4321) } }
            assertFalse(transport is OsmGpxGeometryFormatException)
            assertEquals(4, server.requestCount)
        }
    }

    // A consumer projection contract, not a captured OSM payload: upstream currently emits description/tags.
    @Test fun historyProjectionReadsOnlyItsRequiredFields() = runBlocking {
        MockWebServer().use { server ->
            val variations = listOf(summary(), summary().put("description", 42).put("tags", JSONObject.NULL),
                summary().put("description", "not needed for the list").put("tags", JSONArray()))
            for (row in variations) {
                server.enqueue(json(JSONObject().put("traces", JSONArray().put(row))))
                assertEquals(listOf(OsmGpxHistoryEntry(4321, "fixture.gpx", 1794096000000L, false, "identifiable")),
                    client(server).list("fixture-token", 41))
            }
            assertEquals(variations.size, server.requestCount)
        }
    }

    @Test fun historyProjectionStillRequiresOwnedIntegralIdentityReadinessAndKnownVisibility() {
        val changes = listOf<(JSONObject) -> Unit>({ it.remove("uid") }, { it.put("uid", 42) }, { it.put("uid", "41") },
            { it.remove("id") }, { it.put("id", 0) }, { it.put("id", "4321") }, { it.remove("name") },
            { it.remove("timestamp") }, { it.put("timestamp", "bad-time") }, { it.remove("pending") },
            { it.put("pending", "false") }, { it.remove("visibility") }, { it.put("visibility", "unknown") })
        MockWebServer().use { server ->
            for (change in changes) {
                server.enqueue(json(JSONObject().put("traces", JSONArray().put(summary().also(change)))))
                assertThrows(IOException::class.java) { runBlocking { client(server).list("fixture-token", 41) } }
            }
            assertEquals(changes.size, server.requestCount)
        }
    }

    @Test fun detailRequiresFullTypedMetadataBeforeAnyJournalOrPut() {
        val changes = listOf<(JSONObject) -> Unit>({ it.remove("description") }, { it.put("description", 42) },
            { it.remove("tags") }, { it.put("tags", JSONObject.NULL) }, { it.put("tags", "tag") },
            { it.put("tags", JSONArray().put(42)) }, { it.remove("pending") }, { it.put("pending", "false") })
        MockWebServer().use { server ->
            var hooks = 0
            for (change in changes) {
                server.enqueue(json(JSONObject().put("trace", detail().also(change))))
                assertThrows(IOException::class.java) { runBlocking {
                    client(server).changeToTrackable("fixture-token", 41, 4321, canDispatch = { true }, beforePut = { hooks++; true })
                } }
                assertEquals("GET", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).method)
            }
            assertEquals(0, hooks)
            assertEquals(changes.size, server.requestCount)
        }
    }

    @Test fun explicitEmptyTagsAndFalsePendingAreValidMetadataRatherThanMissingDefaults() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(JSONObject().put("trace", detail())))
            val actual = client(server).readMetadata("fixture-token", 41, 4321, canDispatch = { true })
            assertEquals(emptyList<String>(), actual.tags)
            assertFalse(actual.pending)
            assertEquals("keep < & >", actual.description)
            assertTrue(actual.trackableXml().contains("<description>keep &lt; &amp; &gt;</description>"))
            assertFalse(actual.trackableXml().contains("<tag>"))
            assertEquals(1, server.requestCount)
        }
    }

    private fun summary() = JSONObject().put("id", 4321).put("uid", 41).put("name", "fixture.gpx")
        .put("timestamp", "2026-11-08T00:00:00Z").put("pending", false).put("visibility", "identifiable")
    private fun detail() = summary().put("description", "keep < & >").put("tags", JSONArray())
    private fun json(value: JSONObject) = MockResponse().addHeader("Content-Type", "application/json").setBody(value.toString())
    private fun client(server: MockWebServer) = OsmGpxHistoryClient(server.url("/api/0.6").toString())
}
