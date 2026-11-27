package org.iz.navigation.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupPageDirectoryTest {
    private fun group(type: String, parts: Int, bytes: Long, records: Int): BackupBulkGroup =
        BackupBulkGroup(type, parts * records, List(parts) { index ->
            BackupBulkPart(type, index, BackupBulkPages.pageName(type, index), records, bytes, "0".repeat(64))
        })

    private fun index(group: BackupBulkGroup): JSONObject {
        val groups = BackupBulkPages.TYPES.map { if (it == group.type) group else BackupBulkGroup(it, 0, emptyList()) }
        return BackupJson.header(0).put("version", BackupBulkPages.VERSION).also { manifest ->
            groups.forEach { manifest.put(it.type, JSONArray()) }
            manifest.put("bulkPages", BackupBulkPages.encode(groups))
        }
    }

    @Test fun allowsLargeNonGpsTablesAndKeepsModelMemoryBounded() {
        val oneMiB = 1024L * 1024
        val largeVisits = group("visits", 40, oneMiB, 250)
        assertEquals(largeVisits, BackupBulkPages.parse(index(largeVisits)).single { it.type == "visits" })
        assertThrows(BackupLimitException::class.java) {
            BackupBulkPages.parse(index(group("visits", 65, oneMiB, 250)))
        }
        assertThrows(BackupLimitException::class.java) {
            BackupBulkPages.parse(index(group("places", 400, 32 * 1024L, 512)))
        }
        val streamed = group("points", 65, oneMiB, 512)
        assertEquals(streamed, BackupBulkPages.parse(index(streamed)).single { it.type == "points" })
    }

    @Test fun requiresExactPageSequenceAndRejectsConflictingInlineRecords() {
        val group = group("visits", 2, 64, 1)
        val misplaced = index(group)
        val groups = misplaced.getJSONArray("bulkPages")
        val parts = (0 until groups.length()).map(groups::getJSONObject)
            .single { it.getString("type") == "visits" }.getJSONArray("parts")
        parts.getJSONObject(1).put("path", group.parts.first().path)
        assertThrows(IllegalArgumentException::class.java) { BackupBulkPages.parse(misplaced) }
        val conflicting = index(group).put("visits", JSONArray().put(JSONObject()))
        assertThrows(IllegalArgumentException::class.java) { BackupBulkPages.parse(conflicting) }
    }
}
