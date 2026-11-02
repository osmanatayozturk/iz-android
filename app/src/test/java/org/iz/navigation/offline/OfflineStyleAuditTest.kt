package org.iz.navigation.offline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineStyleAuditTest {
    private val source = """{"tilejson":"3.0.0","tiles":["https://tiles.openfreemap.org/planet/build/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14,"attribution":"OpenMapTiles OpenStreetMap"}"""
    private fun style(glyph: String = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf") =
        """{"version":8,"sources":{"openmaptiles":{"type":"vector","url":"https://tiles.openfreemap.org/planet"}},"sprite":"https://tiles.openfreemap.org/sprites/ofm_f384/ofm","glyphs":"$glyph","layers":[{"id":"road","type":"line","source":"openmaptiles","source-layer":"transportation"}]}"""
    @Test fun resolvedStylePinsPermittedSourceAndIndependentLocalOverlays() {
        val fetched = mutableListOf<String>()
        val result = OfflineStyleAudit.resolve(style().toByteArray(), { url ->
            fetched += url; source.toByteArray()
        })
        assertEquals(listOf("https://tiles.openfreemap.org/planet"), fetched)
        val pinned = JSONObject(result.json).getJSONObject("sources").getJSONObject("openmaptiles")
        assertFalse(pinned.has("url"))
        assertTrue(pinned.getJSONArray("tiles").getString(0).contains("/planet/build/"))
        assertFalse(result.closure.complete)
        assertEquals(setOf("https://tiles.openfreemap.org"), result.closure.origins)
        val overlay = """{"version":8,"sources":{"osm-tiles":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"]},"route":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}},"layers":[{"id":"osm-raster","type":"raster","source":"osm-tiles"},{"id":"route-line","type":"line","source":"route"}]}"""
        val render = JSONObject(result.withLocalOverlays(overlay))
        assertFalse(render.getJSONObject("sources").has("osm-tiles"))
        assertTrue(render.getJSONObject("sources").has("route"))
        assertEquals("route-line", render.getJSONArray("layers").getJSONObject(1).getString("id"))
    }
    @Test fun foreignFontsOrSourceTemplatesAreRejectedBeforeFurtherFetch() {
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            OfflineStyleAudit.resolve(style("https://evil.example/fonts/{fontstack}/{range}.pbf").toByteArray()) {
                calls++; source.toByteArray()
            }
        }
        assertEquals(0, calls)
        assertThrows(IllegalArgumentException::class.java) {
            OfflineStyleAudit.resolve(style().toByteArray()) {
                source.replace("https://tiles.openfreemap.org/planet/build", "https://tile.openstreetmap.org").toByteArray()
            }
        }
    }
    @Test fun remoteGeoJsonOverlayAndBasemapLayerCollisionCannotEscapeClosure() {
        val audited = OfflineStyleAudit.resolve(style().toByteArray()) { source.toByteArray() }
        for (overlay in listOf(
            """{"sources":{"route":{"type":"geojson","data":"https://evil.example/private"}},"layers":[]}""",
            """{"sources":{"openmaptiles":{"type":"geojson","data":{}}},"layers":[]}""",
            """{"sources":{},"layers":[{"id":"road","type":"line","source":"openmaptiles"}]}"""
        )) assertThrows(IllegalArgumentException::class.java) { audited.withLocalOverlays(overlay) }
    }
    @Test fun unsupportedDataAndUnboundedSourceResponseFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { OfflineStyleAudit.resolve(ByteArray(512_001)) { source.toByteArray() } }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineStyleAudit.resolve(style().replace("\"version\":8", "\"version\":9").toByteArray()) { source.toByteArray() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineStyleAudit.resolve(style().toByteArray()) { ByteArray(512_001) }
        }
    }
}
