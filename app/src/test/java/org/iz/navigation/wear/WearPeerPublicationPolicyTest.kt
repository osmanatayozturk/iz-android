package org.iz.navigation.wear

import kotlinx.coroutines.runBlocking
import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test

class WearPeerPublicationPolicyTest {
    @Test fun versionSixGetsSameDeferredSnapshotAndNeverRunsLegacyProducer() = runBlocking {
        val state = deferred()
        val versions = wearCompatibilityVersions(mapOf("six" to 6), mapOf("six" to 2), 7)
        var legacyCalls = 0
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        publishCompatibleWearSnapshots(state, versions, 7,
            legacySnapshot = { legacyCalls++; error("A v6 peer must never request raw legacy metrics") },
            publish = { snapshot, version ->
                assertSame("Reuse the exact deferred object, not a legacy reconstruction", state, snapshot)
                sent += version to WearProtocol.encodeSnapshot(snapshot, version)
            })
        assertEquals(listOf(6), sent.map { it.first })
        assertEquals(0, legacyCalls)
        val decoded = WearProtocol.decodeSnapshot(sent.single().second)!!
        assertTrue(decoded.statsDeferred)
        assertEquals(state.statisticsStatus, decoded.statisticsStatus)
        assertNull(decoded.currentSpeedKmh)
        assertNull(decoded.averageSpeedKmh)
        assertNull(decoded.maxSpeedKmh)
        assertEquals(6, WearProtocol.frameVersion(sent.single().second))
    }

    @Test fun mixedModernAndLegacyPeersOnlyPayLegacyOnceAndKeepStatesSeparate() = runBlocking {
        val current = deferred()
        val legacy = WearSnapshot(generatedAt = 1_000_000, distanceMeters = 123.0, elapsedMillis = 30_000)
        val versions = wearCompatibilityVersions(mapOf("six" to 6, "seven" to 7, "two" to 2, "five" to 5), emptyMap(), 7)
        var legacyCalls = 0
        val sent = mutableListOf<Pair<Int, WearSnapshot>>()
        publishCompatibleWearSnapshots(current, versions, 7, { legacyCalls++; legacy }, { snapshot, version -> sent += version to snapshot })
        assertEquals(listOf(6, 2, 5), sent.map { it.first })
        assertSame(current, sent.first().second)
        assertSame(legacy, sent[1].second)
        assertSame(legacy, sent[2].second)
        assertEquals(1, legacyCalls)
    }

    @Test fun v6AndV7OlderAdvertisementsCannotCauseLegacyPublication() {
        val highest = highestReachableWatchVersions(listOf(
            7 to listOf("seven"), 6 to listOf("six", "seven"),
            1 to listOf("six", "seven"), 5 to listOf("six", "seven"),
        ), 7, 32)
        assertEquals(mapOf("seven" to 7, "six" to 6), highest)
        val versions = wearCompatibilityVersions(highest, mapOf("seven" to 1, "six" to 4), 7)
        assertFalse(versions.any { it in 1..5 })
    }

    @Test fun freshHighestCommandVersionPreventsCapabilityDowngrade() {
        assertTrue(wearCompatibilityVersions(mapOf("peer" to 1), mapOf("peer" to 7), 7).isEmpty())
        assertTrue(wearCompatibilityVersions(mapOf("peer" to 7), mapOf("peer" to 1), 7).isEmpty())
        assertEquals(listOf(2, 5), wearCompatibilityVersions(mapOf("a" to 2), mapOf("b" to 5), 7))
    }

    @Test fun reachablePeerBoundIsExactAndOverflowFailsClosed() {
        val full = (1..32).map { "peer-$it" }
        assertEquals(32, highestReachableWatchVersions(listOf(1 to full, 7 to full), 7, 32).size)
        assertTrue(highestReachableWatchVersions(listOf(1 to full, 7 to listOf("overflow")), 7, 32).isEmpty())
    }

    @Test fun duplicateAndUnsupportedAdvertisementsDoNotExpandPeerOrChannelBudget() {
        assertEquals(mapOf("one" to 7), highestReachableWatchVersions(
            listOf(1 to listOf("one", "one"), 7 to listOf("one"), 8 to listOf("future")), 7, 1))
        assertTrue(wearCompatibilityVersions(mapOf("invalid" to 8), mapOf("negative" to -1), 7).isEmpty())
    }

    @Test fun currentOnlyPeersDoNotRebuildOrRepublishCompatibilityState() = runBlocking {
        var legacyCalls = 0
        val sent = mutableListOf<Int>()
        val versions = wearCompatibilityVersions(mapOf("seven" to 7), emptyMap(), 7)
        publishCompatibleWearSnapshots(deferred(), versions, 7,
            { legacyCalls++; error("Current-only peer") }, { _, version -> sent += version })
        assertTrue(sent.isEmpty())
        assertEquals(0, legacyCalls)
    }

    private fun deferred() = WearSnapshot(generatedAt = 1_000_000, statsDeferred = true,
        statisticsStatus = "Statistics will be prepared after the journey")
}
