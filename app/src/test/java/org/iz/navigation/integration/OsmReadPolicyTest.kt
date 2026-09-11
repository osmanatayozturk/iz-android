package org.iz.navigation.integration

import org.iz.navigation.data.OsmType
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class OsmReadPolicyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    @Test fun nominatimPreservesGeometryReferenceAndSkipsInvalidCoordinates() {
        val places = parseNominatimPlaces("""[
          {"osm_type":"way","osm_id":42,"name":"Park","lat":"41.01","lon":"29.02"},
          {"osm_type":"node","osm_id":99,"display_name":"Invalid","lat":"999","lon":"29"},
          {"display_name":"Address result","lat":"40","lon":"28"}
        ]""")
        assertEquals(listOf("Park", "Address result"), places.map { it.name })
        assertEquals(OsmType.WAY, places[0].osmRef?.type)
        assertEquals(42L, places[0].osmRef?.id)
        assertEquals(41.01, places[0].latitude, 0.000001)
        assertNull(places[1].osmRef)
    }

    @Test fun overpassUsesWayCenterAndKeepsUnrelatedSameNamePlaces() {
        val places = parseOverpassPlaces("""{"elements":[
          {"type":"node","id":1,"lat":41,"lon":29,"tags":{"name":"Cafe","amenity":"cafe"}},
          {"type":"way","id":2,"center":{"lat":41.001,"lon":29.001},"tags":{"name":"Cafe"}},
          {"type":"way","id":2,"center":{"lat":41.001,"lon":29.001},"tags":{"name":"Cafe"}},
          {"type":"relation","id":3,"tags":{"name":"Missing geometry"}}
        ]}""")
        assertEquals(2, places.size)
        assertEquals(OsmType.WAY, places[1].osmRef?.type)
        assertEquals(41.001, places[1].latitude, 0.000001)
    }

    @Test fun persistedDailyBudgetBlocksRequestsAndResetsOnNewUtcDay() {
        val now = 1_000L
        val full = OsmReadBudget(day = 0, queries = 90, bytes = 10)
        assertFalse(full.canRequest(now))
        val restored = OsmReadBudget.fromJson(full.toJson())
        assertFalse(restored.canRequest(now))
        assertTrue(restored.canRequest(86_400_001L))
        val next = restored.startRequest(86_400_001L)
        assertEquals(1, next.queries)
        assertEquals(0L, next.bytes)
    }

    @Test fun bandwidthLimitAndServerCooldownBothBlockNewRequests() {
        assertFalse(OsmReadBudget(day = 0, bytes = 9_000_000).canRequest(1_000))
        val throttled = OsmReadBudget(day = 0).withCooldown(1_000, retryAfterSeconds = 2)
        assertFalse(throttled.canRequest(30_999))
        assertTrue(throttled.canRequest(31_000))
        val restored = OsmReadBudget.fromJson(throttled.toJson())
        assertFalse(restored.canRequest(30_999))
    }

    @Test fun inFlightReservationSurvivesProcessDeathAndReleasesUnusedBytesAfterResponse() {
        val reserved = OsmReadBudget(day = 0, bytes = 8_900_000).startRequest(1_000, reservedBytes = 100_000)
        assertFalse(OsmReadBudget.fromJson(reserved.toJson()).canRequest(2_000))
        val completed = reserved.finishResponse(reservedBytes = 100_000, receivedBytes = 50_000)
        assertEquals(8_950_000L, completed.bytes)
        assertTrue(completed.canRequest(2_000))
    }

    @Test fun serviceEndpointsRequireHttpsAndRejectEmbeddedCredentials() {
        assertFalse(isAllowedOsmEndpoint("http://example.com/search"))
        assertFalse(isAllowedOsmEndpoint("https://user:password@example.com/search"))
        assertFalse(isAllowedOsmEndpoint("https://example.com/search#fragment"))
        assertTrue(isAllowedOsmEndpoint("https://example.com/custom/search"))
    }

    @Test fun tileTemplatesRequireAllCoordinatesAndHttps() {
        assertTrue(isAllowedOsmTileTemplate("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertFalse(isAllowedOsmTileTemplate("https://example.com/{z}/{x}.png"))
        assertFalse(isAllowedOsmTileTemplate("http://example.com/{z}/{x}/{y}.png"))
    }

    @Test fun renderedPoiSelectionUsesItsOwnIdentityInsteadOfChangingCandidateArrayIndex() {
        val selected = parseRenderedOsmPlace("""{"type":"Feature",
          "geometry":{"type":"Point","coordinates":[29.02,41.01]},
          "properties":{"name":"Rendered cafe","osmType":"WAY","osmId":42}
        }""")
        assertNotNull(selected)
        assertEquals("Rendered cafe", selected?.name)
        assertEquals(42L, selected?.osmRef?.id)
        assertEquals(OsmType.WAY, selected?.osmRef?.type)
        assertEquals(41.01, selected!!.latitude, 0.000001)
    }

    @Test fun interruptedCacheWriteIsAMissAndNeverPoisonsTheSameQuery() {
        val file = temporaryFolder.newFile("cached-query")
        file.writeText("[{\"name\":")
        val now = System.currentTimeMillis()
        assertNull(readValidCachedPlaces(file, now, 86_400_000, ::parseNominatimPlaces))
        file.writeText("""[{"name":"Cafe","lat":"41","lon":"29"}]""")
        assertEquals("Cafe", readValidCachedPlaces(file, System.currentTimeMillis(), 86_400_000, ::parseNominatimPlaces)?.single()?.name)
    }
}
