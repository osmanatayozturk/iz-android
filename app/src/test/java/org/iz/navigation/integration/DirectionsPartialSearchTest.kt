package org.iz.navigation.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.iz.navigation.data.OsmType
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.ui.OsmSearchController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest

/** Saved synthetic responses; these coordinates are never device GPS or live acceptance. */
class DirectionsPartialSearchTest {
    @get:Rule val temporary = TemporaryFolder()
    private val origin = WeatherCoordinate(39.93, 32.85)

    @Test fun unrelatedHungarianPrimaryUsesOneNearbyRequestForOriginalQuery() = runTest {
        val f = fixture(saved("tobb-primary"), saved("tobb-nearby"))
        val result = f.search("TOBB ET")
        assertEquals(listOf(900000003L, 61403953L), result.map { it.osmRef!!.id })
        val university = result.single { it.osmRef!!.id == 61403953L }
        assertEquals(OsmType.WAY, university.osmRef!!.type)
        assertEquals("TOBB Ekonomi ve Teknoloji Üniversitesi", university.name)
        assertEquals("Söğütözü Caddesi 43", university.displayName)
        assertEquals(WeatherCoordinate(39.921, 32.798), WeatherCoordinate(university.latitude, university.longitude))
        assertEquals("University and hospital remain separate", 2, result.map { it.osmRef }.distinct().size)
        assertEquals(listOf("GET", "POST"), f.requests.map { it.method })
        assertEquals("TOBB ET", f.requests.first().url.queryParameter("q"))
        assertEquals("32.65,40.10,33.15,39.70", f.requests.first().url.queryParameter("viewbox"))
        assertTrue(f.ql().contains("nwr(39.70,32.65,40.10,33.15)"))
        assertEquals(1, f.locations)
        assertEquals(1, f.budget.queries)
        assertTrue(f.budget.bytes > 0)
    }

    @Test fun emptyPrimaryFindsRequiredHamamWithTurkishDiacriticsAndNoAddress() = runTest {
        val f = fixture(secondary = saved("hamam-nearby"))
        val place = f.search("Kecioren Hamam").single()
        assertEquals(14170085798L, place.osmRef!!.id)
        assertEquals(OsmType.NODE, place.osmRef!!.type)
        assertEquals("Keçiören Hamamı", place.name)
        assertNull(place.displayName)
        assertEquals(2, f.requests.size)
    }

    @Test fun suitablePrimaryRetainsContinuationWithoutOverpassOrThirdRequest() = runTest {
        val f = fixture(primary = primary("Keçiören Hamamı"))
        f.continuation = primary("Keçiören Hamamı Şubesi", 2)
        assertEquals(listOf(1L, 2L), f.search("Kecioren Hamam").map { it.osmRef!!.id })
        assertEquals(2, f.requests.size)
        assertTrue(f.requests.all { it.method == "GET" })
        assertEquals("N1", f.requests.last().url.queryParameter("exclude_place_ids"))
        assertEquals(0, f.budget.queries)
    }

    @Test fun primaryAliasesAreRecheckedOnCacheRead() = runTest {
        val item = JSONObject(primary("Other title").removeSurrounding("[", "]"))
            .put("namedetails", JSONObject().put("short_name", "TOBB ETÜ"))
        val f = fixture(primary = JSONArray().put(item).toString())
        repeat(2) { assertEquals(listOf(1L), f.search("TOBB ET").map { it.osmRef!!.id }) }
        assertEquals(2, f.requests.size)
        assertTrue(f.requests.all { it.method == "GET" })
        assertEquals("1", f.requests.first().url.queryParameter("namedetails"))
    }

    @Test fun suitablePrimaryAfterInvalidRowsStillUsesNominatimContinuation() = runTest {
        val rows = JSONArray()
        repeat(10) { rows.put(JSONObject().put("name", "Invalid").put("lat", 999).put("lon", 32)) }
        rows.put(JSONArray(primary("Keçiören Hamamı")).getJSONObject(0))
        val f = fixture(primary = rows.toString())
        assertEquals(listOf(1L), f.search("Kecioren Hamam").map { it.osmRef!!.id })
        assertEquals(2, f.requests.size)
        assertTrue("Match must belong to a valid selected row", f.requests.all { it.method == "GET" })
    }

