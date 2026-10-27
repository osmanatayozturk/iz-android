package org.iz.navigation.lean

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import org.iz.navigation.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LeanBackupTest {
    @Test fun versionEightRoundTripsTypedPendingResultsAndShortcutReferences() {
        val pending = pending()
        val complete = pending.copy(input = pending.input.copy(token = pending.input.token.copy(
            id = "12345678-1234-1234-1234-123456789abc")), state = "COMPLETE", chunks = emptyList(),
            result = LeanEstimate("test-v1", "epoch", null, 12.0, 10, 20, emptySet(), emptyList()))
        val snapshot = DiarySnapshot(journeys = listOf(Journey(id = "trip", transport = Transport.MOTORCYCLE,
            startedAt = 1, endedAt = 2)), places = listOf(Place(id = "home", name = "Home")),
            destinationShortcuts = listOf(DestinationShortcutRef(DestinationShortcutKind.HOME, "home")),
            leanSessions = listOf(pending, complete))
        val json = BackupJson.encode(snapshot)
        assertEquals(8, json.getInt("version"))
        assertTrue(json.getJSONArray("leanSessions").getJSONObject(0).get("input") is JSONObject)
        assertFalse(json.toString().contains("base64", ignoreCase = true))
        assertEquals(snapshot, BackupJson.decode(json))
    }

    @Test fun oldVersionsIgnoreAbsentNewSections() {
        for (version in 1..7) {
            val json = BackupJson.encode(DiarySnapshot()).put("version", version)
            json.remove("leanSessions")
            json.remove("destinationShortcuts")
            assertEquals(DiarySnapshot(), BackupJson.decode(json))
        }
    }

    @Test fun hostileMetadataNeverRestartsCaptureOrAcceptsBrokenIdentities() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("state", "CAPTURING") },
            { it.getJSONObject("input").getJSONObject("token").put("id", "../bad") },
            { it.getJSONObject("input").getJSONObject("token").put("generation", "1") },
            { it.getJSONObject("input").put("endedAtElapsedNanos", JSONObject.NULL) },
            { it.getJSONObject("input").put("inputHash", "0".repeat(64)) },
            { it.getJSONArray("chunks").getJSONObject(0).put("byteCount", LeanCodec.MAX_CHUNK_BYTES + 1) },
            { it.getJSONArray("chunks").getJSONObject(0).put("partIndex", 1) },
            { it.getJSONArray("chunks").getJSONObject(0).put("recordCount", 0) },
            { it.getJSONArray("chunks").put(it.getJSONArray("chunks").getJSONObject(0)) },
        )
        for (mutate in mutations) {
            val json = LeanBackupCodec.encode(listOf(pending())).getJSONObject(0).also(mutate)
            assertThrows(Exception::class.java) { LeanBackupCodec.decode(JSONArray().put(json)) }
        }
    }

    @Test fun duplicateIdsAndDanglingShortcutsAreRejected() {
        assertThrows(Exception::class.java) { LeanBackupCodec.encode(listOf(pending(), pending())) }
        val json = BackupJson.encode(DiarySnapshot())
        json.put("destinationShortcuts", JSONArray().put(JSONObject().put("kind", "HOME").put("placeId", "missing")))
        assertThrows(Exception::class.java) { BackupJson.decode(json) }
        val lean = LeanBackupCodec.encode(listOf(pending()))
        json.put("destinationShortcuts", JSONArray()).put("leanSessions", lean)
        assertThrows(Exception::class.java) { BackupJson.decode(json) }
    }

    @Test fun framedRawRoundTripsAndRejectsCorruptionTruncationOrExtraChunk() {
        val session = pending()
        val raw = ByteArrayOutputStream().also { LeanArchive.writeRaw(session, it) { payload() } }.toByteArray()
        val dir = Files.createTempDirectory("lean-backup-test").toFile()
        try {
            var stagedBytes = 0L
            val parts = runBlocking { LeanArchive.stage(ByteArrayInputStream(raw), dir) { stagedBytes += it } }
            assertEquals(payload().size.toLong(), stagedBytes)
            LeanArchive.validate(session, parts)
            parts.single().appendBytes(byteArrayOf(0))
            assertThrows(Exception::class.java) { LeanArchive.validate(session, parts) }
            assertThrows(Exception::class.java) { runBlocking { LeanArchive.stage(ByteArrayInputStream(raw.copyOf(raw.size - 1)), dir) {} } }
            assertThrows(Exception::class.java) { runBlocking { LeanArchive.stage(ByteArrayInputStream(byteArrayOf(0, 0)), dir) {} } }
            val extra = runBlocking { LeanArchive.stage(ByteArrayInputStream(raw + raw), dir) {} }
            assertThrows(Exception::class.java) { LeanArchive.validate(session, extra) }
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }

    @Test fun oversizedFramesAndBudgetRejectionOccurBeforeStagingPayload() {
        val dir = Files.createTempDirectory("lean-backup-budget").toFile()
        try {
            val oversized = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(LeanCodec.MAX_CHUNK_BYTES + 1) }.toByteArray()
            assertThrows(Exception::class.java) { runBlocking { LeanArchive.stage(ByteArrayInputStream(oversized), dir) {} } }
            val raw = ByteArrayOutputStream().also { LeanArchive.writeRaw(pending(), it) { payload() } }.toByteArray()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { LeanArchive.stage(ByteArrayInputStream(raw), dir) { error("old plus staged budget exceeded") } }
            }
            assertEquals(0, dir.listFiles()!!.size)
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }

    @Test fun entryAllowlistRejectsTraversalCaseAliasesAndExtraOrMissingEntries() {
        val session = pending()
        val raw = LeanArchive.rawName(session.input.token.id)
        assertTrue(LeanArchive.isRawName(raw))
        listOf("lean/../x.raw", raw.uppercase(), "$raw/extra", "lean/1.raw").forEach { assertFalse(LeanArchive.isRawName(it)) }
        LeanArchive.validateEntryNames(setOf("manifest.json", raw), emptySet(), listOf(session))
        assertThrows(Exception::class.java) {
            LeanArchive.validateEntryNames(setOf("manifest.json"), emptySet(), listOf(session))
        }
        assertThrows(Exception::class.java) {
            LeanArchive.validateEntryNames(setOf("manifest.json", raw, "photos/extra.jpg"), emptySet(), listOf(session))
        }
    }

    @Test fun completedRawAndZeroChunkPartialRemainSealedAfterRoundTrip() {
        val pending = pending()
        val result = LeanEstimate("test-v1", "epoch", null, null, 0, 0, emptySet(), emptyList())
        val completed = pending.copy(state = "COMPLETE", result = result)
        assertEquals(listOf(completed), LeanBackupCodec.decode(LeanBackupCodec.encode(listOf(completed))))
        val partialInput = pending.input.copy(partialReasons = setOf("RAW_BUDGET"))
        val partial = pending.copy(input = partialInput.copy(inputHash = LeanBackupCodec.inputHash(partialInput, emptyList())),
            state = "PARTIAL", chunks = emptyList())
        assertEquals(listOf(partial), LeanBackupCodec.decode(LeanBackupCodec.encode(listOf(partial))))
    }

    @Test fun sameLengthCorruptionAndDishonestRecordCountAreRejected() {
        val session = pending()
        val dir = Files.createTempDirectory("lean-backup-integrity").toFile()
        val file = java.io.File(dir, "generated.part")
        try {
            val bytes = payload()
            file.writeBytes(bytes.copyOf().also { it[it.lastIndex] = 0 })
            assertThrows(Exception::class.java) { LeanArchive.validate(session, listOf(file)) }
            file.writeBytes(bytes)
            val dishonest = session.copy(chunks = listOf(session.chunks.single().copy(recordCount = 2)))
            assertThrows(Exception::class.java) { LeanArchive.validate(dishonest, listOf(file)) }
            val falseTime = session.copy(chunks = listOf(session.chunks.single().copy(lastElapsedNanos = 101)))
            assertThrows(Exception::class.java) { LeanArchive.validate(falseTime, listOf(file)) }
        } finally { file.delete(); dir.delete() }
    }
    private fun payload() = LeanCodec.encodeRecords(listOf(LeanImuRecord(LeanSensorKind.ACCELEROMETER, 0, 100, 0.0, 0.0, 9.8, 3)))
    private fun pending(): LeanBackupSession {
        val bytes = payload()
        val chunks = listOf(LeanChunkManifest(0, bytes.size, LeanCodec.sha256(bytes), 1, 100, 100))
        val input = LeanInputSession(LeanSessionToken("12345678-1234-1234-1234-123456789abd", "trip", 1),
            LeanCalibration("epoch", 1, listOf(0.0, 0.0, 9.8), listOf(0.0, 0.0, 0.0)), "boot", 1, 0, 200_000_000,
            "", emptySet(), 0)
        return LeanBackupSession(input.copy(inputHash = LeanBackupCodec.inputHash(input, chunks)), "SEALED", chunks)
    }
}