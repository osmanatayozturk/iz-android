package org.iz.navigation.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupIdentityCompatibilityTest {
    @Test fun exporterUsesNeutralMarkerWithVersionSixPayload() {
        val encoded = BackupJson.encode(LegacyBackupFixture.expected(), includeHealth = true)
        assertEquals("org.iz.navigation.backup", encoded.getString("format"))
        assertEquals(6, encoded.getInt("version"))
        assertEquals(LegacyBackupFixture.expected(), BackupJson.decode(JSONObject(encoded.toString())))
    }

    @Test fun bothExactMarkersRestoreExistingRecordsInEverySupportedVersion() {
        val legacy = LegacyBackupFixture.manifest()
        for (marker in listOf(legacy.getString("format"), "org.iz.navigation.backup")) {
            for (version in 1..5) {
                val manifest = JSONObject(legacy.toString()).put("format", marker).put("version", version)
                val expected = LegacyBackupFixture.expected().let {
                    it.copy(contributions = if (version >= 3) it.contributions else emptyList(),
                        healthSamples = if (version >= 4) it.healthSamples else emptyList(),
                        watchHealthSessions = if (version >= 5) it.watchHealthSessions else emptyList(),
                        watchHealthSamples = if (version >= 5) it.watchHealthSamples else emptyList())
                }
                if (version < 3) {
                    manifest.remove("contributions")
                    manifest.getJSONArray("places").getJSONObject(0).apply {
                        remove("osmType"); remove("osmId"); remove("source")
                    }
                }
                if (version < 4) manifest.remove("healthSamples")
                if (version < 5) {
                    manifest.remove("mapEdits"); manifest.remove("watchHealthSessions"); manifest.remove("watchHealthSamples")
                }
                assertEquals("$marker version $version", expected, BackupJson.decode(manifest))
            }
        }
    }

    @Test fun markerCompatibilityDoesNotAcceptUnknownOrLookalikeFormats() {
        val legacyMarker = LegacyBackupFixture.manifest().getString("format")
        for (marker in listOf("other.backup", "org.iz.navigation", "org.iz.navigation.backup.v5",
            "ORG.IZ.NAVIGATION.BACKUP", " org.iz.navigation.backup", "$legacyMarker.extra", "$legacyMarker ")) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupJson.decode(LegacyBackupFixture.manifest().put("format", marker))
            }
        }
    }

    @Test fun neitherMarkerBypassesUnsupportedVersionValidation() {
        for (marker in listOf(LegacyBackupFixture.manifest().getString("format"), "org.iz.navigation.backup")) {
            for (version in listOf(0, 7)) {
                assertThrows(IllegalArgumentException::class.java) {
                    BackupJson.decode(LegacyBackupFixture.manifest().put("format", marker).put("version", version))
                }
            }
        }
    }
}
