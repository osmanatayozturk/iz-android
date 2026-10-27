package org.iz.navigation.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "destination_shortcuts", foreignKeys = [ForeignKey(entity = Place::class,
    parentColumns = ["id"], childColumns = ["placeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("placeId")])
internal data class DestinationShortcutEntity(@PrimaryKey val kind: String, val placeId: String)

internal data class DestinationShortcutRow(val kind: String, @Embedded val place: Place)

@Entity(tableName = "lean_sessions", foreignKeys = [ForeignKey(entity = Journey::class,
    parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("journeyId")])
internal data class LeanSessionEntity(
    @PrimaryKey val id: String, val journeyId: String, val generation: Long,
    val calibrationJson: String, val bootId: String, val startedAtUtc: Long,
    val startedAtElapsedNanos: Long, val endedAtElapsedNanos: Long?,
    val state: String, val reasonsJson: String, val droppedSamples: Long,
)

@Entity(tableName = "lean_chunks", primaryKeys = ["sessionId", "partIndex"],
    foreignKeys = [ForeignKey(entity = LeanSessionEntity::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
internal data class LeanChunkEntity(
    val sessionId: String, val partIndex: Int, val payload: ByteArray,
    val sha256: String, val recordCount: Int, val firstElapsedNanos: Long, val lastElapsedNanos: Long,
)

@Entity(tableName = "lean_results", foreignKeys = [
    ForeignKey(entity = LeanSessionEntity::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Journey::class, parentColumns = ["id"],
        childColumns = ["journeyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("journeyId")])
internal data class LeanResultEntity(
    @PrimaryKey val sessionId: String, val journeyId: String, val inputHash: String,
    val algorithmVersion: String, val calibrationEpoch: String, val payload: String,
)

internal data class LeanChunkInfo(
    val sessionId: String, val partIndex: Int, val byteCount: Int, val sha256: String,
    val recordCount: Int, val firstElapsedNanos: Long, val lastElapsedNanos: Long,
)
@Dao
internal interface LeanDao {
    @Query("SELECT sessionId, partIndex, length(payload) AS byteCount, sha256, recordCount, firstElapsedNanos, lastElapsedNanos FROM lean_chunks WHERE sessionId = :id ORDER BY partIndex") suspend fun chunkInfos(id: String): List<LeanChunkInfo>
    @Query("SELECT * FROM lean_chunks WHERE sessionId = :id AND partIndex = :part") fun readChunk(id: String, part: Int): LeanChunkEntity?
    @Query("SELECT COUNT(*) FROM lean_chunks") suspend fun chunkCount(): Int

    @Query("SELECT * FROM lean_sessions WHERE id = :id") suspend fun session(id: String): LeanSessionEntity?
    @Query("SELECT * FROM lean_sessions ORDER BY startedAtUtc, id") suspend fun allSessions(): List<LeanSessionEntity>
    @Query("SELECT s.* FROM lean_sessions s INNER JOIN journeys j ON j.id = s.journeyId WHERE s.journeyId = :journeyId AND j.transport = 'MOTORCYCLE' ORDER BY s.startedAtUtc, s.id") fun sessions(journeyId: String): Flow<List<LeanSessionEntity>>
    @Query("SELECT * FROM lean_sessions WHERE state IN ('SEALED', 'PARTIAL') AND id NOT IN (SELECT sessionId FROM lean_results) ORDER BY startedAtUtc, id") suspend fun pending(): List<LeanSessionEntity>
    @Query("SELECT * FROM lean_chunks WHERE sessionId = :id ORDER BY partIndex") suspend fun chunks(id: String): List<LeanChunkEntity>
    @Query("SELECT * FROM lean_chunks WHERE sessionId = :id AND partIndex = :part") suspend fun chunk(id: String, part: Int): LeanChunkEntity?
    @Query("SELECT COALESCE(SUM(length(payload)), 0) FROM lean_chunks") suspend fun rawBytes(): Long
    @Query("SELECT * FROM lean_results WHERE sessionId = :id") suspend fun result(id: String): LeanResultEntity?
    @Query("SELECT * FROM lean_results ORDER BY sessionId") suspend fun allResults(): List<LeanResultEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: LeanSessionEntity)
    @Update suspend fun update(value: LeanSessionEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: LeanChunkEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: LeanResultEntity)
    @Query("DELETE FROM lean_chunks WHERE sessionId = :id") suspend fun clearRaw(id: String)
    @Query("DELETE FROM lean_sessions WHERE journeyId = :journeyId") suspend fun clearJourney(journeyId: String)
    @Query("DELETE FROM lean_sessions") suspend fun clear()
}

internal val LEAN_SCHEMA_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS destination_shortcuts (kind TEXT NOT NULL PRIMARY KEY, placeId TEXT NOT NULL, FOREIGN KEY(placeId) REFERENCES places(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS index_destination_shortcuts_placeId ON destination_shortcuts(placeId)",
    "CREATE TABLE IF NOT EXISTS lean_sessions (id TEXT NOT NULL PRIMARY KEY, journeyId TEXT NOT NULL, generation INTEGER NOT NULL, calibrationJson TEXT NOT NULL, bootId TEXT NOT NULL, startedAtUtc INTEGER NOT NULL, startedAtElapsedNanos INTEGER NOT NULL, endedAtElapsedNanos INTEGER, state TEXT NOT NULL, reasonsJson TEXT NOT NULL, droppedSamples INTEGER NOT NULL, FOREIGN KEY(journeyId) REFERENCES journeys(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS index_lean_sessions_journeyId ON lean_sessions(journeyId)",
    "CREATE TABLE IF NOT EXISTS lean_chunks (sessionId TEXT NOT NULL, partIndex INTEGER NOT NULL, payload BLOB NOT NULL, sha256 TEXT NOT NULL, recordCount INTEGER NOT NULL, firstElapsedNanos INTEGER NOT NULL, lastElapsedNanos INTEGER NOT NULL, PRIMARY KEY(sessionId, partIndex), FOREIGN KEY(sessionId) REFERENCES lean_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE TABLE IF NOT EXISTS lean_results (sessionId TEXT NOT NULL PRIMARY KEY, journeyId TEXT NOT NULL, inputHash TEXT NOT NULL, algorithmVersion TEXT NOT NULL, calibrationEpoch TEXT NOT NULL, payload TEXT NOT NULL, FOREIGN KEY(sessionId) REFERENCES lean_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(journeyId) REFERENCES journeys(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS index_lean_results_journeyId ON lean_results(journeyId)",
)
