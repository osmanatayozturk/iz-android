package org.iz.navigation.integration.osm

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.OsmGpxPreparation
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.integration.prepareOsmGpxTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmGpxClientTest {
    @Test fun sendsExactPreparedXmlWithFixedVisibilityAndGenericMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("4321\n"))
            val trace = osmGpxTestTrace("private-fixture-journey")
            assertEquals(OsmGpxUploadResult.Received(4321), client(server).upload("fixture-token", trace) { true })
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/0.6/gpx", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
            val contentType = request.getHeader("Content-Type").orEmpty()
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"file\"; filename=\"" + trace.fileName + "\""))
            assertTrue(body.contains("Content-Type: application/gpx+xml"))
            val parts = body.split("--" + contentType.substringAfter("boundary="))
                .filter { it.startsWith("\r\nContent-Disposition:") }
                .map { part ->
                    val headers = part.substringBefore("\r\n\r\n")
                    val name = checkNotNull(Regex("name=\"([^\"]+)\"").find(headers)).groupValues[1]
                    name to part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
                }
            assertEquals(listOf("file" to trace.gpxXml, "description" to "Iz GPS trace",
                "tags" to "", "visibility" to "identifiable"), parts)
            assertFalse(body.contains(trace.journeyId))
            assertFalse(body.contains("Private fixture title"))
            assertFalse(body.contains("Private fixture note"))
        }
    }

    @Test fun retryAfterZeroCannotReplayTheMultipartPost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("4321"))
            assertEquals(OsmGpxUploadResult.Unknown, client(server).upload("fixture-token", osmGpxTestTrace()) { true })
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun lostResponseAndMalformedSuccessAreUnknown() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("not a trace ID"))
            val client = client(server)
            val trace = osmGpxTestTrace()
            assertEquals(OsmGpxUploadResult.Unknown, client.upload("fixture-token", trace) { true })
            assertEquals(OsmGpxUploadResult.Unknown, client.upload("fixture-token", trace) { true })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun rateLimitReturnsDelayAndStopsAtOneRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "12"))
            val result = client(server).upload("fixture-token", osmGpxTestTrace()) { true }
            assertEquals(OsmGpxUploadResult.Rejected(429, 12_000), result)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rechecksDispatchEligibilityBeforeWritingAnyRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("4321"))
            val checks = java.util.concurrent.atomic.AtomicInteger()
            val result = client(server).upload("fixture-token", osmGpxTestTrace()) { checks.incrementAndGet() == 1 }
            assertEquals(OsmGpxUploadResult.NotDispatched, result)
            assertEquals(2, checks.get())
            assertEquals(0, server.requestCount)
        }
    }

    private fun client(server: MockWebServer) = OsmGpxClient(server.url("/api/0.6").toString())
}

internal fun osmGpxTestTrace(
    journeyId: String = "fixture-journey",
    startedAt: Long = 1_000_000,
): PreparedOsmGpxTrace {
    val journey = Journey(id = journeyId, title = "Private fixture title", note = "Private fixture note",
        startedAt = startedAt, endedAt = startedAt + 300_000)
    val points = (0..10).map { index ->
        TrackPoint(journeyId = journeyId, latitude = 41.0, longitude = 29.0 + index * 0.005,
            recordedAt = journey.startedAt + index * 30_000L, accuracy = 5f)
    }
    return (prepareOsmGpxTrace(journey, points) as OsmGpxPreparation.Ready).trace
}
