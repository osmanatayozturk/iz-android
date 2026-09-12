package org.iz.navigation.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupV6Test {
    private fun source(): DiarySnapshot = DiarySnapshot(places = listOf(
        Place(id = "a", name = "Alpha"), Place(id = "b", name = "Beta"), Place(id = "c", name = "Alpha")),
        visits = listOf(Visit(id = "visit", placeId = "b", visitedAt = 100)))

    @Test fun versionSixExportsAndRestoresExplicitOrderRatherThanRecentVisits() {
        val encoded = BackupJson.encode(source())
        assertEquals(6, encoded.getInt("version"))
        val places = encoded.getJSONArray("places")
        places.getJSONObject(0).put("sortOrder", 20L)
        places.getJSONObject(1).put("sortOrder", 30L)
        places.getJSONObject(2).put("sortOrder", 10L)
        val restored = BackupJson.encode(BackupJson.decode(encoded)).getJSONArray("places")
        assertEquals(20L, restored.getJSONObject(0).getLong("sortOrder"))
        assertEquals(30L, restored.getJSONObject(1).getLong("sortOrder"))
        assertEquals(10L, restored.getJSONObject(2).getLong("sortOrder"))
    }
    @Test fun everyLegacyVersionUsesImportedVisitOrderAndDeterministicTies() {
        for (version in 1..5) {
            val legacy = BackupJson.encode(source()).put("version", version)
            // Stray order fields in older backups are not authoritative.
            legacy.getJSONArray("places").getJSONObject(0).put("sortOrder", "invalid legacy metadata")
            val restored = BackupJson.encode(BackupJson.decode(legacy)).getJSONArray("places")
            val ranks = (0 until restored.length()).associate { index -> restored.getJSONObject(index).let { it.getString("id") to it.getLong("sortOrder") } }
            assertEquals(mapOf("b" to 0L, "a" to 1L, "c" to 2L), ranks)
        }
    }
    @Test fun malformedV6RanksAreRejectedWithoutCoercingStringsOrFractions() {
        for (bad in listOf<Any>(-1L, Long.MAX_VALUE, 1.5, "2", JSONObject.NULL)) {
            val encoded = BackupJson.encode(source()).put("version", 6)
            encoded.getJSONArray("places").getJSONObject(0).put("sortOrder", bad)
            assertThrows(Exception::class.java) { BackupJson.decode(encoded) }
        }
    }
}
