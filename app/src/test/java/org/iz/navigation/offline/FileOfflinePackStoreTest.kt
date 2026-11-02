package org.iz.navigation.offline

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FileOfflinePackStoreTest {
    private fun temporary(prefix: String) = Files.createTempDirectory(
        Files.createDirectories(java.nio.file.Paths.get(System.getProperty("iz.offline.testRoot",
            "D:/CodexBuilds/iz-roadmap-five-20261102/unit-temp"))), prefix,
    ).toFile()
    private fun pack() = OfflinePack(
        OfflineCorridorRequest("route", "a".repeat(64), listOf(OfflinePoint(41.0, 29.0), OfflinePoint(41.01, 29.01)), 500.0, 8, 12),
        OfflinePackStatus.PARTIAL, downloadedBytes = 7123, regionId = 5, generation = 4,
    )
    @Test fun atomicManifestRoundTripsAcrossStoreInstances() {
        val directory = temporary("iz-offline-manifest")
        try {
            FileOfflinePackStore(directory).write(pack())
            val restored = FileOfflinePackStore(directory).read()
            assertNotNull("Persisted pack must survive a new store instance", restored)
            assertEquals(pack().request, restored!!.request)
            assertEquals(7123L, restored.downloadedBytes)
            assertEquals(5L, restored.regionId)
            assertEquals(OfflinePackStatus.PARTIAL, restored.status)
            assertTrue(restored.rings.isNotEmpty())
            FileOfflinePackStore(directory).remove()
            assertNull(FileOfflinePackStore(directory).read())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
    @Test fun invalidReplacementCannotEraseExistingValidManifest() {
        val directory = temporary("iz-offline-atomic")
        try {
            val store = FileOfflinePackStore(directory)
            store.write(pack())
            assertThrows(IllegalArgumentException::class.java) { store.write(pack().copy(downloadedBytes = 50_000_001)) }
            assertEquals(7123L, store.read()?.downloadedBytes)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
    @Test fun tamperedManifestIsRejectedWithoutDeletingEvidence() {
        val directory = temporary("iz-offline-corrupt")
        try {
            val store = FileOfflinePackStore(directory); store.write(pack())
            val manifest = directory.listFiles()!!.single()
            val bytes = manifest.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            manifest.writeBytes(bytes)
            assertNull(store.read())
            assertTrue(manifest.isFile)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