    @Test fun noOriginInvalidWorldBoxAndTooShortInputNeverSendNearbyRequest() = runTest {
        for (location in listOf(null, WeatherCoordinate(90.0, 180.0))) {
            val f = fixture(primary = primary("Unrelated"))
            assertEquals(listOf(1L), f.search("TOBB ET", location).map { it.osmRef!!.id })
            assertEquals(1, f.requests.size)
        }
        for (query in listOf("ab", ".*", "a.*b", "\"\\;")) {
            val f = fixture(primary = primary("Unrelated"))
            assertEquals(listOf(1L), f.search(query).map { it.osmRef!!.id })
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun secondaryEmptyIrrelevantMalformedRemarkAndIncompletePayloadPreserveFirst() = runTest {
        val errors = listOf("{broken", "{}", "{\"remark\":\"timeout\",\"elements\":[]}",
            "{\"remark\":\" \",\"elements\":[]}", "{\"error\":\"busy\",\"elements\":[]}",
            "{\"elements\":[{}]}", "{\"elements\":[null]}",
            overpass(candidate(8, "TOBB ET").replace("\"id\":8", "\"id\":8.5")),
            overpass(candidate(8, "TOBB ET").replace("\"lat\":39.93", "\"lat\":\"39.93\"")),
            "{\"elements\":[{\"type\":\"way\",\"id\":4,\"tags\":{\"name\":\"TOBB\"}}]}")
        for (body in errors + listOf("{\"elements\":[]}", overpass(candidate(8, "Unrelated")))) {
            val f = fixture(primary("Hungarian result"), body)
            assertEquals(listOf(1L), f.search("TOBB ET").map { it.osmRef!!.id })
            assertEquals(2, f.requests.size)
            assertEquals(if (body in errors) 0 else 1, f.overpassWrites.size)
        }
    }

    @Test fun secondary406429502503AndTimeoutPreservePrimaryWithoutRetry() = runTest {
        for (status in listOf(406, 429, 502, 503)) {
            val f = fixture(primary = primary("Unrelated")); f.secondaryStatus = status
            assertEquals(listOf(1L), f.search("TOBB ET").map { it.osmRef!!.id })
            assertEquals(2, f.requests.size); assertTrue(f.overpassWrites.isEmpty())
            if (status in listOf(406, 429)) {
                assertTrue(f.budget.cooldownUntil > f.clock)
                f.search("TOBB ET")
                assertEquals("Persisted cooldown blocks second network read", 2, f.requests.size)
            }
        }
        val f = fixture(primary = primary("Unrelated")); f.secondaryFailure = SocketTimeoutException("fixture timeout")
        assertEquals(listOf(1L), f.search("TOBB ET").map { it.osmRef!!.id })
        assertEquals(2, f.requests.size); assertTrue(f.overpassWrites.isEmpty())
    }

    @Test fun sharedDailyCountByteBudgetAndCooldownPreventSecondaryNetwork() = runTest {
        for (budget in listOf(OsmReadBudget(queries = 90), OsmReadBudget(bytes = 9_000_000),
            OsmReadBudget(cooldownUntil = Long.MAX_VALUE))) {
            val f = fixture(primary = primary("Unrelated")); f.budget = budget.copy(day = f.clock / 86_400_000)
            assertEquals(listOf(1L), f.search("TOBB ET").map { it.osmRef!!.id })
            assertEquals(1, f.requests.size); assertTrue(f.overpassWrites.isEmpty())
        }
    }

    @Test fun primaryErrorRetainsExistingFailureAndNeverCallsOverpass() = runTest {
        for (status in listOf(406, 429, 502)) {
            val f = fixture(); f.primaryStatus = status
            try { f.search("TOBB ET"); fail("Primary must fail") } catch (_: OsmServiceException) { }
            assertEquals(1, f.requests.size); assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun allAliasTagsFilterOriginalQueryAndDedupUsesIdentity() = runTest {
        val tags = partialNameTags.mapIndexed { index, tag -> candidate(10L + index, "Other $index", tag to "Kuzey Kültür Merkezi") }
        val f = fixture(secondary = overpass(*(tags + tags.first() + candidate(90, "Unrelated")).toTypedArray()))
        assertEquals((10L..14L).toList(), f.search("kuz kult").map { it.osmRef!!.id })
        assertEquals(2, f.requests.size)
    }

    @Test fun rankingPrecedesTwentyLimitAndKeepsServiceOrderOnTiesOrNoPosition() = runTest {
        val candidates = (1L..50L).map { candidate(it, "Kültür Merkezi", lat = 40.5 - it / 100.0, lon = 32.85) }
        val f = fixture(secondary = overpass(*candidates.toTypedArray()))
        assertEquals((50L downTo 31L).toList(), f.search("kultur").map { it.osmRef!!.id })
        val equal = fixture(secondary = overpass(candidate(4, "Kültür"), candidate(2, "Kültür")))
        assertEquals(listOf(4L, 2L), equal.search("kultur").map { it.osmRef!!.id })
        val parsed = parsePartialNamePlaces(equal.secondary, PartialNameMatcher("kultur"))
        assertEquals(parsed, rankDirectionsPlaces(parsed, null))
    }

    @Test fun byteAndCandidateCapsRejectInsteadOfCachingTruncation() = runTest {
        for (body in listOf(" ".repeat(512_001), overpass(*(1L..51L).map { candidate(it, "Kültür") }.toTypedArray()))) {
            val f = fixture(primary("Unrelated"), body)
            assertEquals(listOf(1L), f.search("kultur").map { it.osmRef!!.id })
            assertTrue(f.overpassWrites.isEmpty()); assertTrue(f.budget.bytes <= 512_000)
        }
    }

    @Test fun cacheSeparatesEndpointOriginalQueryRoundedBoxAndExpiresAfter24Hours() = runTest {
        val f = fixture(secondary = saved("hamam-nearby"))
        val first = f.search("Kecioren Hamam")
        assertEquals(first, f.search("Kecioren Hamam")); assertEquals(2, f.requests.size)
        f.overpassEndpoint = "https://other.invalid/overpass"
        assertEquals(first, f.search("Kecioren Hamam")); assertEquals(3, f.requests.size)
        f.search("KECIOREN HAMAM"); assertEquals(5, f.requests.size)
        f.search("Kecioren Hamam", origin.copy(latitude = 40.2)); assertEquals(7, f.requests.size)
        assertTrue(f.overpassWrites.all { it.startsWith("directions-partial:v1:") })
        f.clock += 86_400_001
        f.search("Kecioren Hamam"); assertEquals(9, f.requests.size)
    }

    @Test fun endpointChangeAfterResponseOrCacheReadCancelsBeforePublishOrCache() = runTest {
        for (changePrimary in listOf(true, false)) {
            val f = fixture(secondary = saved("hamam-nearby"))
            f.afterResponse = { request ->
                if (changePrimary) f.nominatimEndpoint = "https://changed.invalid/search"
                else if (request.method == "POST") f.overpassEndpoint = "https://changed.invalid/overpass"
            }
            try { f.search("Kecioren Hamam"); fail("Stale endpoint must cancel") } catch (_: CancellationException) { }
            assertTrue(f.overpassWrites.isEmpty()); assertEquals(if (changePrimary) 1 else 2, f.requests.size)
        }
        val f = fixture(secondary = saved("hamam-nearby")); f.search("Kecioren Hamam")
        val writes = f.writes.size
        f.afterOverpassCacheRead = { f.overpassEndpoint = "https://changed.invalid/overpass" }
        try { f.search("Kecioren Hamam"); fail("Stale cache must cancel") } catch (_: CancellationException) { }
        assertEquals(2, f.requests.size); assertEquals(writes, f.writes.size)
    }

    @Test fun cancelledPrimarySecondaryOrCacheReadCannotPublishOrCache() = runTest {
        for (cancelAt in listOf("GET", "POST", "CACHE")) {
            val f = fixture(secondary = saved("hamam-nearby"))
            if (cancelAt == "CACHE") f.search("Kecioren Hamam")
            val before = f.writes.size
            val job = Job(coroutineContext[Job])
            f.afterResponse = { if (it.method == cancelAt) job.cancel() }
            if (cancelAt == "CACHE") f.afterOverpassCacheRead = { job.cancel() }
            var published = false
            try { withContext(job) { f.search("Kecioren Hamam"); published = true }; fail("Must cancel") }
            catch (_: CancellationException) { } finally { job.cancel() }
            assertFalse(published)
            assertEquals(before + if (cancelAt == "POST") 1 else 0, f.writes.size)
            assertEquals(if (cancelAt == "GET") 1 else 2, f.requests.size)
        }
    }

    @Test fun generalTokensInitialsTurkishVariantsOneWordAndPunctuationMatch() {
        val cases = listOf("Kecioren Hamam" to "Keçiören Hamamı", "TOBB ET" to "TOBB Ekonomi ve Teknoloji Üniversitesi",
            "tobb etu" to "TOBB Ekonomi ile Teknoloji ve Üniversitesi", "ŞİŞLİ KULT" to "Şişli Kültür Merkezi",
            "hamam" to "Keçiören Hamamı", "ET" to "Ekonomi ve Teknoloji", "123" to "123 Market",
            "Ankara, kultur" to "Ankara Kent Kültür Merkezi")
        for ((query, name) in cases) assertTrue("$query / $name", PartialNameMatcher(query).matches(name))
        for ((query, name) in listOf("TOBB ET" to "Több út", "hamam" to "İlhamam Merkezi", "TOBB ZY" to "TOBB Üniversitesi"))
            assertFalse("$query / $name", PartialNameMatcher(query).matches(name))
        val box = requireNotNull(DirectionsViewbox.around(origin))
        val unsafe = "TOBB\");out meta;[name~\".*\"](1,2,3,4); ET"
        val ql = PartialNameMatcher(unsafe).overpassQuery(box)
        assertFalse(ql.contains("out meta;")); assertFalse(ql.contains("(1,2,3,4)"))
        assertEquals(3, ql.count { it == ';' })
        assertTrue(ql.contains("[timeout:12]")); assertTrue(ql.endsWith("out center tags 50;"))
        assertTrue(ql.contains("[maxsize:16777216]"))
        assertTrue(PartialNameMatcher("a ".repeat(100).trim()).overpassQuery(box).length < 2_000)
    }

    @Test fun serviceBackedLateResponseCannotRepopulateEditedUi() = runTest {
        val f = fixture(secondary = saved("hamam-nearby"))
        val completed = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val controller = OsmSearchController(backgroundScope, "Kecioren Hamam") { query ->
            try {
                withContext(NonCancellable) {
                    val result = f.search(query)
                    completed.complete(Unit); release.await(); result
                }
            } finally { finished.complete(Unit) }
        }
        controller.submit(); completed.await()
        controller.edit("TOBB ET"); release.complete(Unit); finished.await()
        assertEquals("TOBB ET", controller.state.value.query)
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.searched); assertFalse(controller.state.value.busy)
        assertEquals(2, f.requests.size)
    }

    @Test fun serverRegexUsesLiteralTurkishAlternationWithoutMultibyteCharacterClasses() {
        val box = requireNotNull(DirectionsViewbox.around(origin))
        val ql = PartialNameMatcher("Kecioren Hamam").overpassQuery(box)
        assertTrue(ql.contains("(c|C|ç|Ç)"))
        val valueRegex = ql.substringAfter("$\"~\"").substringBefore("\",i]")
        assertFalse(valueRegex.contains('[')); assertFalse(valueRegex.contains("\\u"))
    }

    @Test fun generatedCoordinatorQueriesComeFromProductionBuilderWithPublicOrigin() {
        val box = requireNotNull(DirectionsViewbox.around(origin))
        val output = File("build/partial-search/live-queries.json"); requireNotNull(output.parentFile).mkdirs()
        output.writeText(JSONObject().put("origin", "39.93,32.85 synthetic/public").put("viewbox", box.value)
            .put("Kecioren Hamam", PartialNameMatcher("Kecioren Hamam").overpassQuery(box))
            .put("TOBB ET", PartialNameMatcher("TOBB ET").overpassQuery(box)).toString(2))
        assertTrue(output.length() > 0)
    }

    private fun fixture(primary: String = "[]", secondary: String = "{\"elements\":[]}") = Fixture(temporary.newFolder(), primary, secondary)
    private fun saved(name: String) = javaClass.getResource("/partial-search/$name.json")!!.readText()

    private inner class Fixture(private val folder: File, val primary: String, val secondary: String) {
        var clock = 1_000_000_000L
        var nominatimEndpoint = "https://fixture.invalid/search"
        var overpassEndpoint = "https://fixture.invalid/overpass"
        var primaryStatus = 200; var secondaryStatus = 200
        var secondaryFailure: IOException? = null
        var continuation = "[]"
        var budget = OsmReadBudget()
        var locations = 0
        val requests = mutableListOf<Request>()
        val writes = mutableListOf<String>()
        val overpassWrites get() = writes.filter { it.startsWith("directions-partial:") }
        var afterResponse: ((Request) -> Unit)? = null
        var afterOverpassCacheRead: (() -> Unit)? = null
        private val limits = mutableMapOf<String, Long>()
        private fun file(key: String) = File(folder, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })
        private fun read(key: String): String? = readValidCachedJson(file(key), clock, 86_400_000).also {
            if (key.startsWith("directions-partial:")) afterOverpassCacheRead?.invoke()
        }
        private fun write(key: String, json: String) { file(key).writeText(json); file(key).setLastModified(clock); writes += key }
        private fun execute(request: Request): Response {
            requests += request
            val secondaryRequest = request.method == "POST"
            if (secondaryRequest) secondaryFailure?.let { throw it }
            val body = if (secondaryRequest) secondary else if (request.url.queryParameter("exclude_place_ids") != null) continuation else primary
            val code = if (secondaryRequest) secondaryStatus else primaryStatus
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .header("Retry-After", "60").body(body.toResponseBody()).build().also { afterResponse?.invoke(request) }
        }
        private val overpass = OverpassRead({ budget }, { budget = it; true }, ::execute, { clock })
        private val nearby = PartialNameOverpassSearch(Mutex(), { overpassEndpoint }, ::read, ::write, overpass::query)
        private val nominatim = NominatimSearch(Mutex(), { nominatimEndpoint }, ::read, ::write,
            { limits[it] ?: 0 }, { key, value -> limits[key] = value; true }, ::execute, { clock }, { clock += it })
        private val directions = DirectionsPlaceSearch(nominatim, nearby)
        suspend fun search(query: String, location: WeatherCoordinate? = origin) = directions.search(query) { locations++; location }
        fun ql() = (requests.last().body as FormBody).value(0)
    }

    private fun primary(name: String, id: Long = 1) = JSONArray().put(JSONObject().put("name", name)
        .put("osm_type", "node").put("osm_id", id).put("lat", "39.9").put("lon", "32.8")).toString()
    private fun candidate(id: Long, name: String, alias: Pair<String, String>? = null, lat: Double = 39.93, lon: Double = 32.85): String {
        val tags = JSONObject().put("name", name); alias?.let { tags.put(it.first, it.second) }
        return JSONObject().put("type", "node").put("id", id).put("lat", lat).put("lon", lon).put("tags", tags).toString()
    }
    private fun overpass(vararg items: String) = JSONObject().put("elements", JSONArray(items.joinToString(",", "[", "]"))).toString()
}
