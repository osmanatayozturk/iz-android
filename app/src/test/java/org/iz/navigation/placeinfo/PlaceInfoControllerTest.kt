package org.iz.navigation.placeinfo

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PlaceInfoControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 100_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private fun key(id: Long = 42, endpoint: String = "https://api.openstreetmap.org/api/0.6") = PlaceInfoKey(endpoint, OsmRef(OsmType.NODE,id))
    private fun snapshot(key: PlaceInfoKey = key(), fetched: Long = now, version: Long = 3) = PublicPlaceSnapshot(key,version,mapOf("name" to "Public"),fetched)

    @Test fun freshOpenUsesCacheAndExplicitRefreshReadsExactlyOnce() = runTest {
        val cache = PublicPlaceCache(temp.newFolder(),clock); cache.put(snapshot())
        var calls = 0
        val controller = PlaceInfoController(this, PlaceInfoRepository(PlaceInfoReader { calls++; PlaceInfoRead.Found(snapshot(version = 4)) },cache,clock))
        controller.open(key())?.join()
        assertEquals(snapshot(),controller.state.value.snapshot); assertEquals(0,calls)
        controller.refresh()?.join()
        assertEquals(1,calls); assertEquals(4L,controller.state.value.snapshot?.version)
    }

    @Test fun exactly24HoursAndFutureTimestampAreStaleRatherThanFresh() = runTest {
        for (fetched in listOf(now-86_400_000,now+1)) {
            val cache = PublicPlaceCache(temp.newFolder(),clock); cache.put(snapshot(fetched=fetched))
            var calls=0
            val controller=PlaceInfoController(this,PlaceInfoRepository(PlaceInfoReader { calls++; PlaceInfoRead.Found(snapshot()) },cache,clock))
            controller.open(key())?.join(); assertEquals(1,calls); assertFalse(controller.state.value.stale)
        }
    }

    @Test fun staleDataIsVisibleDuringReadAndNetworkFailurePreservesItsMetadata() = runTest {
        val old=snapshot(fetched=now-86_400_001); val cache=PublicPlaceCache(temp.newFolder(),clock); cache.put(old)
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val controller=PlaceInfoController(this,PlaceInfoRepository(PlaceInfoReader { entered.complete(Unit); release.await(); error("network") },cache,clock))
        val job=controller.open(key()); withContext(Dispatchers.Default) { withTimeout(5000) { entered.await() } }
        assertEquals(old,controller.state.value.snapshot); assertTrue(controller.state.value.loading); assertTrue(controller.state.value.stale)
        release.complete(Unit); job?.join()
        assertEquals(old,controller.state.value.snapshot); assertTrue(controller.state.value.failed); assertFalse(controller.state.value.loading)
        assertEquals(old,cache.cached(key())?.snapshot)
    }

    @Test fun unavailableIsDistinctFromErrorAndRetainsHistoricalSnapshot() = runTest {
        val old=snapshot(fetched=0); val cache=PublicPlaceCache(temp.newFolder(),clock); cache.put(old)
        val controller=PlaceInfoController(this,PlaceInfoRepository(PlaceInfoReader { PlaceInfoRead.Unavailable(it,410) },cache,clock))
        controller.open(key())?.join()
        assertEquals(410,controller.state.value.unavailableStatus); assertFalse(controller.state.value.failed)
        assertEquals(old,controller.state.value.snapshot); assertTrue(controller.state.value.stale)
        assertEquals(410,cache.cached(key())?.unavailableStatus)
    }

    @Test fun endpointChangeAndCloseIgnoreLateResultsAndDoNotPoisonCache() = runTest {
        val cache=PublicPlaceCache(temp.newFolder(),clock)
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val other=key(endpoint="https://other.example/api/0.6")
        val controller=PlaceInfoController(this,PlaceInfoRepository(PlaceInfoReader {
            if(it==key()) withContext(NonCancellable) { entered.complete(Unit); release.await(); PlaceInfoRead.Found(snapshot()) }
            else PlaceInfoRead.Found(snapshot(other))
        },cache,clock))
        val first=controller.open(key()); withContext(Dispatchers.Default) { withTimeout(5000) { entered.await() } }
        controller.open(other)?.join(); release.complete(Unit); first?.join()
        assertEquals(other,controller.state.value.key); assertEquals(other,controller.state.value.snapshot?.key)
        assertNull(cache.cached(key())); assertNotNull(cache.cached(other))
        controller.close(); assertNull(controller.state.value.key); assertNull(controller.refresh())
    }

    @Test fun successfulContributionInvalidatesClosedKeyAndRefreshesOnlyCurrentExactKey() = runTest {
        val cache=PublicPlaceCache(temp.newFolder(),clock); cache.put(snapshot())
        var calls=0
        val controller=PlaceInfoController(this,PlaceInfoRepository(PlaceInfoReader { calls++; PlaceInfoRead.Found(snapshot(it,version=4)) },cache,clock))
        controller.contributionSucceeded(key())?.join(); assertNull(cache.cached(key())); assertEquals(0,calls)
        controller.open(key())?.join(); assertEquals(1,calls)
        controller.contributionSucceeded(key())?.join(); assertEquals(2,calls); assertEquals(4L,controller.state.value.snapshot?.version)
        controller.close(); controller.contributionSucceeded(key())?.join(); assertNull(cache.cached(key())); assertEquals(2,calls)
    }

    @Test fun closeDuringCancellationIgnoringReadPreventsBothDisplayAndDiskPublication() = runTest {
        val cache=PublicPlaceCache(temp.newFolder(),clock)
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val controller=PlaceInfoController(this,PlaceInfoRepository(PlaceInfoReader {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); PlaceInfoRead.Found(snapshot()) }
        },cache,clock))
        val job=controller.open(key())
        withContext(Dispatchers.Default) { withTimeout(5000) { entered.await() } }
        controller.close(); release.complete(Unit); job?.join()
        assertEquals(PlaceInfoState(),controller.state.value); assertNull(cache.cached(key()))
    }

    @Test fun existingSuccessfulReceiptReconcilesOlderFreshCacheButDoesNotRefetchMatchingOrNewerVersion() = runTest {
        for (cachedVersion in listOf(3L,4L,5L)) {
            val cache=PublicPlaceCache(temp.newFolder(),clock)
            cache.put(snapshot(version=cachedVersion))
            var calls=0
            // A new controller represents reopening/process restart with the already persisted SENT/v4 receipt.
            val reopened=PlaceInfoController(this,PlaceInfoRepository(PlaceInfoReader {
                calls++; PlaceInfoRead.Found(snapshot(version=4))
            },cache,clock))
            reopened.open(key(),minimumVersion=4)?.join()
            assertEquals("cachedVersion=$cachedVersion",if(cachedVersion<4) 1 else 0,calls)
            assertEquals(maxOf(4L,cachedVersion),reopened.state.value.snapshot?.version)
            assertFalse(reopened.state.value.stale)
        }
    }

    @Test fun knownOlderCacheRemainsHistoricalWhenRefreshFailsOrServerHasNotReachedConfirmedVersion() = runTest {
        for (offline in listOf(true,false)) {
            val cache=PublicPlaceCache(temp.newFolder(),clock); val old=snapshot(version=3); cache.put(old)
            val reopened=PlaceInfoController(this,PlaceInfoRepository(PlaceInfoReader {
                if(offline) error("offline") else PlaceInfoRead.Found(old)
            },cache,clock))
            reopened.open(key(),minimumVersion=4)?.join()
            assertEquals(old,reopened.state.value.snapshot); assertTrue(reopened.state.value.stale)
            assertTrue(reopened.state.value.failed); assertFalse(reopened.state.value.loading)
            assertEquals(old,cache.cached(key())?.snapshot)
        }
    }
}
