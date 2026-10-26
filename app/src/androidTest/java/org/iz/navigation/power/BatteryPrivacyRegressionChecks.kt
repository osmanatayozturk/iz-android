package org.iz.navigation.power

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.iz.navigation.IzApplication
import org.iz.navigation.data.*
import org.iz.navigation.tracking.TrackingService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import java.util.UUID

/** Private source design for IZ-BAT-SEC-001. Not compiled/run or approved for shared source. */
@RunWith(AndroidJUnit4::class)
class BatteryPrivacyRegressionChecks {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun healthClearPurgesPreparedSecretsWhileActiveJourneyBlocksAnalysis() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            val keptRaw = f.repository.getJourney(f.affected.id)
            val keptPoints = f.repository.journeyPoints(f.affected.id)
            f.seedAtomicSiblingsAndOrphanFingerprint()
            val trails = f.trailFamily.associate { it.name to digest(it.readBytes()) }
            val other = f.unaffectedFiles.associate { it.name to digest(it.readBytes()) }
            (context.applicationContext as IzApplication).healthManager.clearLocalData()
            f.assertPurged(healthOnly = true)
            assertTrue(f.repository.snapshot().healthSamples.isEmpty())
            assertTrue(f.repository.snapshot().watchHealthSamples.isEmpty())
            assertTrue(f.repository.snapshot().watchHealthSessions.isEmpty())
            assertEquals(keptRaw, f.repository.getJourney(f.affected.id))
            assertEquals(keptPoints, f.repository.journeyPoints(f.affected.id))
            f.trailFamily.forEach { assertEquals(trails[it.name], digest(it.readBytes())) }
            f.assertOtherPreserved(other)
            f.assertNoStaleRepublishWhileIneligible(healthOnly = true)
        }
    }

    @Test fun journeyDeletionPurgesAllFingerprintsAndPreservesOtherPreparedJourney() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            val kept = f.unaffectedFiles.associate { it.name to digest(it.readBytes()) }
            f.seedAtomicSiblingsAndOrphanFingerprint()
            f.repository.deleteJourney(f.affected.id)
            f.assertPurged(healthOnly = false)
            assertNull(f.repository.getJourney(f.affected.id))
            assertTrue(f.repository.journeyPoints(f.affected.id).isEmpty())
            assertTrue(f.repository.snapshot().healthSamples.none { it.journeyId == f.affected.id })
            f.assertOtherPreserved(kept)
            f.assertNoStaleRepublishWhileIneligible(healthOnly = false)
        }
    }

    @Test fun failedHealthPurgeThrowsAndRollsBackRawRoomDeletion() = runBlocking(Dispatchers.IO) {
        fixture { f ->
            val raw = f.repository.snapshot()
            val kept = f.unaffectedFiles.associate { it.name to digest(it.readBytes()) }
            val obstruction = File(f.root, "heart-${AnalysisCache.digest(f.affected.id)}-${f.entry.fingerprint}.json.new")
            check(obstruction.canonicalFile.parentFile == f.root && !obstruction.exists())
            check(obstruction.mkdir())
            val sentinel = File(obstruction, "owned-synthetic-sentinel")
            sentinel.writeText("qa-sec001")
            try {
                val error = try {
                    withTimeout(5_000) { (context.applicationContext as IzApplication).healthManager.clearLocalData() }
                    null
                } catch (io: IOException) { io }
                assertNotNull("Checked deletion failure must not report clear success", error)
                assertTrue(sentinel.isFile)
                val after = f.repository.snapshot()
                assertEquals(raw.journeys, after.journeys)
                assertEquals(raw.points, after.points)
                assertEquals(raw.healthSamples, after.healthSamples)
                assertEquals(raw.watchHealthSamples, after.watchHealthSamples)
                assertEquals(raw.watchHealthSessions, after.watchHealthSessions)
                assertTrue(after.healthSamples.any { it.sourceId == f.marker })
                assertFalse("Failed purge must revoke the live sensitive entry", f.manager.state.value.entries.containsKey(f.affected.id))
                assertEquals("", f.manager.state.value.cacheRevision)
                assertTrue(f.manager.state.value.pending)
                assertFalse(f.manager.canProcessNow())
                f.assertOtherPreserved(kept)
            } finally {
                check(sentinel.delete())
                check(obstruction.delete())
            }
        }
    }

    /** Synthetic cache monitor + callback invalidation only; not real WorkManager/generation scheduling. */
    @Test fun isolatedCacheWriterSerializesWithPurgeAndRejectsInvalidatedCallback() {
        assertEquals("org.iz.navigation.batterycheck", context.packageName)
        val directory = File(context.cacheDir, "qa-sec001-cache-${UUID.randomUUID()}").canonicalFile
        check(directory.parentFile == context.cacheDir.canonicalFile && !directory.exists())
        check(directory.mkdir())
        val cache = AnalysisCache(directory)
        val id = "qa-sec001-writer-${UUID.randomUUID()}"
        val entry = AnalysisEntry("a".repeat(64), JourneyStats(0.0, 1, 0, 0, 0, 1, null, null, null),
            JourneyHealthSummary(id, heartRateMeanBpm = 137.0), JourneyHealthSummary(id),
            Journey(id = id, transport = Transport.WALK, startedAt = 1, endedAt = 2))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val purgeAttempt = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        val valid = AtomicBoolean(true)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit<Unit> {
                cache.saveEntry(id, entry) {
                    if (pauseOnce.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    if (!valid.get()) throw CancellationException("synthetic invalidated writer")
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val purge = executor.submit<Set<String>> { purgeAttempt.countDown(); cache.purge(setOf(id)) }
            assertTrue(purgeAttempt.await(5, TimeUnit.SECONDS))
            release.countDown()
            writer.get(5, TimeUnit.SECONDS)
            assertTrue(purge.get(5, TimeUnit.SECONDS).contains(AnalysisCache.digest(id)))
            val file = File(directory, "entry-${AnalysisCache.digest(id)}.json")
            listOf("", ".bak", ".new").forEach { assertFalse(File(file.path + it).exists()) }
            valid.set(false)
            val stale = executor.submit<Unit> {
                cache.saveEntry(id, entry) { if (!valid.get()) throw CancellationException("synthetic invalidated writer") }
            }
            val rejected = try { stale.get(5, TimeUnit.SECONDS); null } catch (error: ExecutionException) { error.cause }
            assertTrue(rejected is CancellationException)
            listOf("", ".bak", ".new").forEach { assertFalse(File(file.path + it).exists()) }
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
            // This unique isolated directory contains only this test's own synthetic files.
            directory.listFiles().orEmpty().forEach { check(it.isFile && it.delete()) }
            check(directory.delete())
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        assertEquals("org.iz.navigation.batterycheck", context.packageName)
        assumeTrue("No real HC reads in this synthetic scenario", listOf("READ_HEART_RATE", "READ_TOTAL_CALORIES_BURNED",
            "READ_STEPS", "READ_EXERCISE", "READ_DISTANCE").none {
            ContextCompat.checkSelfPermission(context, "android.permission.health.$it") == PackageManager.PERMISSION_GRANTED
        })
        requireNaturalSeedEligibility()
        assertFalse(TrackingService.isRunning)
        val repository = DiaryRepository(context)
        val baseline = repository.snapshot()
        assumeTrue("Never clear preexisting QA health/journey data", baseline.journeys.isEmpty() && baseline.points.isEmpty() &&
            baseline.healthSamples.isEmpty() && baseline.watchHealthSamples.isEmpty() && baseline.watchHealthSessions.isEmpty())
        val manager = PostJourneyAnalysis.get(context)
        awaitState(manager) { manager.canProcessNow() }
        val prefix = "qa-sec001-${UUID.randomUUID()}"
        val start = System.currentTimeMillis() - 30_000
        val affected = Journey(id = "$prefix-affected", transport = Transport.WALK, startedAt = start)
        val unaffected = Journey(id = "$prefix-unaffected", transport = Transport.WALK, startedAt = start, endedAt = start + 20_000)
        val blocker = Journey(id = "$prefix-blocker", transport = Transport.WALK, startedAt = System.currentTimeMillis())
        val createdSidecars = mutableListOf<File>()
        try {
            repository.saveJourney(affected)
            repository.addPoint(TrackPoint(id = "$prefix-p1", journeyId = affected.id, latitude = 0.0, longitude = 0.0,
                recordedAt = start + 1_000, accuracy = 5f))
            repository.addPoint(TrackPoint(id = "$prefix-p2", journeyId = affected.id, latitude = 0.0001, longitude = 0.0,
                recordedAt = start + 11_000, accuracy = 5f))
            repository.finishJourney(affected.id, start + 20_000)
            repository.saveJourney(unaffected)
            val marker = "$prefix-health-source"
            repository.replaceJourneyHealth(setOf(affected.id), listOf(ImportedHealthSample(marker,
                HealthRules.SAMSUNG_HEALTH_PACKAGE, HealthRules.WATCH_DEVICE_TYPE, metric = HealthMetric.HEART_RATE_BPM,
                startAt = start + 5_000, endAt = start + 5_000, value = 137.0)), setOf(HealthMetric.HEART_RATE_BPM))
            manager.request()
            requireNaturalSeedEligibility()
            awaitState(manager) { manager.canProcessNow() }
            assertTrue(withTimeout(20_000) { manager.runPending() })
            val prepared = awaitState(manager) { it.phase == AnalysisPhase.READY && !it.pending &&
                it.entries.keys.containsAll(listOf(affected.id, unaffected.id)) }
            requireNaturalSeedEligibility()
            val entry = requireNotNull(prepared.entries[affected.id])
            val other = requireNotNull(prepared.entries[unaffected.id])
            assertEquals(137.0, entry.health.heartRateMeanBpm!!, 0.0)
            assertEquals(marker, manager.cache.healthSamples(affected.id, entry.fingerprint).single().sourceId)
            val root = File(context.noBackupFilesDir, "post_journey_analysis").canonicalFile
            check(root.parentFile == context.noBackupFilesDir.canonicalFile)
            repository.saveJourney(blocker)
            manager.request()
            awaitState(manager) { it.phase == AnalysisPhase.WAITING_END && !manager.canProcessNow() }
            block(Fixture(repository, manager, requireNotNull(repository.getJourney(affected.id)), unaffected,
                entry, other, prepared.cacheRevision, marker, root, createdSidecars))
        } finally {
            withContext(NonCancellable) { withTimeout(15_000) {
                // Remove only sidecars this test itself created, never broad cache/DB cleanup.
                createdSidecars.forEach { if (it.exists()) { check(it.isFile); check(it.delete()) } }
                listOf(affected.id, unaffected.id, blocker.id).forEach { repository.deleteJourney(it) }
                manager.request()
                assertEquals(baseline.journeys, repository.snapshot().journeys)
                assertEquals(baseline.points, repository.snapshot().points)
                assertEquals(baseline.healthSamples, repository.snapshot().healthSamples)
                assertEquals(baseline.watchHealthSamples, repository.snapshot().watchHealthSamples)
                assertEquals(baseline.watchHealthSessions, repository.snapshot().watchHealthSessions)
            } }
        }
    }

    private class Fixture(val repository: DiaryRepository, val manager: PostJourneyAnalysis,
        val affected: Journey, val unaffected: Journey, val entry: AnalysisEntry, val unaffectedEntry: AnalysisEntry,
        val oldRevision: String, val marker: String, val root: File, val sidecars: MutableList<File>) {
        private val key = AnalysisCache.digest(affected.id)
        private val orphanFingerprint = AnalysisCache.digest("old-fingerprint-${affected.id}")
        private fun asset(kind: String, fingerprint: String) = File(root, "$kind-$key-$fingerprint.json")
        private fun family(base: File) = listOf("", ".bak", ".new").map { File(base.path + it) }
        private val heartBases get() = listOf(entry.fingerprint, orphanFingerprint).map { asset("heart", it) }
        private val trailBases get() = listOf(entry.fingerprint, orphanFingerprint).map { asset("trail", it) }
        val trailFamily get() = trailBases.flatMap(::family)
        private val entryFile get() = File(root, "entry-$key.json")
        val unaffectedFiles get() = listOf("entry-${AnalysisCache.digest(unaffected.id)}.json",
            "heart-${AnalysisCache.digest(unaffected.id)}-${unaffectedEntry.fingerprint}.json",
            "trail-${AnalysisCache.digest(unaffected.id)}-${unaffectedEntry.fingerprint}.json").map { File(root, it) }
        private var aggregateBases = emptyList<File>()

        fun seedAtomicSiblingsAndOrphanFingerprint() {
            val samples = manager.cache.healthSamples(affected.id, entry.fingerprint)
            assertEquals(marker, samples.single().sourceId)
            val trail = requireNotNull(manager.cache.trail(affected.id, entry.fingerprint))
            manager.cache.saveHealthSamples(affected.id, orphanFingerprint, samples)
            manager.cache.saveTrail(affected.id, orphanFingerprint, trail.trail)
            aggregateBases = root.listFiles().orEmpty().filter { it.name.startsWith("heatmap-") && it.name.endsWith(".json") }
            assertTrue(aggregateBases.isNotEmpty())
            val bases = listOf(entryFile, File(root, "index.json")) + heartBases + trailBases + aggregateBases
            bases.forEach { base ->
                assertTrue(base.isFile)
                listOf(".bak", ".new").forEach { suffix ->
                    val sibling = File(base.path + suffix)
                    check(sibling.canonicalFile.parentFile == root && !sibling.exists())
                    base.copyTo(sibling, overwrite = false); sidecars += sibling
                }
            }
        }
        fun assertPurged(healthOnly: Boolean) {
            // Existence BEFORE readers: openRead may restore .bak; failedAsset may delete bad files.
            val removed = listOf(entryFile) + heartBases + aggregateBases + if (healthOnly) emptyList() else trailBases
            removed.flatMap(::family).forEach { assertFalse("Sensitive asset remains: ${it.name}", it.exists()) }
            listOf(".bak", ".new").forEach { assertFalse(File(root, "index.json$it").exists()) }
            val index = org.json.JSONObject(File(root, "index.json").readText())
            assertTrue(index.isNull("daily"))
            assertFalse(index.toString().contains(affected.id))
            assertFalse(index.toString().contains(marker))
            assertFalse("Pending hiding alone is insufficient", manager.state.value.entries.containsKey(affected.id))
            assertTrue(manager.state.value.pending)
            assertEquals("", manager.state.value.cacheRevision)
            val persisted = manager.cache.load().first
            assertFalse(persisted.entries.containsKey(affected.id))
            assertNotEquals(oldRevision, persisted.cacheRevision)
            assertNull(manager.cache.entry(affected.id, entry.fingerprint))
            assertTrue(manager.cache.healthSamples(affected.id, entry.fingerprint).isEmpty())
            assertTrue(manager.cache.healthSamples(affected.id, orphanFingerprint).isEmpty())
        }
        suspend fun assertOtherPreserved(hashes: Map<String, String>) {
            assertEquals(unaffected, repository.getJourney(unaffected.id))
            unaffectedFiles.forEach { assertEquals(hashes[it.name], digest(it.readBytes())) }
            val stillKept = requireNotNull(manager.cache.entry(unaffected.id, unaffectedEntry.fingerprint))
            assertEquals(unaffectedEntry.stats, stillKept.stats)
            assertEquals(unaffectedEntry.health, stillKept.health)
            assertEquals(unaffectedEntry.watch, stillKept.watch)
            assertEquals(unaffectedEntry.fingerprint, manager.state.value.entries[unaffected.id]?.fingerprint)
        }
        suspend fun assertNoStaleRepublishWhileIneligible(healthOnly: Boolean) {
            assertFalse(manager.canProcessNow())
            assertTrue(withTimeout(5_000) { manager.runPending() })
            assertFalse(manager.canProcessNow())
            assertPurged(healthOnly)
        }
    }
    private suspend fun awaitState(manager: PostJourneyAnalysis, accept: (AnalysisState) -> Boolean): AnalysisState {
        val state = withTimeout(5_000) { manager.state.first { it.phase == AnalysisPhase.FAILED || accept(it) } }
        assertNotEquals(AnalysisPhase.FAILED, state.phase)
        return state
    }
    private fun requireNaturalSeedEligibility() {
        val percent = PhoneBatteryReader.read(context).percent
        assumeTrue("Only live-state seeding needs natural>=20; purge is tested with the active gate CLOSED", percent != null && percent in 20..100)
    }
    companion object {
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
