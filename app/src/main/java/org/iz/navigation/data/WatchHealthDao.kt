package org.iz.navigation.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
internal interface WatchHealthDao {
    @Query("SELECT * FROM watch_health_samples WHERE journeyId=:id ORDER BY startAt,sessionId,sequence")
    fun observeJourneySamples(id: String): Flow<List<WatchHealthSample>>
    @Query("SELECT * FROM watch_health_sessions ORDER BY createdAt") fun observeSessions(): Flow<List<WatchHealthSession>>
    @Query("SELECT * FROM watch_health_samples ORDER BY startAt,sequence") fun observeSamples(): Flow<List<WatchHealthSample>>
    @Query("SELECT * FROM watch_health_sessions ORDER BY createdAt") suspend fun allSessions(): List<WatchHealthSession>
    @Query("SELECT * FROM watch_health_samples ORDER BY startAt,sequence") suspend fun allSamples(): List<WatchHealthSample>
    @Query("SELECT * FROM watch_health_sessions WHERE id=:id") suspend fun session(id: String): WatchHealthSession?
    @Query("SELECT * FROM watch_health_samples WHERE sessionId=:id AND sequence IN (:sequences)")
    suspend fun existing(id: String, sequences: List<Long>): List<WatchHealthSample>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSession(value: WatchHealthSession)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSamples(values: List<WatchHealthSample>)
    @Insert suspend fun restoreSessions(values: List<WatchHealthSession>)
    @Insert suspend fun restoreSamples(values: List<WatchHealthSample>)
    @Query("DELETE FROM watch_health_samples") suspend fun clearSamples()
    @Query("DELETE FROM watch_health_sessions") suspend fun clearSessions()
    @Query("DELETE FROM watch_health_samples WHERE journeyId=:journeyId AND (startAt < :startedAt OR startAt >= :endedAt OR endAt > :endedAt)")
    suspend fun pruneOutsideJourney(journeyId: String, startedAt: Long, endedAt: Long)
}
