package org.iz.navigation.offline

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class OfflineWireBudgetTest {
    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
    private fun delegate(payload: ByteArray): Call.Factory = Call.Factory { request ->
        object : Call {
            private var used = false; private var cancelled = false
            override fun request() = request
            override fun isExecuted() = used
            override fun isCanceled() = cancelled
            override fun timeout() = Timeout.NONE
            override fun cancel() { cancelled = true }
            override fun clone(): Call = delegate(payload).newCall(request)
            override fun execute(): Response {
                used = true
                val guard = requireNotNull(request.tag(OfflineWireBudget::class.java))
                val raw = guard.wrap(gzip(payload).toResponseBody(null))
                val decoded = GZIPInputStream(raw.byteStream()).use { it.readBytes() }
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(decoded.toResponseBody(null)).build()
            }
            override fun enqueue(responseCallback: Callback) {
                try { responseCallback.onResponse(this, execute()) }
                catch (error: IOException) { responseCallback.onFailure(this, error) }
            }
        }
    }
    @Test fun compressedAndDecodedBytesShareOneConservativeMaximumNotAnUnboundedWirePath() {
        val ledger = OfflineHttpLedger(limitBytes = 64)
        val calls = OfflineDownloadCalls(delegate(ByteArray(64) { 'x'.code.toByte() }), ledger, "Iz/test")
        calls.newCall(Request.Builder().url(OPEN_FREE_MAP_STYLE).build()).execute().use {
            assertEquals(64, it.body!!.bytes().size)
        }
        assertEquals(64L, ledger.downloadedBytes)
    }
    @Test fun rawCompressedBodyOverBudgetIsRejectedEvenWhenDecodedContentWouldFit() {
        val ledger = OfflineHttpLedger(limitBytes = 8)
        val calls = OfflineDownloadCalls(delegate(byteArrayOf(1)), ledger, "Iz/test")
        assertThrows(IOException::class.java) {
            calls.newCall(Request.Builder().url(OPEN_FREE_MAP_STYLE).build()).execute().use { it.body!!.bytes() }
        }
        assertEquals(0L, ledger.downloadedBytes)
    }
}
