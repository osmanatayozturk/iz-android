package org.iz.navigation.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.iz.navigation.data.OsmType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class NominatimSearchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun generalQueryFindsBranchWithOneCanonicalExclusionRequest() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "Head office"), place(2, "Bus stop", "node")))
            f.enqueue(page(place(3, "Branch")))
            val results = f.search.search("  atlas   institute  ")
            assertEquals(listOf("Head office", "Bus stop", "Branch"), results.map { it.name })
            assertEquals(2, f.server.requestCount)
            val first = f.server.takeRequest()
            val second = f.server.takeRequest()
            assertEquals("atlas institute", first.requestUrl!!.queryParameter("q"))
            assertNull(first.requestUrl!!.queryParameter("exclude_place_ids"))
            assertEquals("N2,W1", second.requestUrl!!.queryParameter("exclude_place_ids"))
            assertEquals("atlas institute", second.requestUrl!!.queryParameter("q"))
            for (request in listOf(first, second)) {
                assertEquals("10", request.requestUrl!!.queryParameter("limit"))
                assertEquals("jsonv2", request.requestUrl!!.queryParameter("format"))
                assertEquals("0", request.requestUrl!!.queryParameter("addressdetails"))
                assertEquals("tr", request.getHeader("Accept-Language"))
                assertTrue(request.getHeader("User-Agent")!!.startsWith("Iz/"))
            }
            assertEquals(listOf(1_000L, 2_000L), f.requestTimes)
        }
    }

    @Test fun oldSinglePageCacheCannotHideBranchAfterUpgrade() = runTest {
        fixture().use { f ->
            f.cache("search:" + f.endpoint + ":atlas", page(place(90, "Stale")))
            f.enqueue(page(place(1, "Head office")))
            f.enqueue(page(place(3, "Branch")))
            assertEquals(listOf(1L, 3L), f.search.search("atlas").map { it.osmRef!!.id })
            assertEquals(2, f.server.requestCount)
            assertEquals(listOf(1L, 3L), f.search.search(" ATLAS ").map { it.osmRef!!.id })
            assertEquals("Both successful pages are reused", 2, f.server.requestCount)
        }
    }

    @Test fun cancellationAfterResponseCannotStoreCacheOrStartContinuation() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "Head office")))
            f.enqueue(page(place(3, "Branch")))
            val job = Job(coroutineContext[Job])
            f.afterResponse = { job.cancel() }
            try {
                withContext(job) { f.search.search("atlas") }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { } finally { job.cancel() }
            assertTrue("Canceled response must not become cache state", f.writtenKeys.isEmpty())
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun secondaryFailurePreservesFirstPageAndDoesNotCacheFailureAsSuccess() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "Head office")))
            f.server.enqueue(MockResponse().setResponseCode(500).setBody("unavailable"))
            assertEquals(listOf(1L), f.search.search("atlas").map { it.osmRef!!.id })
            assertEquals(2, f.server.requestCount)
            assertEquals(1, f.writtenKeys.size)
            f.enqueue(page(place(3, "Branch")))
            assertEquals(listOf(1L, 3L), f.search.search("atlas").map { it.osmRef!!.id })
            assertEquals("First page came from cache, only continuation retried", 3, f.server.requestCount)
        }
    }

    @Test fun acquiredBranchesBeyondFirstTenAreRetained() = runTest {
        fixture().use { f ->
            f.enqueue(page(*(1L..9L).map { place(it, "Office " + it) }.toTypedArray()))
            f.enqueue(page(*(10L..19L).map { place(it, "Office " + it) }.toTypedArray()))
            assertEquals((1L..19L).toList(), f.search.search("atlas").map { it.osmRef!!.id })
            assertEquals(2, f.server.requestCount)
        }
    }


    @Test fun emptyInvalidReferenceLessAndFullFirstPagesDoNotExpand() = runTest {
        val cases = listOf(
            "[]" to 0,
            page("""{"name":"Invalid","lat":"999","lon":"30","osm_type":"way","osm_id":1}""") to 0,
            page(place(0, "Address without ref")) to 1,
            page(*(1L..10L).map { place(it, "Office " + it) }.toTypedArray()) to 10,
        )
        for ((body, size) in cases) fixture().use { f ->
            f.enqueue(body)
            assertEquals(size, f.search.search("atlas").size)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun dedupUsesOsmTypeAndIdAndDoesNotCollapseDifferentUnreferencedPlaces() = runTest {
        fixture().use { f ->
            val noRefA = place(0, "Address", lat = 40.0)
            val noRefB = place(0, "Address", lat = 41.0)
            val noRefC = place(0, "Other address", lat = 40.0)
            f.enqueue(page(place(1, "Same name"), noRefA))
            f.enqueue(page(place(1, "Duplicate way"), place(1, "Same name", "node"), place(2, "Same name"),
                noRefA, noRefB, noRefC))
            val result = f.search.search("atlas")
            assertEquals(6, result.size)
            assertEquals(listOf(1L, 1L, 2L), result.mapNotNull { it.osmRef?.id })
            assertEquals(listOf(OsmType.WAY, OsmType.NODE, OsmType.WAY), result.mapNotNull { it.osmRef?.type })
            assertEquals(listOf("Address", "Address", "Other address"), result.filter { it.osmRef == null }.map { it.name })
            assertEquals(listOf(40.0, 41.0, 40.0), result.filter { it.osmRef == null }.map { it.latitude })
        }
    }

    @Test fun secondPageIsBoundedEvenIfProviderIgnoresLimitAndNeverTriggersThirdRequest() = runTest {
        fixture().use { f ->
            f.enqueue(page(*(1L..9L).map { place(it, "First " + it) }.toTypedArray()))
            f.enqueue(page(*(10L..29L).map { place(it, "Branch " + it) }.toTypedArray()))
            assertEquals((1L..19L).toList(), f.search.search("atlas").map { it.osmRef!!.id })
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun successfulEmptyContinuationIsCachedAndDoesNotTriggerMorePaging() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "Head")))
            f.enqueue("[]")
            assertEquals(listOf("Head"), f.search.search("atlas").map { it.name })
            assertEquals(listOf("Head"), f.search.search("atlas").map { it.name })
            assertEquals(2, f.server.requestCount)
            assertEquals(2, f.writtenKeys.size)
        }
    }

    @Test fun malformedAndOversizedContinuationPreserveFirstPageWithoutSuccessfulCache() = runTest {
        for (body in listOf("[broken", page(place(3, "x".repeat(512_001))))) fixture().use { f ->
            f.enqueue(page(place(1, "Head")))
            f.enqueue(body)
            assertEquals(listOf(1L), f.search.search("atlas").map { it.osmRef!!.id })
            assertEquals(2, f.server.requestCount)
            assertEquals(1, f.writtenKeys.size)
        }
    }

    @Test fun secondaryTimeoutPreservesFirstPageAndIsNotCached() = runTest {
        fixture().use { f ->
            f.client = f.client.newBuilder().readTimeout(50, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            f.enqueue(page(place(1, "Head")))
            f.server.enqueue(MockResponse().setBody(page(place(3, "Branch")))
                .setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertEquals(listOf(1L), f.search.search("atlas").map { it.osmRef!!.id })
            assertEquals(2, f.server.requestCount)
            assertEquals(1, f.writtenKeys.size)
        }
    }

    @Test fun primaryFailurePropagatesWithoutContinuationOrCache() = runTest {
        for (status in listOf(500, 429, 406, 503)) fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0").setBody("busy"))
            f.enqueue(page(place(3, "Unexpected retry")))
            try {
                f.search.search("atlas")
                fail("Primary HTTP failure must reach caller")
            } catch (_: OsmServiceException) { }
            assertEquals(1, f.server.requestCount)
            assertTrue(f.writtenKeys.isEmpty())
        }
    }

    @Test fun malformedPrimaryResponseIsNotCachedOrExpanded() = runTest {
        fixture().use { f ->
            f.enqueue("[broken")
            try {
                f.search.search("atlas")
                fail("Invalid primary JSON must reach caller")
            } catch (_: org.json.JSONException) { }
            assertEquals(1, f.server.requestCount)
            assertTrue(f.writtenKeys.isEmpty())
        }
    }

    @Test fun secondaryCooldownPersistsAcrossSearchInstancesAndKeepsCachedFirstPage() = runTest {
        for (status in listOf(429, 406)) fixture().use { f ->
            f.enqueue(page(place(1, "Head")))
            f.server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "60").setBody("busy"))
            assertEquals(listOf(1L), f.search.search("atlas").map { it.osmRef!!.id })
            val timeAtCooldown = 1_000L + testScheduler.currentTime
            assertEquals(timeAtCooldown + 60_000, f.persisted["nominatim_cooldown"])
            val nextInstance = f.newSearch()
            assertEquals(listOf(1L), nextInstance.search("atlas").map { it.osmRef!!.id })
            try {
                nextInstance.search("different")
                fail("Persisted cooldown must block uncached primary request")
            } catch (_: OsmServiceException) { }
            assertEquals(2, f.server.requestCount)
            testScheduler.advanceTimeBy(60_001)
            f.enqueue(page(place(3, "Branch")))
            assertEquals(listOf(1L, 3L), nextInstance.search("atlas").map { it.osmRef!!.id })
            assertEquals(3, f.server.requestCount)
        }
    }

    @Test fun secondaryServiceUnavailableDoesNotReplayOutsideTheGate() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "Head")))
            f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
            f.enqueue(page(place(3, "Unexpected retry")))
            assertEquals(listOf(1L), f.search.search("atlas").map { it.osmRef!!.id })
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun everyPageAcrossConcurrentSearchInstancesUsesSharedRateGate() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "First")))
            f.enqueue(page(place(2, "First branch")))
            f.enqueue(page(place(3, "Second")))
            f.enqueue(page(place(4, "Second branch")))
            val another = f.newSearch()
            val first = async { f.search.search("atlas") }
            val second = async { another.search("beacon") }
            assertEquals(listOf(1L, 2L), first.await().map { it.osmRef!!.id })
            assertEquals(listOf(3L, 4L), second.await().map { it.osmRef!!.id })
            assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), f.requestTimes)
        }
    }

    @Test fun endpointAndQueryCachesCannotLeakIntoAnotherSearch() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "First")))
            f.enqueue("[]")
            f.search.search("atlas")
            f.endpoint = f.server.url("/other-search?countrycodes=tr").toString()
            f.enqueue(page(place(3, "Other endpoint")))
            f.enqueue("[]")
            assertEquals(listOf(3L), f.search.search("atlas").map { it.osmRef!!.id })
            f.enqueue(page(place(4, "Other query")))
            f.enqueue("[]")
            assertEquals(listOf(4L), f.search.search("beacon").map { it.osmRef!!.id })
            assertEquals(6, f.server.requestCount)
        }
    }

    @Test fun exclusionCacheIsCanonicalForDifferentOrderingOfSameFirstPageReferences() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "Head"), place(2, "Stop", "node")))
            f.enqueue(page(place(3, "Branch")))
            f.search.search("atlas")
            f.cache(f.writtenKeys.first(), page(place(2, "Stop", "node"), place(1, "Head")))
            assertEquals(listOf(2L, 1L, 3L), f.search.search("atlas").map { it.osmRef!!.id })
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun cancellationAfterCachedPageCannotReturnResultsOrStartContinuation() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "Head")))
            f.enqueue(page(place(3, "Branch")))
            f.search.search("atlas")
            var returned = false
            val writes = f.writtenKeys.size
            val requests = f.server.requestCount
            val job = Job(coroutineContext[Job])
            f.afterCacheRead = { job.cancel() }
            try {
                withContext(job) { f.search.search("atlas"); returned = true }
                fail("Cached result cannot bypass cancellation")
            } catch (_: CancellationException) { } finally { job.cancel() }
            assertFalse("Canceled cache read must not return inside the caller", returned)
            assertEquals(requests, f.server.requestCount)
            assertEquals(writes, f.writtenKeys.size)
        }
    }

    @Test fun cancellationDuringRateWaitDoesNotRecordOrSendRequest() = runTest {
        fixture().use { f ->
            f.persisted["nominatim_last_request"] = 1_000
            val job = async { f.search.search("atlas") }
            runCurrent()
            job.cancel()
            job.join()
            assertEquals(0, f.server.requestCount)
            assertEquals(1_000L, f.persisted["nominatim_last_request"])
            assertTrue(f.writtenKeys.isEmpty())
        }
    }

    @Test fun cancellationAtSecondaryResponseIsNotSwallowedAsFallbackOrCache() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "Head")))
            f.enqueue(page(place(3, "Branch")))
            val job = Job(coroutineContext[Job])
            f.afterResponse = { if (f.server.requestCount == 2) job.cancel() }
            var returned = false
            try {
                withContext(job) { f.search.search("atlas"); returned = true }
                fail("Secondary cancellation must propagate, not return first page")
            } catch (_: CancellationException) { } finally { job.cancel() }
            assertFalse("Cancellation must not return fallback inside the caller", returned)
            assertEquals(2, f.server.requestCount)
            assertEquals(1, f.writtenKeys.size)
        }
    }

    @Test fun canceledRateLimitResponseDoesNotProduceCooldownState() = runTest {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            val job = Job(coroutineContext[Job])
            f.afterResponse = { job.cancel() }
            try {
                withContext(job) { f.search.search("atlas") }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { } finally { job.cancel() }
            assertFalse(f.persisted.containsKey("nominatim_cooldown"))
            assertTrue(f.writtenKeys.isEmpty())
        }
    }

    @Test fun bothPagesAndTheirV2CachesKeepDisplayNamesWithoutChangingIdentity() = runTest {
        fixture().use { f ->
            f.enqueue(page(place(1, "Atlas"), place(2, "Stop", "node")))
            f.enqueue(page(place(1, "Atlas"), place(3, "Atlas")))
            val first = f.search.search("atlas")
            assertEquals(listOf(1L, 2L, 3L), first.map { it.osmRef!!.id })
            assertEquals(listOf("Atlas, Example", "Stop, Example", "Atlas, Example"), first.map { it.displayName })
            assertEquals(first, f.newSearch().search(" ATLAS "))
            assertEquals(2, f.server.requestCount)
            assertTrue(f.writtenKeys.all { it.startsWith("search-v2:") && it.contains("addressdetails=0") })
            assertEquals(2, f.writtenKeys.size)
        }
    }
    private fun TestScope.fixture() = Fixture(this, temporary.newFolder())

    private class Fixture(private val scope: TestScope, private val folder: File) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        var endpoint = server.url("/search").toString()
        val persisted = mutableMapOf<String, Long>()
        val writtenKeys = mutableListOf<String>()
        val requestTimes = mutableListOf<Long>()
        var afterResponse: (() -> Unit)? = null
        var afterCacheRead: (() -> Unit)? = null
        private val mutex = Mutex()
        var client: OkHttpClient = OsmPlaces::class.java.getDeclaredField("client")
            .apply { isAccessible = true }.get(null) as OkHttpClient
        val search = newSearch()
        fun newSearch() = NominatimSearch(
            mutex = mutex,
            endpoint = { endpoint },
            readCached = { key ->
                readValidCachedPlaces(file(key), System.currentTimeMillis(), 86_400_000, ::parseNominatimPlaces)
                    .also { afterCacheRead?.invoke() }
            },
            writeCache = { key, json -> cache(key, json); writtenKeys += key },
            readLong = { persisted[it] ?: 0L },
            writeLong = { key, value -> persisted[key] = value; true },
            execute = { request ->
                requestTimes += 1_000L + scope.testScheduler.currentTime
                client.newCall(request).execute().also { afterResponse?.invoke() }
            },
            now = { 1_000L + scope.testScheduler.currentTime },
        )
        fun enqueue(body: String) { server.enqueue(MockResponse().setBody(body)) }
        fun cache(key: String, body: String) { file(key).writeText(body) }
        private fun file(key: String): File {
            val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
            return File(folder, name)
        }
        override fun close() { server.close() }
    }

    private fun page(vararg places: String) = places.joinToString(",", "[", "]")
    private fun place(id: Long, name: String, type: String = "way", lat: Double = 40.0, lon: Double = 30.0) =
        """{"osm_type":"$type","osm_id":$id,"name":"$name","display_name":"$name, Example","lat":"$lat","lon":"$lon"}"""
}