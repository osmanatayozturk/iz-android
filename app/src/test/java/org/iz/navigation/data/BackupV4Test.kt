package org.iz.navigation.data

import org.junit.Assert.*
import org.junit.Test

class BackupV4Test {
    private val trip = Journey(id = "run", transport = Transport.RUN, startedAt = 1_000, endedAt = 5_000, stepCount = 42)
    private val sample = ImportedHealthSample("source", HealthRules.SAMSUNG_HEALTH_PACKAGE, 1, "Samsung", "Watch8 Classic",
        HealthMetric.HEART_RATE_BPM, 2_000, 2_000, 90.0).forJourney(trip.id)
    private val original = DiarySnapshot(journeys = listOf(trip), healthSamples = listOf(sample))

    @Test fun backupsOmitSensitiveHealthUnlessExplicitlyIncluded() {
        val encoded = BackupJson.encode(original)
        assertEquals(7, encoded.getInt("version"))
        assertTrue(BackupJson.decode(encoded).healthSamples.isEmpty())
        assertEquals(trip, BackupJson.decode(encoded).journeys.single())
        assertEquals(original, BackupJson.decode(BackupJson.encode(original, includeHealth = true)))
    }

    @Test fun previousBackupVersionsNeverInterpretExtraFieldsAsHealthPermissionOrData() {
        for (version in 1..3) {
            val encoded = BackupJson.encode(original, includeHealth = true).put("version", version)
            val restored = BackupJson.decode(encoded)
            assertTrue(restored.healthSamples.isEmpty())
            assertEquals(42L, restored.journeys.single().stepCount)
        }
    }

    @Test fun versionFourHealthStillDecodesWithoutAnyVersionFiveArrays() {
        val encoded = BackupJson.encode(original, includeHealth = true).put("version", 4)
        encoded.remove("mapEdits"); encoded.remove("watchHealthSessions"); encoded.remove("watchHealthSamples")
        assertEquals(original, BackupJson.decode(encoded))
    }

    @Test fun invalidDeviceDanglingJourneyOrDuplicateSampleRejectsTheWholeBackup() {
        fun invalid(mutate: (org.json.JSONObject) -> Unit) {
            val encoded = BackupJson.encode(original, includeHealth = true)
            assertTrue("Explicitly selected health data must be represented in the backup", encoded.has("healthSamples"))
            mutate(encoded.getJSONArray("healthSamples").getJSONObject(0))
            assertThrows(IllegalArgumentException::class.java) { BackupJson.decode(encoded) }
        }
        invalid { it.put("deviceType", org.json.JSONObject.NULL) }
        invalid { it.put("journeyId", "missing") }
        invalid { it.put("startAt", 5_000).put("endAt", 5_000) }
        invalid { it.put("value", -5) }
        invalid { it.put("startAt", 2_000.5) }
        val duplicate = BackupJson.encode(original, includeHealth = true)
        duplicate.getJSONArray("healthSamples").put(duplicate.getJSONArray("healthSamples").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { BackupJson.decode(duplicate) }
    }
}
