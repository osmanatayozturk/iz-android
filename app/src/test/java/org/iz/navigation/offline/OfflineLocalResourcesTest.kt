package org.iz.navigation.offline

import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test

class OfflineLocalResourcesTest {
    private val id = "a".repeat(64)
    private val style = """{"version":8,"name":"pinned"}""".toByteArray()
    private fun entry(bytes: ByteArray = style, role: OfflineLocalRole = OfflineLocalRole.STYLE) =
        OfflineLocalResource(role, bytes, OfflineStyleAudit.sha256(bytes))
    private fun request(path: String = "style.json") =
        Request.Builder().url(OfflineLocalResources.ORIGIN + "/" + id + "/" + path).build()

    @Test fun actualCallReturnsExactPinnedStyleAndGlyphBytesWithoutAnyDelegate() {
        val local = OfflineLocalResources()
        val glyph = byteArrayOf(10, 0)
        local.install(id, 1, mapOf("style.json" to entry(), "glyphs/IzOpenFont/0-255.pbf" to
            entry(glyph, OfflineLocalRole.GLYPH))) { true }.use {
            local.newCall(request()).execute().use { response ->
                assertEquals(200, response.code)
                assertArrayEquals(style, response.body!!.bytes())
                assertTrue(response.header("Cache-Control")!!.contains("immutable"))
            }
            local.newCall(request("glyphs/IzOpenFont/0-255.pbf")).execute().use { response ->
                assertArrayEquals(glyph, response.body!!.bytes())
            }
        }
    }

    @Test fun callsAndClonesCaptureOwnershipAndCannotBindToANewerGeneration() {
        val local = OfflineLocalResources()
        val oldLease = local.install(id, 1, mapOf("style.json" to entry())) { true }
        val old = local.newCall(request()); val clone = old.clone()
        oldLease.close()
        val fresh = local.install(id, 2, mapOf("style.json" to entry())) { true }
        oldLease.close()
        for (call in listOf(old, clone))
            assertThrows(IOException::class.java) { call.execute().close() }
        local.newCall(request()).execute().close()
        fresh.close()
        assertThrows(IOException::class.java) { local.newCall(request()).execute() }
    }

    @Test fun cancelledEnqueueHasOneTerminalFailureAndNeverDeliversAResponse() {
        val local = OfflineLocalResources()
        local.install(id, 1, mapOf("style.json" to entry())) { true }
        val call = local.newCall(request())
        var failed = 0; var succeeded = 0
        call.cancel()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { failed++ }
            override fun onResponse(call: Call, response: Response) { succeeded++; response.close() }
        })
        assertEquals(1, failed)
        assertEquals(0, succeeded)
        assertThrows(IllegalStateException::class.java) { call.execute() }
    }

    @Test fun revokedOwnerCannotReadBodyOrAcceptAnAlreadyConstructedCall() {
        val local = OfflineLocalResources(); var current = true
        local.install(id, 1, mapOf("style.json" to entry())) { current }
        val queued = local.newCall(request())
        val response = local.newCall(request()).execute()
        current = false
        assertThrows(IOException::class.java) { queued.execute() }
        response.use { assertThrows(IOException::class.java) { it.body!!.bytes() } }
    }

    @Test fun invalidHashRolePathAndOversizeCorpusAreRejectedBeforeServing() {
        val local = OfflineLocalResources()
        for (resources in listOf(
            mapOf("style.json" to entry().copy(sha256 = "b".repeat(64))),
            mapOf("style.json" to entry(role = OfflineLocalRole.GLYPH)),
            mapOf("style.json" to entry(), "../escape" to entry()),
            mapOf("style.json" to entry(), "glyphs/IzOpenFont/1-256.pbf" to entry(byteArrayOf(10, 0), OfflineLocalRole.GLYPH)),
            mapOf("style.json" to entry(ByteArray(512_001))),
        )) assertThrows(IllegalArgumentException::class.java) { local.install(id, 1, resources) { true } }
    }

    @Test fun arbitraryOriginQueryAndEncodedPathsNeverResolveToLocalBytes() {
        val local = OfflineLocalResources()
        local.install(id, 1, mapOf("style.json" to entry())) { true }
        val base = OfflineLocalResources.ORIGIN + "/" + id
        for (url in listOf("https://example.org/" + id + "/style.json",
            base + "/style.json?x=1", base + "/%73tyle.json", base + "/glyphs/unknown/0-255.pbf")) {
            assertThrows(IOException::class.java) { local.newCall(Request.Builder().url(url).build()).execute() }
        }
    }
}
