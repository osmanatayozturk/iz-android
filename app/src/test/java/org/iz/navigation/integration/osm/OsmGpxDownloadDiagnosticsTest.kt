package org.iz.navigation.integration.osm

import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.iz.navigation.integration.OsmDirectedPoint
import org.junit.Assert.*
import org.junit.Test

class OsmGpxDownloadDiagnosticsTest {
    @Test fun completeListThenDownloadPreservesAuthIdentityAndBytesAcrossBodyFraming() = runBlocking {
        val bytes = GPX.toByteArray(Charsets.UTF_8)
        for (framing in listOf("normal", "chunked", "gzip")) MockWebServer().use { server ->
            server.enqueue(history())
            val response = MockResponse().addHeader("Content-Type", "application/gpx+xml")
            server.enqueue(when (framing) {
                "chunked" -> response.setChunkedBody(GPX, 17)
                "gzip" -> response.addHeader("Content-Encoding", "gzip").setBody(Buffer().write(gzip(bytes)))
                else -> response.setBody(GPX)
            })
            val client = client(server)
            val listed = client.list(TOKEN, 41)
            assertEquals(listOf(OsmGpxHistoryEntry(7301, "synthetic.gpx", 1794096000000L, false, "trackable")), listed)
            val actual = client.data(TOKEN, listed.single().id)
            assertEquals(listOf(listOf(OsmDirectedPoint(1.0, 2.0), OsmDirectedPoint(1.001, 2.001)),
                listOf(OsmDirectedPoint(3.0, 4.0), OsmDirectedPoint(3.001, 4.001))), actual.segments)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, actual.sha256)
            val index = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("GET", index.method)
            assertEquals("/api/0.6/user/gpx_files.json", index.path)
            assertEquals("Bearer $TOKEN", index.getHeader("Authorization"))
            assertEquals("application/json", index.getHeader("Accept"))
            val data = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("GET", data.method)
            assertEquals("/api/0.6/gpx/7301/data.gpx", data.path)
            assertEquals("Bearer $TOKEN", data.getHeader("Authorization"))
            assertEquals("application/gpx+xml", data.getHeader("Accept"))
            assertEquals("identity", data.getHeader("Accept-Encoding"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun fixedLengthDisconnectAfterHeadersHasOnlyBodyDiagnostic() {
        assertBodyDisconnect(chunked = false)
    }

    @Test fun chunkedDisconnectAfterHeadersHasOnlyBodyDiagnostic() {
        assertBodyDisconnect(chunked = true)
    }

    private fun assertBodyDisconnect(chunked: Boolean) {
        MockWebServer().use { server ->
            // A valid complete GPX would parse; disconnect halfway through its long metadata instead.
            val body = GPX.replace("<trk>", "<metadata><desc>" + "synthetic padding ".repeat(2048) + "</desc></metadata><trk>")
            val response = MockResponse().addHeader("Content-Type", "application/gpx+xml")
            if (chunked) response.setChunkedBody(body, 257) else response.setBody(body)
            server.enqueue(response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val error = assertThrows(OsmGpxTransferException::class.java) { runBlocking { client(server).data(TOKEN, 7301) } }
            val allowed = mapOf(OsmGpxTransferReason.BODY_INCOMPLETE to "GPX-BODY-INCOMPLETE",
                OsmGpxTransferReason.BODY_SOCKET to "GPX-BODY-SOCKET", OsmGpxTransferReason.BODY_IO to "GPX-BODY-IO")
            assertTrue("A response-body callback failure must retain its body stage", error.reason in allowed)
            assertEquals(allowed.getValue(error.reason), error.reason.code)
            assertEquals(error.reason.code, error.message)
            assertEquals("GET", checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).method)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun disconnectBeforeHeadersHasOnlyHeadDiagnostic() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val error = assertThrows(OsmGpxTransferException::class.java) { runBlocking { client(server).data(TOKEN, 7301) } }
            val allowed = mapOf(OsmGpxTransferReason.HEAD_IO to "GPX-HEAD-IO", OsmGpxTransferReason.HEAD_SOCKET to "GPX-HEAD-SOCKET")
            assertTrue("No response headers arrived", error.reason in allowed)
            assertEquals(allowed.getValue(error.reason), error.reason.code)
            assertEquals(error.reason.code, error.message)
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("/api/0.6/gpx/7301/data.gpx", request.path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectToAnotherLoopbackCannotReceiveAnAuthenticatedRequest() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { destination ->
            origin.enqueue(history())
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/must-not-receive-token")))
            destination.enqueue(MockResponse().setBody(GPX))
            val client = client(origin)
            val id = client.list(TOKEN, 41).single().id
            val error = assertThrows(OsmGpxHistoryHttpException::class.java) { runBlocking { client.data(TOKEN, id) } }
            assertEquals(302, error.statusCode)
            repeat(2) { assertEquals("Bearer $TOKEN", checkNotNull(origin.takeRequest(3, TimeUnit.SECONDS)).getHeader("Authorization")) }
            assertEquals(2, origin.requestCount)
            assertNull(destination.takeRequest(200, TimeUnit.MILLISECONDS))
            assertEquals(0, destination.requestCount)
        } }
    }

    private fun client(server: MockWebServer) = OsmGpxHistoryClient(server.url("/api/0.6").toString())
    private fun history() = MockResponse().addHeader("Content-Type", "application/json").setBody("""
        {"traces":[{"id":7301,"uid":41,"name":"synthetic.gpx","timestamp":"2026-11-08T00:00:00Z",
        "pending":false,"visibility":"trackable","description":"synthetic fixture","tags":[]}]}
    """.trimIndent())

    private companion object {
        const val TOKEN = "synthetic-fixture-token"
        val GPX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="synthetic fixture" xmlns="http://www.topografix.com/GPX/1/1"><trk>
            <trkseg><trkpt lat="1" lon="2"/><trkpt lat="1.001" lon="2.001"/></trkseg>
            <trkseg><trkpt lat="3" lon="4"/><trkpt lat="3.001" lon="4.001"/></trkseg>
            </trk></gpx>
        """.trimIndent()
    }
}
