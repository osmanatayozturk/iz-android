package org.iz.navigation.integration.osm

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OsmGpxHistoryMutationTest {
    @Test fun rowAndHttpByteLimitsRemainTypedWhileHttpStatusStaysSeparate() {
        MockWebServer().use { server ->
            server.enqueue(json("{\"traces\":[" + List(10_001) { "{}" }.joinToString(",") + "]}"))
            assertThrows(OsmGpxDataLimitException::class.java) { runBlocking { client(server).list("fixture-token", 41) } }
            server.enqueue(json(" ".repeat(4 * 1024 * 1024 + 1)).setChunkedBody(" ".repeat(4 * 1024 * 1024 + 1), 4096))
            assertThrows(OsmGpxDataLimitException::class.java) { runBlocking { client(server).list("fixture-token", 41) } }
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", OsmGpxRemoteXml.MAX_BYTES + 1))
            assertThrows(OsmGpxDataLimitException::class.java) { runBlocking { client(server).data("fixture-token", 4321) } }
            server.enqueue(MockResponse().setResponseCode(429))
            assertEquals(429, assertThrows(OsmGpxHistoryHttpException::class.java) {
                runBlocking { client(server).data("fixture-token", 4321) }
            }.statusCode)
        }
    }

    @Test fun partialDataAndUnknownCompressionNeverBecomeCoverage() {
        val body = remoteGpx()
        val replies = listOf(MockResponse().setResponseCode(206).setBody(body),
            MockResponse().addHeader("Content-Encoding", "br").setBody(body),
            MockResponse().addHeader("Content-Encoding", "gzip").setBody(body),
            MockResponse().addHeader("Content-Type", "application/x-tar").setBody(body))
        MockWebServer().use { server ->
            replies.forEach { response ->
                server.enqueue(response)
                assertThrows(IOException::class.java) { runBlocking { client(server).data("fixture-token", 4321) } }
            }
            assertEquals(replies.size, server.requestCount)
        }
    }

    @Test fun readsForcedGpxFormatWithAuthenticationAndTransportGzip() = runBlocking {
        MockWebServer().use { server ->
            val bytes = remoteGpx().toByteArray()
            server.enqueue(MockResponse().addHeader("Content-Type", "application/gpx+xml")
                .addHeader("Content-Encoding", "gzip").setBody(Buffer().write(gzip(bytes))))
            val actual = client(server).data("fixture-token", 4321)
            assertEquals(OsmGpxRemoteXml.parse(bytes, null), actual)
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/api/0.6/gpx/4321/data.gpx", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
            assertEquals("application/gpx+xml", request.getHeader("Accept"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
        }
    }

    @Test fun visibilityPutPreservesDescriptionAndTagsThenVerifiesTheActualOwner() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(metadata())
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(metadata(visibility = "trackable"))
            assertTrue(client(server).changeToTrackable("fixture-token", 41, 4321) { true })
            val read = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("/api/0.6/gpx/4321.json", read.path)
            val write = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("PUT", write.method)
            assertEquals("/api/0.6/gpx/4321", write.path)
            assertEquals("Bearer fixture-token", write.getHeader("Authorization"))
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(write.body.inputStream())
            val trace = document.getElementsByTagName("gpx_file").item(0) as org.w3c.dom.Element
            assertEquals("4321", trace.getAttribute("id"))
            assertEquals("trackable", trace.getAttribute("visibility"))
            assertEquals("unchanged < & > \r\nİz", trace.getElementsByTagName("description").item(0).textContent)
            val tags = trace.getElementsByTagName("tag")
            assertEquals(listOf("a&b", "<tag>", "a&b"), (0 until tags.length).map { tags.item(it).textContent })
            assertEquals("GET", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).method)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun alreadyTrackableAndStaleAuthorizationNeverPut() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(metadata(visibility = "trackable"))
            val client = client(server)
            assertTrue(client.changeToTrackable("fixture-token", 41, 4321) { true })
            assertFalse(client.changeToTrackable("fixture-token", 41, 4321) { false })
            server.enqueue(metadata())
            val checks = AtomicInteger()
            assertFalse(client.changeToTrackable("fixture-token", 41, 4321) { checks.incrementAndGet() <= 2 })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun rechecksTheOwnerAtThePutExchangeAndAfterBothReplies() = runBlocking {
        for (allowedChecks in listOf(3, 4, 5, 6)) MockWebServer().use { server ->
            server.enqueue(metadata())
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(metadata(visibility = "trackable"))
            val checks = AtomicInteger()
            assertFalse(client(server).changeToTrackable("fixture-token", 41, 4321) {
                checks.incrementAndGet() <= allowedChecks
            })
            assertEquals(when (allowedChecks) { 3 -> 1; 6 -> 3; else -> 2 }, server.requestCount)
        }
    }

    @Test fun incompleteOrForeignMetadataNeverDispatchesAWrite() {
        val changes = listOf<(JSONObject) -> Unit>({ it.remove("description") }, { it.remove("tags") },
            { it.put("uid", 42) }, { it.put("id", 999) }, { it.put("visibility", "unknown") })
        MockWebServer().use { server ->
            changes.forEach { alter ->
                val row = row().also(alter)
                server.enqueue(json(JSONObject().put("trace", row).toString()))
                assertThrows(IOException::class.java) { runBlocking { client(server).changeToTrackable("fixture-token", 41, 4321) { true } } }
            }
            assertEquals(changes.size, server.requestCount)
        }
    }

    @Test fun lostPutReply503AndRedirectAreUncertainAndNeverReplay() = runBlocking {
        val responses = listOf(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            MockResponse().setResponseCode(503).addHeader("Retry-After", "0"),
            MockResponse().setResponseCode(302).addHeader("Location", "/redirect"))
        responses.forEach { uncertain -> MockWebServer().use { server ->
            server.enqueue(metadata())
            server.enqueue(uncertain)
            server.enqueue(metadata(visibility = "trackable"))
            assertFalse(client(server).changeToTrackable("fixture-token", 41, 4321) { true })
            assertEquals(2, server.requestCount)
        } }
    }

    @Test fun aSuccessfulPutWithoutMatchingReadbackRemainsUncertain() = runBlocking {
        for (readback in listOf(metadata(), metadata(uid = 42, visibility = "trackable"),
            metadata(visibility = "trackable", description = "changed"), MockResponse().setResponseCode(500))) {
            MockWebServer().use { server ->
                server.enqueue(metadata())
                server.enqueue(MockResponse().setResponseCode(200))
                server.enqueue(readback)
                assertFalse(client(server).changeToTrackable("fixture-token", 41, 4321) { true })
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun cancellingAfterPutDispatchCancelsTheCallWithoutReplay() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(metadata())
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                client(server).changeToTrackable("fixture-token", 41, 4321) { true }
            }
            repeat(2) { checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(3_000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals(2, server.requestCount)
        }
    }

    private fun client(server: MockWebServer) = OsmGpxHistoryClient(server.url("/api/0.6").toString())
    private fun row(uid: Long = 41, visibility: String = "identifiable", description: String = "unchanged < & > \r\nİz") =
        JSONObject().put("id", 4321).put("uid", uid).put("name", "sample.gpx")
            .put("timestamp", "2026-11-08T00:00:00Z").put("pending", false).put("visibility", visibility)
            .put("description", description).put("tags", JSONArray(listOf("a&b", "<tag>", "a&b")))
    private fun metadata(uid: Long = 41, visibility: String = "identifiable", description: String = "unchanged < & > \r\nİz") =
        json(JSONObject().put("trace", row(uid, visibility, description)).toString())
    private fun json(body: String) = MockResponse().addHeader("Content-Type", "application/json").setBody(body)
}
