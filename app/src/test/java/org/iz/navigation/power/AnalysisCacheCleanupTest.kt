package org.iz.navigation.power

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyHealthSummary
import org.iz.navigation.data.JourneyStats
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.junit.Assert.*
import org.junit.Test

class AnalysisCacheCleanupTest {
    @Test fun staleCurveBackupAndNewWithoutBaseAreRemoved() = withTemporaryCache { directory, cache ->
        // Focused baseline05 RED: both names were skipped before any Android AtomicFile branch.
        val base = directory.resolve(curveName(OLD_FINGERPRINT))
        val backup = directory.resolve(base.fileName.toString() + ".bak")
        val partial = directory.resolve(base.fileName.toString() + ".new")
        Files.write(backup, "old-backup".toByteArray(Charsets.UTF_8))
        Files.write(partial, "old-uncommitted".toByteArray(Charsets.UTF_8))
        assertFalse("The regression requires no base file", Files.exists(base))

        cache.cleanup(emptyMap(), "current-revision")

        assertFalse("A stale backup must not remain recoverable", Files.exists(backup))
        assertFalse("A stale interrupted write must not remain", Files.exists(partial))
        assertFalse(Files.exists(base))
        assertTrue(directory.toFile().listFiles()!!.isEmpty())
    }

    @Test fun currentCurveFamilyAndUnrelatedFilesRemainByteIdentical() = withTemporaryCache { directory, cache ->
        val base = curveName(CURRENT_FINGERPRINT)
        val retained = linkedMapOf(
            base to "current-base",
            "$base.bak" to "current-backup",
            "$base.new" to "current-uncommitted",
            "unrelated.json" to "user-owned-unrelated-base",
            "unrelated.json.bak" to "user-owned-unrelated-backup",
            "unrelated.json.new" to "user-owned-unrelated-partial",
            "curves-readme.txt" to "unrelated-extension",
        )
        retained.forEach { (name, content) -> Files.write(directory.resolve(name), content.toByteArray(Charsets.UTF_8)) }

        cache.cleanup(currentEntries(), "current-revision")

        assertEquals(retained.keys, directory.toFile().list()!!.toSet())
        retained.forEach { (name, expected) ->
            assertArrayEquals(name, expected.toByteArray(Charsets.UTF_8), Files.readAllBytes(directory.resolve(name)))
        }
    }

    @Test fun obsoleteFingerprintSiblingsAreRemovedWhileCurrentFamilyIsRetained() = withTemporaryCache { directory, cache ->
        val oldBase = curveName(OLD_FINGERPRINT)
        val currentBase = curveName(CURRENT_FINGERPRINT)
        val current = listOf(currentBase, "$currentBase.bak", "$currentBase.new")
        current.forEach { name -> Files.write(directory.resolve(name), "keep:$name".toByteArray(Charsets.UTF_8)) }
        Files.write(directory.resolve("$oldBase.bak"), "stale-backup".toByteArray(Charsets.UTF_8))
        Files.write(directory.resolve("$oldBase.new"), "stale-partial".toByteArray(Charsets.UTF_8))
        assertFalse(Files.exists(directory.resolve(oldBase)))

        cache.cleanup(currentEntries(), "current-revision")

        assertFalse(Files.exists(directory.resolve("$oldBase.bak")))
        assertFalse(Files.exists(directory.resolve("$oldBase.new")))
        assertEquals(current.toSet(), directory.toFile().list()!!.toSet())
        current.forEach { name ->
            assertArrayEquals(name, "keep:$name".toByteArray(Charsets.UTF_8), Files.readAllBytes(directory.resolve(name)))
        }
    }

    private fun currentEntries(): Map<String, AnalysisEntry> {
        val journey = Journey(id = "abc", transport = Transport.MOTORCYCLE,
            status = JourneyStatus.CONFIRMED, startedAt = 0, endedAt = 10_000)
        val stationary = JourneyStats(distanceMeters = 0.0, elapsedMillis = 10_000,
            observedMillis = 10_000, movingMillis = 0, stoppedMillis = 10_000, unobservedMillis = 0,
            averageSpeedKmh = 0.0, movingAverageSpeedKmh = null, maxSpeedKmh = 0.0)
        return mapOf("abc" to AnalysisEntry(CURRENT_FINGERPRINT, stationary,
            JourneyHealthSummary("abc"), JourneyHealthSummary("abc"), journey))
    }

    private fun curveName(fingerprint: String) = "curves-$JOURNEY_KEY-$fingerprint.json"

    private fun withTemporaryCache(block: (Path, AnalysisCache) -> Unit) {
        // B1 may pin this root explicitly; Windows defaults to D:, while non-Windows CI stays portable.
        val configured = System.getProperty("iz.unitTempRoot")
        val requestedRoot = when {
            configured != null -> Paths.get(configured)
            File.separatorChar == '\\' -> Paths.get("D:/CodexBuilds/iz-roadmap-five-20261102/unit-temp")
            else -> Paths.get(System.getProperty("java.io.tmpdir"))
        }.toAbsolutePath().normalize()
        val parent = Files.createDirectories(requestedRoot).toRealPath()
        val directory = Files.createTempDirectory(parent, "analysis-cache-cleanup-").toRealPath()
        check(directory.parent == parent)
        try {
            block(directory, AnalysisCache(directory.toFile()))
        } finally {
            // Only this newly created, owned, flat fixture directory; never recursive traversal.
            check(directory.toRealPath().parent == parent)
            Files.newDirectoryStream(directory).use { children ->
                children.forEach { child ->
                    check(child.parent == directory)
                    Files.delete(child)
                }
            }
            Files.delete(directory)
        }
    }

    private companion object {
        // SHA-256("abc"), a fixed external test vector; no AnalysisCache.digest() oracle.
        const val JOURNEY_KEY = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val CURRENT_FINGERPRINT = "a".repeat(64)
        val OLD_FINGERPRINT = "b".repeat(64)
    }
}
