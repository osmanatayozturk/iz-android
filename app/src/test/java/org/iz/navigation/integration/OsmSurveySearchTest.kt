package org.iz.navigation.integration

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OsmSurveySearchTest {
    private val origin = WeatherCoordinate(0.0, 0.0)

    @Test fun exactOneKilometreCircleIsSortedStablyAndRetainsOnlySupportedGeometry() = runTest {
        val f = Fixture()
        f.response = json(
            node(3, longitude = 0.0089),
            node(2, longitude = -0.004),
            node(1, longitude = 0.004),
            node(4, longitude = 0.0091),
            way(5, listOf(10, 11, 12, 10)),
            way(6, listOf(10, 11, 12)),
            way(7, listOf(10, 11, 10, 12, 10)),
            node(8).put("type", "relation"),
            node(9, amenity = "bench"),
        )
        val snapshot = f.search.surveyNearby(origin, "account-41") { true }
        assertEquals(listOf(5L, 2L, 1L, 3L), snapshot.venues.map { it.ref.id })
        assertEquals(8L, snapshot.venues.first().version)
        assertEquals(origin, snapshot.origin)
        assertEquals(1_800_000_000_000L, snapshot.refreshedAt)
        assertEquals("https://overpass.fixture.invalid/interpreter", snapshot.endpoint)
        assertFalse(snapshot.capped)
        assertEquals(1, f.calls.size)
    }

    @Test fun hospitalUniversityAndPublicBathRemainExistingVenueCandidates() = runTest {
        val f = Fixture()
        f.response = json(node(1, amenity = "hospital"), node(2, amenity = "university"),
            node(3, amenity = "public_bath"))
        val venues = f.search.surveyNearby(origin, null) { true }.venues
        assertEquals(listOf(1L, 2L, 3L), venues.map { it.ref.id })
        assertTrue(venues.all { it.ref.type == OsmType.NODE })
    }

    @Test fun queryCoversTheWholeCircleWithOutwardRoundedApproximateBounds() {
        for (coordinate in listOf(WeatherCoordinate(0.00019, 0.00019), WeatherCoordinate(85.00019, 29.00019))) {
            val query = osmSurveyQuery(coordinate)
            assertTrue(query.contains("[timeout:12]"))
            assertTrue(query.contains("[maxsize:16777216]"))
            assertTrue(Regex("out\\s+meta\\s+center\\s+100\\s*;").containsMatchIn(query))
            val box = checkNotNull(Regex("\\[bbox:([^]]+)]").find(query))
                .groupValues[1].split(',').map(String::toDouble)
            assertEquals(4, box.size)
            val latitudeRadius = 0.00899
            val longitudeRadius = if (coordinate.latitude > 80) 0.10318 else 0.00899
            assertTrue(box[0] <= coordinate.latitude - latitudeRadius)
            assertTrue(box[2] >= coordinate.latitude + latitudeRadius)
            assertTrue(box[1] <= coordinate.longitude - longitudeRadius)
            assertTrue(box[3] >= coordinate.longitude + longitudeRadius)
        }
    }

    @Test fun serverCandidateCapIsReportedBeforeRadiusOrEligibilityFiltering() = runTest {
        val f = Fixture()
        f.response = json(*(1L..100L).map { node(it, longitude = 0.02) }.toTypedArray())
        val snapshot = f.search.surveyNearby(origin, null) { true }
        assertTrue(snapshot.capped)
        assertTrue(snapshot.venues.isEmpty())
    }

    @Test fun dateLineUsesAWrappedLocalBoxAndPoleUsesTheFullLongitudeRange() {
        val dateLine = checkNotNull(Regex("\\[bbox:([^]]+)]").find(
            osmSurveyQuery(WeatherCoordinate(0.0, 179.999)))).groupValues[1].split(',').map(String::toDouble)
        assertTrue(dateLine[1] > 179.9)
        assertTrue(dateLine[3] < -179.9)
        val pole = checkNotNull(Regex("\\[bbox:([^]]+)]").find(
            osmSurveyQuery(WeatherCoordinate(90.0, 0.0)))).groupValues[1].split(',').map(String::toDouble)
        assertEquals(-180.0, pole[1], 0.0)
        assertEquals(180.0, pole[3], 0.0)
        assertEquals(90.0, pole[2], 0.0)
    }

    @Test fun venueRetainsItsVersionReferenceAddressAndUntouchedTags() = runTest {
        val f = Fixture()
        val venue = node(1)
        venue.getJSONObject("tags").put("name:tr", "Yerel ad").put("addr:street", "Örnek Sokak")
            .put("addr:housenumber", "12").put("operator", "Existing operator")
        f.response = json(venue)
        val result = f.search.surveyNearby(origin, "account-41") { true }.venues.single()
        assertEquals(OsmRef(OsmType.NODE, 1), result.ref)
        assertEquals(8L, result.version)
        assertEquals("Existing operator", result.tags["operator"])
        assertEquals("Yerel ad", result.place.name)
        assertTrue(result.place.displayName.orEmpty().contains("Örnek Sokak"))
        assertTrue(result.place.displayName.orEmpty().contains("12"))
    }

    @Test fun offlineCacheRetainsLastSuccessfulSnapshotAndPartitionsAccountAndEndpoint() = runTest {
        val f = Fixture()
        f.response = json(node(1))
        val first = f.search.surveyNearby(origin, "account-41") { true }
        assertEquals(first, f.search.cachedSurvey("account-41"))
        assertNull(f.search.cachedSurvey("account-42"))
        f.failure = IOException("offline")
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { f.search.surveyNearby(origin, "account-41") { true } }
        }
        assertEquals(first, f.search.cachedSurvey("account-41"))
        f.endpoint = "https://another.fixture.invalid/interpreter"
        assertNull(f.search.cachedSurvey("account-41"))
        f.endpoint = first.endpoint
        assertEquals(first, f.search.cachedSurvey("account-41"))
        assertEquals(2, f.calls.size)
    }

    @Test fun differentOriginGetsItsOwnSnapshotAndExplicitRefreshDoesNotReuseOldResults() = runTest {
        val f = Fixture()
        f.response = json(node(1))
        f.search.surveyNearby(origin, "account-41") { true }
        val secondOrigin = WeatherCoordinate(41.0, 29.0)
        f.response = json(node(2, latitude = 41.0, longitude = 29.0))
        val result = f.search.surveyNearby(secondOrigin, "account-41") { true }
        assertEquals(secondOrigin, result.origin)
        assertEquals(listOf(2L), result.venues.map { it.ref.id })
        assertEquals(result, f.search.cachedSurvey("account-41"))
        assertEquals(2, f.calls.size)
        assertNotEquals(f.calls[0].first, f.calls[1].first)
    }

    @Test fun accountOrRequestInvalidationAndEndpointChangeCannotWriteTheCache() = runTest {
        for (changeEndpoint in listOf(false, true)) {
            val f = Fixture()
            var current = true
            f.afterRead = {
                if (changeEndpoint) f.endpoint = "https://changed.fixture.invalid/interpreter"
                else current = false
            }
            try {
                f.search.surveyNearby(origin, "account-41") { current }
                fail("Stale survey was accepted")
            } catch (_: CancellationException) {
                assertTrue(f.cache.isEmpty())
            }
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun cancellationPropagatesAndPreCancelledEntryNeverStartsAQuery() = runTest {
        val f = Fixture()
        try {
            f.search.surveyNearby(origin, null) { false }
            fail("Invalid entry started")
        } catch (_: CancellationException) {
            assertTrue(f.calls.isEmpty())
        }
        f.failure = CancellationException("fixture cancellation")
        try {
            f.search.surveyNearby(origin, null) { true }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertTrue(f.cache.isEmpty())
        }
        assertEquals(1, f.calls.size)
    }

    @Test fun malformedIncompleteOversizedOrOverCapReplyCannotReplaceCachedSnapshot() = runTest {
        val f = Fixture()
        val first = f.search.surveyNearby(origin, "account-41") { true }
        val invalid = listOf(
            """{"elements":[],"remark":"runtime error: timeout"}""",
            """{"elements":[],"error":"fixture"}""",
            """{"elements":[]} trailing""",
            """{"version":0.6}""",
            json(node(1).apply { remove("version") }),
            json(node(1).put("version", 1.5)),
            json(node(1).put("id", "1")),
            json(node(1).put("lat", "0")),
            json(*(1L..101L).map { node(it) }.toTypedArray()),
            " ".repeat(512_001),
        )
        for (body in invalid) {
            f.response = body
            try {
                f.search.surveyNearby(origin, "account-41") { true }
                fail("Invalid response replaced the survey")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
            assertEquals(first, f.search.cachedSurvey("account-41"))
        }
    }

    @Test fun excessiveResponseAndCacheDepthAreRejectedBeforePlatformJsonParsing() = runTest {
        val f = Fixture()
        val first = f.search.surveyNearby(origin, "account-41") { true }
        val nesting = "[".repeat(70) + "0" + "]".repeat(70)
        f.response = """{"elements":[],"extra":$nesting}"""
        try {
            f.search.surveyNearby(origin, "account-41") { true }
            fail("Excessive response nesting was accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals(first, f.search.cachedSurvey("account-41"))
        }
        val saved = f.cache.entries.single { it.value.startsWith("{") }
        saved.setValue(saved.value.removeSuffix("}") + ""","extra":$nesting}""")
        assertNull(f.search.cachedSurvey("account-41"))
        assertEquals(2, f.calls.size)
    }

    private class Fixture {
        val cache = linkedMapOf<String, String>()
        val calls = mutableListOf<Pair<String, String>>()
        var endpoint = "https://overpass.fixture.invalid/interpreter"
        var response = """{"elements":[]}"""
        var failure: Exception? = null
        var afterRead: () -> Unit = {}
        val search = OsmSurveySearch(
            mutex = Mutex(), endpoint = { endpoint },
            readCached = cache::get, writeCache = { key, value -> cache[key] = value },
            execute = { query, configured ->
                calls += query to configured
                failure?.let { throw it }
                afterRead()
                response
            },
            now = { 1_800_000_000_000L },
        )
    }

    private fun node(id: Long, latitude: Double = 0.0, longitude: Double = 0.0, amenity: String = "cafe") =
        JSONObject().put("type", "node").put("id", id).put("version", 8).put("lat", latitude).put("lon", longitude)
            .put("tags", JSONObject().put("name", "Fixture $id").put("amenity", amenity))

    private fun way(id: Long, refs: List<Long>) = node(id).apply {
        put("type", "way").remove("lat")
        remove("lon")
        put("center", JSONObject().put("lat", 0.0).put("lon", 0.0))
        put("nodes", JSONArray(refs))
    }

    private fun json(vararg elements: JSONObject) = JSONObject().put("elements", JSONArray(elements.toList())).toString()
}
