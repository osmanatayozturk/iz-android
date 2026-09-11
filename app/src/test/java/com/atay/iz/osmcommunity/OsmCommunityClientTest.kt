package com.atay.iz.osmcommunity

import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OsmCommunityClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OsmCommunityClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OsmCommunityClient(baseUrl = server.url("/api/0.6").toString())
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun currentProfileReadsPrivateUnreadCountWithBearerToken() = runBlocking {
        server.enqueue(json("""{"user":{"id":41,"display_name":"Çağrı","description":"Raw **description**","account_created":"2020-01-02T03:04:05Z","img":{"href":"https://www.openstreetmap.org/avatar.png"},"changesets":{"count":12},"messages":{"received":{"count":5,"unread":3},"sent":{"count":2}}}}"""))
        val result = client.profile("fictional-token")
        assertEquals(41L, result.id)
        assertEquals("Çağrı", result.displayName)
        assertEquals("Raw **description**", result.description)
        assertEquals(Instant.parse("2020-01-02T03:04:05Z").toEpochMilli(), result.accountCreatedAt)
        assertEquals(12, result.changesetCount)
        assertEquals(3, result.unreadCount)
        val request = server.takeRequest()
        assertEquals("/api/0.6/user/details.json", request.path)
        assertEquals("Bearer fictional-token", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test fun publicProfileUsesIdAndOmitsCredentialsWhenNoSessionWasGiven() = runBlocking {
        server.enqueue(json("""{"user":{"id":42,"display_name":"Mapper","img":{},"changesets":{"count":0}}}"""))
        val result = client.profile(null, 42)
        assertEquals(0, result.unreadCount)
        assertNull(result.imageUrl)
        val request = server.takeRequest()
        assertEquals("/api/0.6/user/42.json", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun unsafeProfileImageIsDiscardedAndDescriptionRemainsPlainText() = runBlocking {
        server.enqueue(json("""{"user":{"id":42,"display_name":"Mapper","description":"<script>danger</script>","img":{"href":"javascript:alert(1)"}}}"""))
        val result = client.profile(null, 42)
        assertNull(result.imageUrl)
        assertEquals("<script>danger</script>", result.description)
    }

    @Test fun inboxUsesActualWrapperAndInclusiveCursorWithDeletedRowsRetained() = runBlocking {
        val rows = (201L downTo 102L).map { summary(it, deleted = it == 102L) }
        server.enqueue(json("""{"version":"0.6","messages":[${rows.joinToString()}]}"""))
        val result = client.mailbox("token", CommunityMailbox.INBOX, 201)
        assertEquals(100, result.messages.size)
        assertEquals(101L, result.nextFromId)
        assertTrue(result.messages.last().deleted)
        assertFalse(result.messages.first().read)
        assertEquals(42L, result.messages.first().fromId)
        assertEquals("Alice", result.messages.first().fromName)
        assertEquals(41L, result.messages.first().toId)
        assertEquals("Me", result.messages.first().toName)
        assertEquals("/api/0.6/user/messages/inbox.json?order=newest&limit=100&from_id=201", server.takeRequest().path)
    }

    @Test fun cursorUsesMinimumRawIdEvenWhenLastRowsAreDeletedAndOrderVaries() = runBlocking {
        val rows = (102L..201L).map { summary(it, deleted = it < 150) }
        server.enqueue(json("""{"messages":[${rows.joinToString()}]}"""))
        assertEquals(101L, client.mailbox("token", CommunityMailbox.INBOX).nextFromId)
    }

    @Test fun shortMailboxPageEndsPaginationAndOutboxDoesNotInventUnreadStatus() = runBlocking {
        server.enqueue(json("""{"messages":[${sentSummary(17)}]}"""))
        val result = client.mailbox("token", CommunityMailbox.OUTBOX)
        assertNull(result.nextFromId)
        assertTrue(result.messages.single().read)
        assertEquals("/api/0.6/user/messages/outbox.json?order=newest&limit=100", server.takeRequest().path)
    }

    @Test fun allDeletedFullPageStillHasNextCursor() = runBlocking {
        server.enqueue(json("""{"messages":[${(501L downTo 402L).joinToString { summary(it, deleted = true) }}]}"""))
        val result = client.mailbox("token", CommunityMailbox.INBOX)
        assertEquals(401L, result.nextFromId)
        assertEquals(100, result.messages.count { it.deleted })
    }

    @Test fun messageFetchDoesNotSilentlyMarkReadOrRenderMarkdown() = runBlocking {
        server.enqueue(json(detail(17, "**Hello** <a href='intent:bad'>text</a>")))
        val result = client.message("token", 17)
        assertEquals("**Hello** <a href='intent:bad'>text</a>", result.body)
        assertEquals(17L, result.summary.id)
        assertEquals(Instant.parse("2026-09-11T10:00:00Z").toEpochMilli(), result.summary.sentAt)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/0.6/user/messages/17.json", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test fun sendByIdSubmitsOnlyIdTitleAndExactBodyOnce() = runBlocking {
        server.enqueue(json(sentDetail(18, "server body")))
        val result = client.send("token", 42, "stale display name", " Merhaba ", "  **Exact**\nTürkçe  ")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/0.6/user/messages.json", request.path)
        val payload = JSONObject(request.body.readUtf8())
        assertEquals(setOf("recipient_id", "title", "body"), payload.keys().asSequence().toSet())
        assertEquals(42L, payload.getLong("recipient_id"))
        assertEquals(" Merhaba ", payload.getString("title"))
        assertEquals("  **Exact**\nTürkçe  ", payload.getString("body"))
        assertEquals("  **Exact**\nTürkçe  ", result.body)
        assertEquals(1, server.requestCount)
    }

    @Test fun sendByNameUsesRecipientFieldAndAcceptsSummaryOnlySuccess() = runBlocking {
        server.enqueue(json("""{"message":${sentSummary(18).put("to_display_name", "Çağrı User")}}"""))
        val result = client.send("token", null, "Çağrı User", "Subject", "Exact body")
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(payload.has("recipient_id"))
        assertEquals("Çağrı User", payload.getString("recipient"))
        assertEquals("Exact body", result.body)
    }

    @Test fun validUnicodeTitleCountsCodePointsRatherThanUtf16Units() = runBlocking {
        server.enqueue(json(sentDetail(18, "body")))
        client.send("token", 42, "", "😀".repeat(255), "body")
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidSendIsRejectedBeforeAnyNetworkRequest() = runBlocking {
        val cases = listOf(Triple("", "title", "body"), Triple("name", " ", "body"),
            Triple("name", "x".repeat(256), "body"), Triple("name", "title", " "))
        for ((name, title, body) in cases) {
            try { client.send("token", null, name, title, body); fail("Invalid request accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun markReadAndUnreadUseActualReadStatusParameter() = runBlocking {
        for (read in listOf(true, false)) {
            server.enqueue(json(detail(17, "body")))
            client.markRead("token", 17, read)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/0.6/user/messages/17.json", request.path)
            assertEquals(read.toString(), JSONObject(request.body.readUtf8()).getString("read_status"))
        }
    }

    @Test fun deletionUsesOwnMessageEndpointWithAuthenticatedDelete() = runBlocking {
        server.enqueue(json(detail(17, "body")))
        client.delete("token", 17)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("/api/0.6/user/messages/17.json", request.path)
    }

    @Test fun explicitRejectionRetainsStatusAndRetryAfterWithoutPrivateServerText() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120")
            .setBody("Private body and token must not enter exceptions"))
        try { client.send("token", 42, "", "title", "body"); fail("Expected 429") }
        catch (failure: CommunityHttpException) {
            assertEquals(429, failure.statusCode)
            assertEquals(120_000L, failure.retryAfterMillis)
            assertFalse(failure.toString().contains("Private"))
            assertNull(failure.cause)
        }
    }

    @Test fun retryAfterHttpDateIsParsedWithoutNegativeDelay() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT"))
        try { client.mailbox("token", CommunityMailbox.INBOX); fail("Expected failure") }
        catch (failure: CommunityHttpException) { assertEquals(0L, failure.retryAfterMillis) }
    }

    @Test fun retryAfterZeroCannotReplayMessageCreation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(json(detail(99, "body")))
        try { client.send("token", 42, "", "title", "body"); fail("Expected uncertain failure") }
        catch (failure: CommunityHttpException) { assertEquals(503, failure.statusCode) }
        assertEquals(1, server.requestCount)
    }

    @Test fun serverRetryHintLeavesGetRetryTimingToRepository() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(json("""{"messages":[]}"""))
        try { client.mailbox("token", CommunityMailbox.INBOX); fail("Expected HTTP failure") }
        catch (failure: CommunityHttpException) { assertEquals(503, failure.statusCode) }
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectCannotReplayPostOrForwardBearerCredentials() = runBlocking {
        MockWebServer().use { destination ->
            destination.start()
            destination.enqueue(json(detail(99, "body")))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", destination.url("/stolen")))
            try { client.send("token", 42, "", "title", "body"); fail("Expected redirect failure") }
            catch (failure: CommunityHttpException) { assertEquals(307, failure.statusCode) }
            assertEquals(1, server.requestCount)
            assertEquals(0, destination.requestCount)
        }
    }

    @Test fun disconnectedResponseDoesNotRetryOrClaimExplicitRejection() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json(detail(99, "body")))
        try { client.send("token", 42, "", "title", "body"); fail("Expected uncertain failure") }
        catch (failure: IOException) { assertFalse(failure is CommunityHttpException) }
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedSuccessIsSanitizedIoFailureForUnknownSendOutcome() = runBlocking {
        server.enqueue(json("""{"message":{"private-secret":"token"}}"""))
        try { client.send("token", 42, "", "title", "body"); fail("Expected uncertain failure") }
        catch (failure: IOException) {
            assertFalse(failure is CommunityHttpException)
            assertFalse(failure.toString().contains("private-secret"))
            assertFalse(failure.toString().contains("token"))
            assertNull(failure.cause)
        }
    }

    @Test fun wrongRecipientInSuccessfulResponseCannotBeCommittedAsSent() = runBlocking {
        server.enqueue(json(detail(18, "body")))
        try { client.send("token", 42, "", "title", "body"); fail("Expected uncertain response") }
        catch (failure: IOException) { assertFalse(failure is CommunityHttpException) }
        assertEquals(1, server.requestCount)
    }

    @Test fun oversizedResponseIsRejectedBeforeJsonParsing() = runBlocking {
        server.enqueue(json(" ".repeat(4 * 1024 * 1024 + 1)))
        try { client.message("token", 17); fail("Expected bounded response failure") }
        catch (failure: IOException) { assertFalse(failure is CommunityHttpException) }
    }

    @Test fun cancellingCoroutineCancelsSocketWithoutWaitingForHttpTimeout() = runBlocking {
        val socketStopped = CountDownLatch(1)
        val transport = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) { socketStopped.countDown() }
        }).build()
        client = OsmCommunityClient(transport, server.url("/api/0.6").toString())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val operation = async(Dispatchers.IO) { client.message("token", 17) }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        operation.cancel()
        try { operation.await(); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertTrue(operation.isCancelled)
        assertTrue("Cancelling the coroutine must close its outstanding HTTP exchange", socketStopped.await(3, TimeUnit.SECONDS))
    }

    @Test fun untrustedBaseCannotReceiveAnAuthenticationToken() {
        for (base in listOf("https://evil.example/api/0.6", "http://api.openstreetmap.org/api/0.6",
            "https://api.openstreetmap.org.evil.example/api/0.6", "https://user:pass@api.openstreetmap.org/api/0.6")) {
            try { OsmCommunityClient(baseUrl = base); fail("Untrusted endpoint accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)

    private fun summary(id: Long, deleted: Boolean = false, read: Boolean? = false): String =
        """{"id":$id,"from_user_id":42,"from_display_name":"Alice","to_user_id":41,"to_display_name":"Me","title":"Hello","sent_on":"2026-09-11T10:00:00Z",${if (read == null) "" else "\"message_read\":$read,"}"deleted":$deleted,"body_format":"markdown"}"""

    private fun detail(id: Long, body: String): String =
        JSONObject().put("message", JSONObject(summary(id)).put("body", body)).toString()

    private fun sentSummary(id: Long): JSONObject = JSONObject(summary(id, read = null))
        .put("from_user_id", 41).put("from_display_name", "Me").put("to_user_id", 42).put("to_display_name", "Alice")

    private fun sentDetail(id: Long, body: String): String =
        JSONObject().put("message", sentSummary(id).put("body", body)).toString()
}
