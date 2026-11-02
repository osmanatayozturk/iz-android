package org.iz.navigation.offline

import java.io.IOException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class OfflineDownloadCallsTest {
    private class FakeFactory : Call.Factory {
        val requests = mutableListOf<Request>()
        val pending = mutableListOf<Pair<Call, Callback>>()
        var executions = 0
        var payload = ByteArray(4) { 1 }
        var knownLength = true
        override fun newCall(request: Request): Call {
            requests += request
            return object : Call {
                private var cancelled = false
                private var executed = false
                override fun request() = request
                override fun execute(): Response { executed = true; executions++; return response(request) }
                override fun enqueue(callback: Callback) { executed = true; executions++; pending += this to callback }
                override fun cancel() { cancelled = true }
                override fun isCanceled() = cancelled
                override fun isExecuted() = executed
                override fun timeout() = Timeout.NONE
                override fun clone() = newCall(request)
            }
        }
        private fun response(request: Request) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("fixture").body(object : ResponseBody() {
                private val bytes = Buffer().write(payload)
                override fun contentType() = "application/x-protobuf".toMediaType()
                override fun contentLength() = if (knownLength) payload.size.toLong() else -1L
                override fun source(): BufferedSource = bytes
            }).build()
        fun completeNext() {
            val (call, callback) = pending.removeAt(0)
            callback.onResponse(call, response(call.request()))
        }
    }
    private fun request(url: String = "https://tiles.openfreemap.org/planet/fixture/8/148/96.pbf") =
        Request.Builder().url(url).build()
    private fun callback() = object : Callback {
        override fun onFailure(call: Call, e: IOException) {}
        override fun onResponse(call: Call, response: Response) { response.use { it.body!!.bytes() } }
    }

    @Test fun permittedResourceReceivesIdentifiableUserAgent() {
        val fake = FakeFactory()
        val calls = OfflineDownloadCalls(fake, OfflineHttpLedger(), "Iz/0.12.0 (org.iz.navigation)")
        calls.newCall(request()).execute().use { assertEquals(4, it.body!!.bytes().size) }
        assertEquals("Iz/0.12.0 (org.iz.navigation)", fake.requests.single().header("User-Agent"))
    }

    @Test fun forbiddenOriginsAndCredentialsDispatchZeroCalls() {
        val fake = FakeFactory(); val calls = OfflineDownloadCalls(fake, OfflineHttpLedger(), "Iz/test")
        for (url in listOf("https://tile.openstreetmap.org/8/148/96.png",
            "https://tiles.openfreemap.org.evil.example/planet/8/1/1.pbf",
            "http://tiles.openfreemap.org/planet/8/1/1.pbf",
            "https://user:pass@tiles.openfreemap.org/planet/8/1/1.pbf",
            "https://tiles.openfreemap.org/unknown/path")) {
            assertThrows(IOException::class.java) { calls.newCall(request(url)).execute().close() }
        }
        assertEquals(0, fake.executions)
    }

    @Test fun globalFourRequestLimitIncludesDifferentCallFactories() {
        val fake = FakeFactory(); val ledger = OfflineHttpLedger()
        val first = OfflineDownloadCalls(fake, ledger, "Iz/test")
        val second = OfflineDownloadCalls(fake, ledger, "Iz/test")
        repeat(5) { (if (it % 2 == 0) first else second).newCall(request()).enqueue(callback()) }
        assertEquals("Fifth call must wait before delegate dispatch", 4, fake.pending.size)
        fake.completeNext()
        assertEquals(5, fake.executions)
        assertEquals(4, fake.pending.size)
        while (fake.pending.isNotEmpty()) fake.completeNext()
        assertEquals(20L, ledger.downloadedBytes)
    }

    @Test fun unknownLengthStreamingCannotExceedPersistentBudget() {
        val fake = FakeFactory().also { it.payload = ByteArray(9); it.knownLength = false }
        var saved = 0L
        val ledger = OfflineHttpLedger(limitBytes = 8, persist = { saved = it; true })
        val calls = OfflineDownloadCalls(fake, ledger, "Iz/test")
        assertThrows(IOException::class.java) { calls.newCall(request()).execute().use { it.body!!.bytes() } }
        assertTrue(saved <= 8)
        assertTrue(ledger.downloadedBytes <= 8)
    }

    @Test fun resumingUsesSameCumulativeCounterAndRejectsKnownOversizeBeforeBody() {
        val fake = FakeFactory()
        val ledger = OfflineHttpLedger(initialBytes = 7, limitBytes = 8)
        val calls = OfflineDownloadCalls(fake, ledger, "Iz/test")
        assertThrows(IOException::class.java) { calls.newCall(request()).execute().use { it.body!!.bytes() } }
        assertEquals(7L, ledger.downloadedBytes)
    }

    @Test fun journalFailureDoesNotDeliverUncommittedBody() {
        val fake = FakeFactory()
        val calls = OfflineDownloadCalls(fake, OfflineHttpLedger(persist = { false }), "Iz/test")
        assertThrows(IOException::class.java) { calls.newCall(request()).execute().use { it.body!!.bytes() } }
    }

    @Test fun cancellationRetiresQueuedRequestsAndClosesDispatch() {
        val fake = FakeFactory(); val ledger = OfflineHttpLedger()
        val calls = OfflineDownloadCalls(fake, ledger, "Iz/test")
        repeat(6) { calls.newCall(request()).enqueue(callback()) }
        ledger.cancel()
        while (fake.pending.isNotEmpty()) fake.completeNext()
        assertEquals(4, fake.executions)
        assertThrows(IOException::class.java) { calls.newCall(request()).execute().close() }
    }
}
