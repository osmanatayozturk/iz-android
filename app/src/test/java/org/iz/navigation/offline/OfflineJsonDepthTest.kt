package org.iz.navigation.offline

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Bounded grammar/call-boundary regression sources; no network, Android or live provider corpus. */
class OfflineJsonDepthTest {
    @get:Rule val temporary = TemporaryFolder()
    private val style = """{"version":8,"sources":{"openmaptiles":{"type":"vector","url":"https://tiles.openfreemap.org/planet"}},"layers":[{"id":"road","type":"line","source":"openmaptiles","source-layer":"transportation"},{"id":"road-label","type":"symbol","source":"openmaptiles","source-layer":"transportation_name","layout":{"text-field":["coalesce",["get","name:latin"],["get","name"]]}},{"id":"place","type":"symbol","source":"openmaptiles","source-layer":"place","layout":{"text-field":["get","name:latin"]}}]}"""
    private val pinnedStyle = style.replace("\"url\":\"https://tiles.openfreemap.org/planet\"",
        "\"tiles\":[\"https://tiles.openfreemap.org/planet/fixture/{z}/{x}/{y}.pbf\"]")
    private val source = """{"tiles":["https://tiles.openfreemap.org/planet/fixture/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14,"scheme":"xyz"}"""
    private fun arrays(count: Int) = "[".repeat(count) + "0" + "]".repeat(count)
    private fun objects(count: Int) = "{\"x\":".repeat(count) + "0" + "}".repeat(count)
    private fun metadata(json: String, value: String) = json.dropLast(1) + ",\"metadata\":" + value + "}"
    private fun depthFailure(block: () -> Unit) {
        val error = assertThrows(OfflineJsonDepthException::class.java) { block() }
        assertEquals(OfflineJsonDepthException.Reason.DEPTH_LIMIT, error.reason)
    }
    private fun pack() = corridorPlan(OfflineCorridorRequest("json-fixture", "a".repeat(64),
        listOf(OfflinePoint(41.0, 29.0), OfflinePoint(41.001, 29.001)), 100.0, 8, 8))

    @Test fun sixtyFourContainersPassAndSixtyFiveAreRejectedWithoutRecursiveParsing() {
        for (json in listOf(arrays(64), objects(64))) assertSame(json, OfflineJsonDepth.requireSafe(json))
        for (json in listOf(arrays(65), objects(65))) depthFailure { OfflineJsonDepth.requireSafe(json) }
    }

    @Test fun mixedObjectAndArrayNestingSharesTheSameContainerLimit() {
        var json = "0"
        repeat(32) { json = "{\"x\":[" + json + "]}" }
        assertSame(json, OfflineJsonDepth.requireSafe(json))
        depthFailure { OfflineJsonDepth.requireSafe("[" + json + "]") }
    }

    @Test fun legitimateExpressionsNumbersAndWhitespaceKeepTheirOriginalText() {
        val json = " \t\r\n" + """{"expression":["case",["all",true,false],-0.125e+2,3E-2],"empty":[{},[],null],"label":"Şişli"}""" + "\r\n"
        assertSame(json, OfflineJsonDepth.requireSafe(json))
        val parsed = JSONObject(OfflineJsonDepth.requireSafe(json))
        assertEquals("Şişli", parsed.getString("label"))
        assertEquals(-12.5, parsed.getJSONArray("expression").getDouble(2), 0.0)
    }

    @Test fun quotedBracesUnicodeEscapesAndBackslashParityDoNotCountAsContainers() {
        for (value in listOf("{}[]".repeat(80), "\\", "\\\\", "\"", "\\\"[[[]]]", "https://map/{fontstack}/{range}.pbf /* # // 'text'")) {
            val json = JSONObject().put("value", value).put("tail", JSONArray().put(JSONObject().put("n", 1))).toString()
            assertSame(json, OfflineJsonDepth.requireSafe(json))
            assertEquals(value, JSONObject(OfflineJsonDepth.requireSafe(json)).getString("value"))
        }
        val unicode = """{"value":"\u005b\u007b","tail":[0]}"""
        assertEquals("[{", JSONObject(OfflineJsonDepth.requireSafe(unicode)).getString("value"))
    }

    @Test fun malformedStandardJsonFailsWithATypedReason() {
        val cases = listOf("", " ", "{}{}", "[}", "[1,]", """{"x":0,}""", """{"x":}""",
            """{"x":"unterminated}""", """{"x":"\q"}""", """{"x":"\u00g0"}""",
            """{"x":true"[[[]]"}""", "01", "+1", "-", "1.", "1e", "1e+", "--1", "NaN", "Infinity",
            "{\"x\":\"" + 1.toChar() + "\"}")
        for (json in cases) {
            val error = assertThrows(OfflineJsonDepthException::class.java) { OfflineJsonDepth.requireSafe(json) }
            assertEquals(OfflineJsonDepthException.Reason.MALFORMED_JSON, error.reason)
        }
    }

