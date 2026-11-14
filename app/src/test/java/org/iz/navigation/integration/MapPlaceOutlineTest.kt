package org.iz.navigation.integration

import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

class MapPlaceOutlineTest {
    private val ref = OsmRef(OsmType.WAY, 42)
    private fun catalog() = OsmPresetCatalog.fromJson(File(System.getProperty("iz.catalogue") ?: "src/main/assets/osm-light-catalogue-v1.json").readText())
    private fun body(points: List<Pair<Double, Double>> = listOf(41.0 to 29.0, 41.0 to 29.001, 41.001 to 29.001, 41.0 to 29.0),
        tags: Map<String, String> = mapOf("building" to "yes")): String = JSONObject().put("elements", JSONArray().put(
        JSONObject().put("type", "way").put("id", 42).put("nodes", JSONArray(listOf(1, 2, 3, 1)))
            .put("tags", JSONObject(tags)).put("geometry", JSONArray().also { geometry -> points.forEach { (lat, lon) ->
                geometry.put(JSONObject().put("lat", lat).put("lon", lon))
            } }))).toString()

    @Test fun validAreaEvidenceClosedSimpleRingUsesActualCoordinatesAndIdentity() {
        val result = parseMapPlaceOutline(body(), ref, catalog())
        val outline = requireNotNull(result.outline)
        assertEquals(4, outline.points.size)
        assertEquals(41.0, outline.points.first().latitude, 0.0)
        assertEquals(29.001, outline.points[1].longitude, 0.0)
        assertEquals(outline.points.first(), outline.points.last())
        assertNull(result.message)
        assertTrue(mapPlaceOutlineQuery(ref).contains("way(42)"))
        assertTrue(mapPlaceOutlineQuery(ref).contains("[timeout:15][maxsize:16777216]"))
    }
    @Test fun openMissingDegenerateWrongIdentityOrLineOnlyShapesNeverInventPolygon() {
        val catalogue = catalog()
        val examples = listOf(body().replace("[1,2,3,1]", "[1,2,3,4]"), "{}",
            body(listOf(41.0 to 29.0, 41.0 to 29.0, 41.0 to 29.0, 41.0 to 29.0)),
            body().replace("\"id\":42", "\"id\":43"), body(tags = mapOf("highway" to "residential")),
            body(tags = mapOf("building" to "no")), body().replace("\"lat\":41.001", "\"lat\":141.001"))
        examples.forEach { json ->
            val result = parseMapPlaceOutline(json, ref, catalogue)
            assertNull(json, result.outline)
            assertFalse(result.message.isNullOrBlank())
        }
    }
    @Test fun incompleteOrOversizePayloadIsNeverSuccessful() {
        assertThrows(IllegalArgumentException::class.java) { parseMapPlaceOutline("{\"remark\":\"incomplete\",\"elements\":[]}", ref, catalog()) }
        assertThrows(IllegalArgumentException::class.java) { parseMapPlaceOutline(" ".repeat(512_001), ref, catalog()) }
        assertThrows(IllegalArgumentException::class.java) { mapPlaceOutlineQuery(OsmRef(OsmType.NODE, 42)) }
    }
    @Test fun selfIntersectionAndMultipartNeverBecomeDisplayPolygon() {
        // Crossing ring with nonzero signed area, so rejection exercises intersection validation.
        val points = listOf(41.0 to 29.0, 41.003 to 29.003, 41.0 to 29.003, 41.002 to 29.0, 41.0 to 29.0)
        val crossing = JSONObject(body(points)).apply {
            getJSONArray("elements").getJSONObject(0).put("nodes", JSONArray(listOf(1, 2, 3, 4, 1)))
        }.toString()
        assertNull(parseMapPlaceOutline(crossing, ref, catalog()).outline)
        val multipart = JSONObject(body()).apply { getJSONArray("elements").getJSONObject(0).put("members", JSONArray()) }.toString()
        assertNull(parseMapPlaceOutline(multipart, ref, catalog()).outline)
    }
    @Test fun onlyValidGeometryIsCachedForTenMinutesByEndpointIdentityAndRevision() {
        runBlocking {
            var endpoint = "https://endpoint-a.invalid/api"
            var now = 0L
            var calls = 0
            val stored = mutableMapOf<String, Pair<Long, String>>()
            val catalogue = catalog()
            val reader = MapPlaceOutlineReader(Mutex(), { endpoint }, { key, maxAge ->
                assertEquals(600_000L, maxAge)
                stored[key]?.takeIf { now - it.first in 0..maxAge }?.second
            }, { key, value ->
                assertTrue(key.contains(MAP_OUTLINE_QUERY_REVISION)); assertTrue(key.contains("WAY/42"))
                stored[key] = now to value
            }, { _, actualEndpoint -> calls++; assertEquals(endpoint, actualEndpoint); body() }, { catalogue })
            assertNotNull(reader.read(ref).outline)
            assertNotNull(reader.read(ref).outline)
            assertEquals(1, calls)
            now = 600_001
            assertNotNull(reader.read(ref).outline)
            assertEquals(2, calls)
            endpoint = "https://endpoint-b.invalid/api"
            assertNotNull(reader.read(ref).outline)
            assertEquals(3, calls)
        }
    }
    @Test fun incompleteFailureAndEndpointReplacementDoNotWriteSuccessCache() {
        runBlocking {
            val catalogue = catalog()
            var endpoint = "endpoint-a"
            var writes = 0
            var calls = 0
            val invalid = MapPlaceOutlineReader(Mutex(), { endpoint }, { _, _ -> null }, { _, _ -> writes++ },
                { _, _ -> calls++; "{}" }, { catalogue })
            assertNull(invalid.read(ref).outline)
            assertNull(invalid.read(ref).outline)
            assertEquals(2, calls); assertEquals(0, writes)
            val replaced = MapPlaceOutlineReader(Mutex(), { endpoint }, { _, _ -> null }, { _, _ -> writes++ },
                { _, _ -> endpoint = "endpoint-b"; body() }, { catalogue })
            try { replaced.read(ref); fail("Changed endpoint must cancel") } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(0, writes)
        }
    }
    @Test fun renderedOutlineBelongsOnlyToSelectedWayAndClearsWithoutSelection() {
        val place = org.iz.navigation.data.SelectedOsmPlace("Building", 41.0, 29.0, ref)
        val candidate = MapPlaceCandidate(place, "Bina", outline = parseMapPlaceOutline(body(), ref, catalog()).outline)
        val feature = JSONObject(mapPlaceOutlineJson(candidate)).getJSONArray("features").getJSONObject(0)
        assertEquals("Polygon", feature.getJSONObject("geometry").getString("type"))
        assertEquals(42, feature.getJSONObject("properties").getLong("osmId"))
        assertEquals(0, JSONObject(mapPlaceOutlineJson(null)).getJSONArray("features").length())
        assertEquals(0, JSONObject(mapPlaceOutlineJson(candidate.copy(place = place.copy(osmRef = OsmRef(OsmType.NODE, 42))))).getJSONArray("features").length())
    }
}
