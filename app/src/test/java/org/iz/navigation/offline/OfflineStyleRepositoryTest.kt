package org.iz.navigation.offline

import java.nio.file.Files
import java.nio.file.Paths
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineStyleRepositoryTest {
    private fun temporary() = Files.createTempDirectory(Files.createDirectories(Paths.get(
        System.getProperty("iz.offline.testRoot", "D:/CodexBuilds/iz-roadmap-five-20261102/unit-temp"))), "styles").toFile()
    private fun pack() = corridorPlan(OfflineCorridorRequest("route", "a".repeat(64),
        listOf(OfflinePoint(41.0, 29.0), OfflinePoint(41.01, 29.01)), 500.0, 8, 12))
    private fun style() = OfflineStyleAudit.resolve(
        """{"version":8,"sources":{"openmaptiles":{"type":"vector","tiles":["https://tiles.openfreemap.org/planet/build/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"road","type":"line","source":"openmaptiles"}]}""".toByteArray()
    ) { error("Already pinned style must not fetch source metadata") }

    @Test fun verifiedStyleAndTileJournalSurviveNewRepositoryAndResumeGeneration() {
        val root = temporary()
        try {
            val first = OfflineStyleRepository(root); val original = pack()
            val saved = first.save(original, style())
            assertTrue(first.saveTileKeys(original, setOf("https://tiles.openfreemap.org/planet/build/8/148/96.pbf")))
            val resumed = original.copy(generation = 5, downloadedBytes = 123, regionId = 7, status = OfflinePackStatus.PARTIAL)
            val second = OfflineStyleRepository(root)
            assertEquals(saved, second.load(resumed)?.first)
            assertEquals(style().closure.styleSha256, second.load(resumed)?.second?.closure?.styleSha256)
            assertEquals(1, second.tileKeys(resumed).size)
            assertFalse("Generic notice names are not a verified profile corpus", root.resolve("NOTICES.txt").exists())
            second.remove(resumed)
            assertNull(second.load(resumed))
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }

    @Test fun tamperedStyleAndMissingOrChangedResumeJournalNeverResetToFresh() {
        val root = temporary()
        try {
            val store = OfflineStyleRepository(root); val original = pack()
            val styleFile = store.save(original, style())
            assertTrue(store.saveTileKeys(original, emptySet()))
            styleFile.appendText(" ")
            assertNull(store.load(original))
            assertTrue(styleFile.exists())
            val journal = root.listFiles()!!.single { it.name.endsWith(".tiles") }
            val json = JSONObject(journal.readText()).put("payload", "[\"https://tiles.openfreemap.org/planet/8/1/1.pbf\"]")
            journal.writeText(json.toString())
            assertThrows(IllegalArgumentException::class.java) { store.tileKeys(original) }
            journal.delete()
            assertThrows(IllegalStateException::class.java) {
                store.tileKeys(original.copy(downloadedBytes = 1, regionId = 7))
            }
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }
}