    @Test fun androidDialectQuotesCommentsAndUnquotedKeysCannotHideNesting() {
        val cases = listOf("{'x':'" + arrays(65) + "'}", "/*" + arrays(65) + "*/{}",
            "//" + arrays(65) + "\n{}", "#" + arrays(65) + "\n{}", """{"x":/*comment*/0}""",
            "{\"x\":0//comment\n}", "{x:0}", """{"x"=0}""")
        for (json in cases) {
            val error = assertThrows(OfflineJsonDepthException::class.java) { OfflineJsonDepth.requireSafe(json) }
            assertEquals(OfflineJsonDepthException.Reason.MALFORMED_JSON, error.reason)
        }
    }

    @Test fun existingSyntheticLabelledStyleAndTileJsonRemainCompatible() {
        var fetched = 0
        val result = OfflineLabelledProfile.resolve(style.toByteArray()) { url ->
            assertEquals("https://tiles.openfreemap.org/planet", url); fetched++; source.toByteArray()
        }
        assertEquals(1, fetched)
        val parsed = JSONObject(result.json)
        assertEquals(8, parsed.getInt("version"))
        assertFalse(parsed.getJSONObject("sources").getJSONObject("openmaptiles").has("url"))
        assertTrue(parsed.getJSONArray("layers").toString().contains(OfflineGlyphBundle.NAME))
    }

    @Test fun deepProviderStyleIsRejectedBeforeSourceFetch() {
        var fetched = 0
        depthFailure {
            OfflineLabelledProfile.resolve(metadata(style, objects(64)).toByteArray()) {
                fetched++; source.toByteArray()
            }
        }
        assertEquals(0, fetched)
    }

    @Test fun deepTileJsonMetadataIsRejectedAtTheActualSourceBoundary() {
        var fetched = 0
        depthFailure {
            OfflineStyleAudit.resolve(style.toByteArray()) {
                fetched++; metadata(source, arrays(64)).toByteArray()
            }
        }
        assertEquals(1, fetched)
    }

    @Test fun tileJsonAttributionCannotExceedDepthWhenEmbeddedIntoStyle() {
        // Standalone metadata root+63 arrays is valid depth64; style.sources.id adds two parents.
        val tileJson = source.dropLast(1) + ",\"attribution\":" + arrays(63) + "}"
        assertSame(tileJson, OfflineJsonDepth.requireSafe(tileJson))
        depthFailure { OfflineStyleAudit.resolve(style.toByteArray()) { tileJson.toByteArray() } }
        // The corresponding normalized depth64 boundary remains compatible.
        val shallower = source.dropLast(1) + ",\"attribution\":" + arrays(61) + "}"
        val accepted = OfflineStyleAudit.resolve(style.toByteArray()) { shallower.toByteArray() }
        assertSame(accepted.json, OfflineJsonDepth.requireSafe(accepted.json))
    }
    @Test fun derivedBaseAndOverlayTextAreCheckedBeforeTheirDomReparse() {
        val shallow = OfflineStyleAudit.resolve(pinnedStyle.toByteArray()) { error("Pinned fixture must not fetch") }
        val deep = metadata(pinnedStyle, objects(64))
        val supplied = AuditedOfflineStyle(deep, shallow.closure)
        depthFailure { supplied.withLocalOverlays("""{"sources":{},"layers":[]}""") }
        depthFailure {
            shallow.withLocalOverlays(metadata("""{"sources":{},"layers":[]}""", arrays(64)))
        }
    }

    @Test fun pinnedRestoreRejectsDeepMetadataAndPreservesTheExactCachedFile() {
        val deep = metadata(pinnedStyle, arrays(64))
        depthFailure { OfflineLabelledProfile.parse(deep.toByteArray()) }
        val root = temporary.newFolder()
        val repository = OfflineStyleRepository(root)
        val bytes = deep.toByteArray()
        val closure = OfflineResourceClosure(OfflineStyleAudit.sha256(bytes),
            setOf("https://tiles.openfreemap.org"), setOf("OpenFreeMap", "OpenMapTiles", "OpenStreetMap"), false)
        val saved = repository.save(pack(), AuditedOfflineStyle(deep, closure))
        assertNull(repository.load(pack()))
        assertTrue(saved.isFile)
        assertArrayEquals(bytes, saved.readBytes())
    }

    @Test fun leasedTileJournalWrapperAndArrayPayloadBothUseTheSamePreflight() {
        val root = temporary.newFolder()
        val repository = OfflineStyleRepository(root)
        val pack = pack()
        assertTrue(repository.saveTileKeys(pack, emptySet()))
        val journal = root.listFiles()!!.single { it.name.endsWith(".tiles") }
        val original = journal.readText()
        journal.writeText(metadata(original, objects(64)))
        depthFailure { repository.tileKeys(pack) }
        val payload = arrays(65)
        val wrapper = JSONObject().put("payload", payload)
            .put("sha256", OfflineStyleAudit.sha256(payload.toByteArray())).toString()
        journal.writeText(wrapper)
        depthFailure { repository.tileKeys(pack) }
        assertEquals(wrapper, journal.readText())
    }
}
