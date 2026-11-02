package org.iz.navigation.offline

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real installed font/notices and actual source classes; synthetic vector bytes, no Android/network. */
class OfflineLabelledProfileTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assets = File(System.getProperty("iz.offline.assets",
        "D:/CodexWorktrees/iz-roadmap-five-services-20261102/app/src/main/assets/offline/labelled-latin-v1"))
    private fun asset(path: String) = File(assets, path).readBytes()
    private val metadata = """{"tiles":["https://tiles.openfreemap.org/planet/fixture/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":0,"scheme":"xyz"}"""
    private val raw = """{"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf","sprite":"https://tiles.openfreemap.org/sprites/ofm_f384/ofm","sources":{"earth":{"type":"raster","tiles":["https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png"]},"openmaptiles":{"type":"vector","url":"https://tiles.openfreemap.org/planet"}},"layers":[{"id":"raster","type":"raster","source":"earth"},{"id":"road","type":"line","source":"openmaptiles","paint":{"line-pattern":"unknown-art"}},{"id":"road-label","type":"symbol","source":"openmaptiles","source-layer":"transportation_name","layout":{"text-field":["get","name:latin"],"text-font":["Noto Sans Regular"],"icon-image":"unknown-art"}},{"id":"place","type":"symbol","source":"openmaptiles","source-layer":"place","layout":{"text-field":["get","name:latin"]}}]}"""
    private val tileUrl = "https://tiles.openfreemap.org/planet/fixture/0/0/0.pbf"
    private data class Fixture(val root: File, val corpusDir: File, val style: AuditedOfflineStyle,
        val resources: Map<String, OfflineLocalFileResource>, val acquisition: String)
    private fun fixture(): Fixture {
        val root = temporary.newFolder(); val corpusDir = File(root, "corpus")
        val corpus = OfflineResourceCorpus(corpusDir, "a".repeat(64), 0) {}
        fun fetch(url: String): OfflineFetchedResource = OfflineFetchedResource(when (url) {
            OPEN_FREE_MAP_STYLE -> raw.toByteArray()
            "https://tiles.openfreemap.org/planet" -> metadata.toByteArray()
            tileUrl -> ByteArray(0)
            else -> error("Unexpected fixture request")
        }, 1_800_000_000_000L, "\"fixture\"", null)
        val source = corpus.obtain(OPEN_FREE_MAP_STYLE, "style", ::fetch)
        val resolved = OfflineLabelledProfile.resolve(source) { corpus.obtain(it, "source", ::fetch) }
        corpus.allowTiles(setOf(tileUrl)); corpus.obtain(tileUrl, "vector", ::fetch)
        val (acquisition, tiles) = corpus.seal(setOf(tileUrl))
        val bundle = OfflineLabelledProfile.glyphBundle(::asset)
        OfflineLabelledProfile.installNotices(root, ::asset)
        val (style, resources) = OfflineLabelledProfile.materialize(resolved, bundle, root, corpusDir, acquisition, tiles)
        return Fixture(root, corpusDir, style, resources, acquisition)
    }

    @Test fun actualSealedSpacedFontStackAndEmptyVectorReachTheSamePinnedLocalCorpus() {
        val f = fixture(); val proof = requireNotNull(f.style.profileProof)
        assertEquals(OfflineLabelledProfile.FONT_SHA, proof.fontBundleSha256)
        assertEquals("NOT_RUN", proof.nativeReadability)
        val style = JSONObject(f.style.json)
        assertFalse(style.has("sprite"))
        assertEquals(1, style.getJSONObject("sources").length())
        assertFalse(f.style.json.contains("line-pattern") || f.style.json.contains("icon-image"))
        assertTrue(f.style.json.contains("road-label") && f.style.json.contains("place"))
        val local = OfflineLocalResources()
        local.installFiles(proof.corpusId, 3, f.root, f.resources) { true }.use {
            val glyph = style.getString("glyphs").replace("{fontstack}", "Iz%20Offline%20Latin%20Regular").replace("{range}", "0-255")
            local.newCall(Request.Builder().url(glyph).build()).execute().use { response ->
                assertArrayEquals(asset("glyphs/Iz Offline Latin Regular/0-255.pbf"), response.body!!.bytes())
            }
            val tile = style.getJSONObject("sources").getJSONObject("openmaptiles").getJSONArray("tiles").getString(0)
                .replace("{z}", "0").replace("{x}", "0").replace("{y}", "0")
            local.newCall(Request.Builder().url(tile).build()).execute().use { assertEquals(0, it.body!!.bytes().size) }
            val unsafe = glyph.replace("Iz%20Offline%20Latin%20Regular", "Iz%2FOffline")
            assertThrows(IOException::class.java) { local.newCall(Request.Builder().url(unsafe).build()).execute() }
        }
        assertNull(OfflineLabelledProfile.parse(f.style.json.toByteArray()).profileProof)
        assertFalse("Pinned graph alone cannot assert complete", OfflineLabelledProfile.parse(f.style.json.toByteArray()).closure.complete)
    }

    @Test fun visibleNoticeCorpusContainsOnlyActualLegalTextAndRejectsTampering() {
        val f = fixture()
        val text = requireNotNull(OfflineProfileNotices.read(f.root, f.style.json, f.style.closure.styleSha256))
        assertTrue(text.contains("SIL OPEN FONT LICENSE Version 1.1") && text.contains("Łukasz Dziedzic"))
        assertFalse(text.contains("Profile notice contract") || text.contains("glyphCount") || text.contains("notice-or-inventory"))
        assertTrue(text.contains("Copyright (c) 2023 Zsolt Ero"))
        val manifest = JSONObject(File(f.root, "notices/" + OfflineLabelledProfile.NOTICES_SHA + ".json").readText())
        val path = manifest.getJSONArray("files").getJSONObject(0).getString("path")
        File(f.root, path).appendText("changed")
        assertNull(OfflineProfileNotices.read(f.root, f.style.json, f.style.closure.styleSha256))
    }

    @Test fun resumedAcquisitionUsesIdenticalHashAndRejectsLostJournalOrCounterReset() {
        val f = fixture()
        val restored = OfflineResourceCorpus(f.corpusDir, "a".repeat(64), 10_000) {}
        restored.allowTiles(setOf(tileUrl))
        assertEquals(0, restored.obtain(tileUrl, "vector") { error("Retained bytes must not be fetched again") }.size)
        assertEquals(f.acquisition, restored.seal(setOf(tileUrl)).first)
        assertThrows(IllegalArgumentException::class.java) { OfflineResourceCorpus(f.corpusDir, "a".repeat(64), 0) {} }
        File(f.corpusDir, "acquisition.json").delete()
        assertThrows(IllegalStateException::class.java) { OfflineResourceCorpus(f.corpusDir, "a".repeat(64), 10_000) {} }
    }

    @Test fun exactAcquisitionAdmissionAndFileBackedRevocationRejectUnrelatedOrChangedResources() {
        val f = fixture()
        val restored = OfflineResourceCorpus(f.corpusDir, "a".repeat(64), 10_000) {}
        assertThrows(IllegalArgumentException::class.java) { restored.admit(tileUrl) }
        restored.allowTiles(setOf(tileUrl))
        assertThrows(IllegalArgumentException::class.java) { restored.admit("https://tiles.openfreemap.org/planet/other/0/0/0.pbf") }
        val local = OfflineLocalResources(); var current = true
        val proof = requireNotNull(f.style.profileProof)
        val lease = local.installFiles(proof.corpusId, 3, f.root, f.resources) { current }
        val request = Request.Builder().url(OfflineLocalResources.styleUrl(proof.corpusId)).build()
        val call = local.newCall(request); val clone = call.clone()
        current = false
        assertThrows(IOException::class.java) { clone.execute() }
        lease.close()
        current = true
        local.installFiles(proof.corpusId, 4, f.root, f.resources) { current }.use {
            assertThrows(IOException::class.java) { call.execute() }
            val file = requireNotNull(f.resources["style.json"]).file
            file.appendText("tampered")
            assertThrows(IOException::class.java) { local.newCall(request).execute() }
        }
    }
}
