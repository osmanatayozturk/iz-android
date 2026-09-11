package org.iz.navigation.integration.osm

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OsmNotesClientTest {
    private val json = """{"type":"Feature","geometry":{"type":"Point","coordinates":[29.02,41.01]},"properties":{"id":99,"status":"open","date_created":"2026-09-09 10:30:00 UTC","comments":[{"uid":41,"action":"opened","text":"User's exact text","date":"2026-09-09 10:30:00 UTC"}]}}"""

    @Test fun publishesOnlyExplicitTextToFixedHostWithAuthenticatedIdentity() = runBlocking {
        var sent: Request? = null
        val client = OsmNotesClient(OkHttpClient.Builder().addInterceptor { chain ->
            sent = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(json.toResponseBody()).build()
        }.build())
        val result = client.createNote("token", 41.01, 29.02, "User's exact text")
        assertEquals(99L, result.id)
        assertEquals(41L, result.comments.first().userId)
        assertEquals("https://api.openstreetmap.org/api/0.6/notes.json", sent!!.url.toString())
        assertEquals("Bearer token", sent!!.header("Authorization"))
        val body = Buffer().also { sent!!.body!!.writeTo(it) }.readUtf8()
        val payload = JSONObject(body)
        assertEquals(setOf("lat", "lon", "text"), payload.keys().asSequence().toSet())
        assertEquals("User's exact text", payload.getString("text"))
    }

    @Test fun postNetworkFailureIsNotRetried() = runBlocking {
        var calls = 0
        val client = OsmNotesClient(OkHttpClient.Builder().addInterceptor {
            calls++
            throw IOException("Connection lost after write")
        }.build())
        try { client.createNote("token", 1.0, 2.0, "Observed issue"); fail("Expected failure") }
        catch (_: IOException) { assertEquals(1, calls) }
        Unit
    }

    @Test fun unknownServerFailureAndExplicitRejectionRemainDistinguishable() = runBlocking {
        for (status in listOf(400, 401, 403, 429, 500, 503)) {
            val client = OsmNotesClient(OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                    .message("Failed").body("Do not persist this response".toResponseBody()).build()
            }.build())
            try { client.createNote("token", 1.0, 2.0, "Observed issue"); fail("Expected HTTP failure") }
            catch (error: OsmApiException) { assertEquals(status, error.statusCode) }
        }
    }

    @Test fun retryAfterZeroCannotReplayANotePost() = runBlocking {
        // Loopback HTTP only: exercise OkHttp's actual follow-up logic without contacting OSM.
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val worker = Executors.newSingleThreadExecutor()
        val received = AtomicInteger()
        worker.submit {
            try {
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { reader.read() }
                        val first = received.incrementAndGet() == 1
                        val body = if (first) "" else json
                        val status = if (first) "503 Unavailable" else "200 OK"
                        val response = "HTTP/1.1 $status\r\nRetry-After: 0\r\nConnection: close\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
                        socket.getOutputStream().write(response.toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
            } catch (_: IOException) { /* The test closes the listener after the first response. */ }
        }
        try {
            val client = OsmNotesClient(OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.localPort}/notes").build())
            }.build())
            try { client.createNote("fictional-test-token", 1.0, 2.0, "Test observation") }
            catch (_: IOException) { /* A 503 or the replay guard must remain a failed single attempt. */ }
            assertEquals("A server retry hint must not duplicate a public note", 1, received.get())
        } finally {
            server.close()
            worker.shutdownNow()
        }
    }
}
