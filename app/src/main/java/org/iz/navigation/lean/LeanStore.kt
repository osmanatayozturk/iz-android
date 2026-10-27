package org.iz.navigation.lean

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.iz.navigation.data.*

/** Private immutable Room chunks; source input is independent of disposable analysis caches. */
class LeanStore internal constructor(
    context: Context,
    private val db: DiaryDatabase,
    private val byteBudget: Long = MAX_RAW_BYTES,
    private val scheduleAnalysis: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val dao = db.leanDao()
    private val ioReserve = minOf(2L * LeanCodec.MAX_CHUNK_BYTES, byteBudget / 4)
    private var recovered = false
    init { require(byteBudget in 1024..MAX_RAW_BYTES) }

    suspend fun begin(journeyId: String, calibration: LeanCalibration, bootId: String,
        utcMillis: Long, elapsedNanos: Long): LeanSessionToken? = withContext(Dispatchers.IO) {
        withSnapshotLease {
            recoverLocked()
            require(calibration.bodyConfirmed && utcMillis >= 0 && elapsedNanos >= 0 && bootId.isNotBlank() && bootId.length <= 200)
            val encoded = LeanCodec.calibrationJson(calibration)
            db.withTransaction {
                val trip = db.diaryDao().journey(journeyId) ?: return@withTransaction null
                if (trip.status != JourneyStatus.CONFIRMED || trip.transport != Transport.MOTORCYCLE ||
                    trip.endedAt != null || trip.interrupted) return@withTransaction null
                val previous = dao.allSessions().filter { it.journeyId == journeyId }

                if (previous.any { LeanCodec.reasons(it.reasonsJson).any { r -> r in setOf("RAW_BUDGET", "CHUNK_LIMIT") } }) return@withTransaction null
                previous.filter { it.state == "CAPTURING" }.forEach { old ->
                    dao.update(old.copy(state = "PARTIAL", endedAtElapsedNanos = old.endedAtElapsedNanos?.let { minOf(it, elapsedNanos) } ?: elapsedNanos,
                        reasonsJson = LeanCodec.reasonsJson(LeanCodec.reasons(old.reasonsJson) + if (old.calibrationJson == encoded) "PRODUCER_REPLACED" else "CALIBRATION_CHANGED")))
                }
                val capacityReason = when {
                    dao.rawBytes() + ioReserve >= byteBudget -> "RAW_BUDGET"
                    dao.chunkCount() >= MAX_CHUNKS -> "CHUNK_LIMIT"
                    else -> null
                }
                val capped = capacityReason != null
                val row = LeanSessionEntity(UUID.randomUUID().toString(), journeyId, generations.incrementAndGet(),
                    encoded, bootId, utcMillis, elapsedNanos, if (capped) elapsedNanos else null, if (capped) "PARTIAL" else "CAPTURING",
                    LeanCodec.reasonsJson(setOfNotNull(capacityReason)), 0)
                dao.insert(row)
                if (capped) null else row.token()
            }
        }
    }

    suspend fun append(token: LeanSessionToken, records: List<LeanRecord>): Boolean = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext true
        require(records.size <= LeanCodec.MAX_RECORDS)
        withSnapshotLease {
            val row = dao.session(token.id) ?: return@withSnapshotLease false
            if (!row.matches(token) || row.state != "CAPTURING" ||
                LeanCodec.reasons(row.reasonsJson).any { it in setOf("RAW_BUDGET", "CHUNK_LIMIT") }) return@withSnapshotLease false
            val trip = db.diaryDao().journey(row.journeyId) ?: return@withSnapshotLease false
            // A valid writer may drain after finish; deleted, changed-mode and temporary trips cannot write.
            if (trip.transport != Transport.MOTORCYCLE || trip.status != JourneyStatus.CONFIRMED) return@withSnapshotLease false
            val accepted = records.filter { it.elapsedNanos >= row.startedAtElapsedNanos &&
                (row.endedAtElapsedNanos == null || it.elapsedNanos <= row.endedAtElapsedNanos) }
            if (accepted.isEmpty()) return@withSnapshotLease true
            val payload = LeanCodec.encodeRecords(accepted)
            val capacityReason = when {
                dao.rawBytes() + 2L * payload.size + ioReserve > byteBudget -> "RAW_BUDGET"
                dao.chunkCount() >= MAX_CHUNKS -> "CHUNK_LIMIT"
                else -> null
            }
            if (capacityReason != null) {
                dao.update(row.copy(reasonsJson = LeanCodec.reasonsJson(LeanCodec.reasons(row.reasonsJson) + capacityReason)))
                return@withSnapshotLease false
            }
            db.withTransaction {
                val current = dao.session(token.id) ?: return@withTransaction false
                if (!current.matches(token) || current.state != "CAPTURING") return@withTransaction false
                val index = dao.chunkInfos(token.id).lastOrNull()?.partIndex?.plus(1) ?: 0
                dao.insert(LeanChunkEntity(token.id, index, payload, LeanCodec.sha256(payload), accepted.size,
                    accepted.minOf { it.elapsedNanos }, accepted.maxOf { it.elapsedNanos }))
                true
            }
        }
    }

    suspend fun stopAt(token: LeanSessionToken, endElapsedNanos: Long) = withContext(Dispatchers.IO) {
        withSnapshotLease {
            val row = dao.session(token.id) ?: return@withSnapshotLease
            if (!row.matches(token) || row.state != "CAPTURING") return@withSnapshotLease
            require(endElapsedNanos >= row.startedAtElapsedNanos)
            dao.update(row.copy(endedAtElapsedNanos = row.endedAtElapsedNanos?.let { minOf(it, endElapsedNanos) } ?: endElapsedNanos))
        }
    }
    suspend fun seal(token: LeanSessionToken, endElapsedNanos: Long, partialReason: String? = null,
        droppedSamples: Long = 0) = withContext(Dispatchers.IO) {
        withSnapshotLease {
            val row = dao.session(token.id) ?: return@withSnapshotLease
            if (!row.matches(token) || row.state != "CAPTURING") return@withSnapshotLease
            require(endElapsedNanos >= row.startedAtElapsedNanos && droppedSamples >= 0)
            val reasons = LeanCodec.reasons(row.reasonsJson) + listOfNotNull(partialReason)
            dao.update(row.copy(endedAtElapsedNanos = row.endedAtElapsedNanos?.let { minOf(it, endElapsedNanos) } ?: endElapsedNanos,
                state = if (reasons.isEmpty() && droppedSamples == 0L) "SEALED" else "PARTIAL",
                reasonsJson = LeanCodec.reasonsJson(reasons), droppedSamples = row.droppedSamples + droppedSamples))
        }
        scheduleAnalysis()
    }

    fun observeReport(journeyId: String): Flow<LeanJourneyReport> = dao.sessions(journeyId).map { rows ->
        val reasons = linkedSetOf<String>()
        val results = rows.mapNotNull { row ->
            reasons += runCatching { LeanCodec.reasons(row.reasonsJson) }.getOrDefault(setOf("INVALID_METADATA"))
            dao.result(row.id)?.let { result ->
                runCatching { LeanCodec.estimate(result.payload) }.getOrElse { reasons += "INVALID_RESULT"; null }
            }
        }
        results.forEach { reasons += it.partialReasons }
        LeanJourneyReport(rows.any { it.state != "COMPLETE" },
            results.mapNotNull { it.leftDegrees }.maxOrNull(), results.mapNotNull { it.rightDegrees }.maxOrNull(),
            results.sumOf { it.validMillis }, results.sumOf { it.observedMillis }, reasons,
            results.flatMap { it.curves }.sortedBy { it.startUtcMillis ?: Long.MAX_VALUE }.take(LeanCodec.MAX_CURVES),
            results.map { it.algorithmVersion }.toSet())
    }.flowOn(Dispatchers.IO)

    internal suspend fun pendingInputs(): List<LeanInputSession> = withContext(Dispatchers.IO) {
        withSnapshotLease { recoverLocked(); dao.pending().map { input(it, dao.chunkInfos(it.id)) } }
    }
    internal suspend fun hasPending(): Boolean = withContext(Dispatchers.IO) {
        withSnapshotLease { recoverLocked(); dao.pending().isNotEmpty() }
    }
    internal suspend fun recoverInterrupted() = withContext(Dispatchers.IO) {
        withSnapshotLease { recoverLocked() }
    }
    private suspend fun recoverLocked() {
        if (recovered) return
        db.withTransaction {
            dao.allSessions().filter { it.state == "CAPTURING" }.forEach { row ->
                val end = row.endedAtElapsedNanos ?: dao.chunkInfos(row.id).maxOfOrNull { it.lastElapsedNanos } ?: row.startedAtElapsedNanos
                dao.update(row.copy(state = "PARTIAL", endedAtElapsedNanos = end,
                    reasonsJson = LeanCodec.reasonsJson(LeanCodec.reasons(row.reasonsJson) + "PROCESS_INTERRUPTED")))
            }
        }
        cleanupCommittedRawLocked()
        recovered = true
    }

    internal suspend fun processPending(estimator: LeanEstimator, checkAllowed: () -> Unit) = withContext(Dispatchers.IO) {
        withSnapshotLease {
            recoverLocked()
            cleanupCommittedRawLocked()
            for (row in dao.pending()) {
                checkAllowed()
                val trip = db.diaryDao().journey(row.journeyId) ?: continue
                if (trip.transport != Transport.MOTORCYCLE || trip.status != JourneyStatus.CONFIRMED || trip.endedAt == null) continue
                val parts = dao.chunkInfos(row.id)
                val source = input(row, parts)
                val records = sequence {
                    for (part in parts) {
                        checkAllowed()
                        val chunk = requireNotNull(dao.readChunk(row.id, part.partIndex)) { "Pending raw missing" }
                        check(chunk.payload.size == part.byteCount && LeanCodec.sha256(chunk.payload) == part.sha256)
                        val decoded = LeanCodec.decodeRecords(chunk.payload).toList()
                        check(decoded.size == part.recordCount && decoded.minOf { it.elapsedNanos } == part.firstElapsedNanos &&
                            decoded.maxOf { it.elapsedNanos } == part.lastElapsedNanos)
                        for (record in decoded) if (row.endedAtElapsedNanos == null ||
                            record.elapsedNanos <= row.endedAtElapsedNanos) yield(record)
                    }
                }
                val result = estimator.estimate(source, records, checkAllowed)
                checkAllowed()
                if (commitResultLocked(row.id, source.inputHash, result, checkAllowed)) cleanupCommittedRawLocked()
            }
        }
    }

    internal suspend fun commitResult(sessionId: String, inputHash: String, estimate: LeanEstimate,
        checkAllowed: () -> Unit): Boolean = withContext(Dispatchers.IO) {
        withSnapshotLease { commitResultLocked(sessionId, inputHash, estimate, checkAllowed) }
    }
    private suspend fun commitResultLocked(sessionId: String, expectedHash: String, estimate: LeanEstimate,
        checkAllowed: () -> Unit): Boolean {
        val encoded = LeanCodec.estimateJson(estimate)
        return db.withTransaction {
            checkAllowed()
            val row = dao.session(sessionId) ?: return@withTransaction false
            if (row.state !in setOf("SEALED", "PARTIAL")) return@withTransaction false
            val trip = db.diaryDao().journey(row.journeyId) ?: return@withTransaction false
            if (trip.transport != Transport.MOTORCYCLE || trip.status != JourneyStatus.CONFIRMED || trip.endedAt == null)
                return@withTransaction false
            val source = input(row, dao.chunkInfos(row.id))
            if (source.inputHash != expectedHash || estimate.calibrationEpoch != source.calibration.epoch) return@withTransaction false
            val prior = dao.result(row.id)
            if (prior != null) return@withTransaction prior.inputHash == expectedHash
            dao.insert(LeanResultEntity(row.id, row.journeyId, expectedHash, estimate.algorithmVersion, estimate.calibrationEpoch, encoded))
            dao.update(row.copy(state = "COMPLETE"))
            true
        }
    }
    internal suspend fun cleanupCommittedRaw() = withContext(Dispatchers.IO) {
        withSnapshotLease { cleanupCommittedRawLocked() }
    }
    private suspend fun cleanupCommittedRawLocked() = db.withTransaction {
        for (result in dao.allResults()) {
            val row = dao.session(result.sessionId) ?: continue
            if (row.state != "COMPLETE") continue
            val saved = LeanCodec.estimate(result.payload)
            check(saved.algorithmVersion == result.algorithmVersion && saved.calibrationEpoch == result.calibrationEpoch &&
                saved.calibrationEpoch == LeanCodec.calibration(row.calibrationJson).epoch)
            val parts = dao.chunkInfos(row.id)
            if (parts.isNotEmpty()) check(input(row, parts).inputHash == result.inputHash)
            dao.clearRaw(row.id)
        }
    }

    internal suspend fun snapshot(): List<LeanBackupSession> = dao.allSessions().map { row ->
        val parts = dao.chunkInfos(row.id)
        val result = dao.result(row.id)
        val source = input(row, parts).let { if (result == null) it else it.copy(inputHash = result.inputHash) }
        LeanBackupSession(source, row.state, parts.map { it.manifest() }, result?.let { LeanCodec.estimate(it.payload) })
    }

    /** Caller holds the snapshot lease; no cache or external filename is trusted as input. */
    internal suspend fun writeRaw(session: LeanBackupSession, output: OutputStream) {
        val stream = DataOutputStream(output)
        for (part in session.chunks.sortedBy { it.partIndex }) {
            val stored = requireNotNull(dao.chunk(session.input.token.id, part.partIndex)) { "Yedek ham verisi eksik." }
            LeanArchive.validateChunk(session, part, stored.payload)
            stream.writeInt(stored.payload.size)
            stream.write(stored.payload)
        }
        stream.flush()
    }

    /** Staging and restored copies share the same quota, including reserved reader headroom. */
    internal suspend fun checkIncomingBudget(stagedBytes: Long) {
        require(stagedBytes >= 0 && dao.rawBytes() + stagedBytes + ioReserve <= byteBudget) {
            "Bekleyen sensör verisi ve geri yükleme geçici alanı 256 MiB sınırını aşıyor."
        }
    }

    /** The caller owns the raw lease and Room replacement transaction. */
    internal suspend fun restoreSessions(sessions: List<LeanBackupSession>, rawSources: Map<String, List<File>>) {
        val expected = sessions.filter { it.chunks.isNotEmpty() }.map { "lean/" + it.input.token.id + ".raw" }.toSet()
        require(rawSources.keys == expected) { "Yedek sensör parçaları eksik veya fazladan." }
        var remaining = rawSources.values.flatten().sumOf { if (it.isFile) it.length() else 0L }
        val cacheRoot = appContext.cacheDir.canonicalFile.path + File.separator
        for (session in sessions) {
            val source = session.input
            require(session.state in setOf("SEALED", "PARTIAL", "COMPLETE")) { "Açık sensör kaydı geri yüklenemez." }
            require(db.diaryDao().journey(source.token.journeyId) != null)
            val row = LeanSessionEntity(source.token.id, source.token.journeyId, source.token.generation,
                LeanCodec.calibrationJson(source.calibration), source.bootId, source.startedAtUtc,
                source.startedAtElapsedNanos, source.endedAtElapsedNanos, session.state,
                LeanCodec.reasonsJson(source.partialReasons), source.droppedSamples)
            dao.insert(row)
            val paths = rawSources["lean/" + source.token.id + ".raw"].orEmpty()
            require(paths.size == session.chunks.size)
            session.chunks.forEachIndexed { index, part ->
                val file = paths[index]
                require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalPath.startsWith(cacheRoot))
                require(file.length() == part.byteCount.toLong() && part.byteCount in 1..LeanCodec.MAX_CHUNK_BYTES)
                val bytes = file.readBytes()
                require(dao.rawBytes() + remaining + bytes.size <= byteBudget) { "Sensör geri yükleme bütçesi aşıldı." }
                require(LeanCodec.sha256(bytes) == part.sha256)
                val decoded = LeanCodec.decodeRecords(bytes).toList()
                require(decoded.size == part.recordCount && decoded.minOf { it.elapsedNanos } == part.firstElapsedNanos &&
                    decoded.maxOf { it.elapsedNanos } == part.lastElapsedNanos)
                dao.insert(LeanChunkEntity(row.id, part.partIndex, bytes, part.sha256, part.recordCount,
                    part.firstElapsedNanos, part.lastElapsedNanos))
                check(file.delete()) { "Sensör geçici dosyası kaldırılamadı." }
                remaining -= bytes.size
            }
            if (session.chunks.isNotEmpty() || session.result == null)
                require(input(row, dao.chunkInfos(row.id)).inputHash == source.inputHash) { "Sensör kaynak kimliği eşleşmedi." }
            session.result?.let { result ->
                require(session.state == "COMPLETE" && result.calibrationEpoch == source.calibration.epoch)
                dao.insert(LeanResultEntity(row.id, row.journeyId, source.inputHash, result.algorithmVersion,
                    result.calibrationEpoch, LeanCodec.estimateJson(result)))
            }
        }
        require(dao.rawBytes() + ioReserve <= byteBudget)
    }
    private fun input(row: LeanSessionEntity, parts: List<LeanChunkInfo>): LeanInputSession {
        val source = LeanInputSession(row.token(), LeanCodec.calibration(row.calibrationJson), row.bootId,
            row.startedAtUtc, row.startedAtElapsedNanos, row.endedAtElapsedNanos, "",
            LeanCodec.reasons(row.reasonsJson), row.droppedSamples)
        return source.copy(inputHash = LeanCodec.inputHash(source, parts.map { it.manifest() }))
    }
    private fun LeanSessionEntity.token() = LeanSessionToken(id, journeyId, generation)
    private fun LeanSessionEntity.matches(token: LeanSessionToken) =
        id == token.id && journeyId == token.journeyId && generation == token.generation
    private fun LeanChunkInfo.manifest() = LeanChunkManifest(partIndex, byteCount, sha256, recordCount, firstElapsedNanos, lastElapsedNanos)

    companion object {
        const val MAX_RAW_BYTES = 256L * 1024 * 1024
        internal const val MAX_CHUNKS = 65_536
        private val generations = AtomicLong(System.currentTimeMillis())
        private val gate = Mutex()
        @Volatile private var instance: LeanStore? = null
        fun get(context: Context): LeanStore = instance ?: synchronized(this) {
            instance ?: LeanStore(context.applicationContext, DiaryDatabase.get(context.applicationContext),
                scheduleAnalysis = { org.iz.navigation.power.PostJourneyAnalysis.get(context.applicationContext).request() })
                .also { instance = it }
        }
        internal suspend fun <T> withReadyRawMutation(
            awaitReady: suspend () -> Unit, invalidate: () -> Unit, action: suspend () -> T,
        ): T {
            awaitReady()
            invalidate()
            return withSnapshotLease(action)
        }
        private class SnapshotLease : AbstractCoroutineContextElement(Key) {
            companion object Key : CoroutineContext.Key<SnapshotLease>
        }
        internal suspend fun <T> withSnapshotLease(action: suspend () -> T): T =
            if (currentCoroutineContext()[SnapshotLease] != null) action()
            else gate.withLock { withContext(SnapshotLease()) { action() } }
    }
}
