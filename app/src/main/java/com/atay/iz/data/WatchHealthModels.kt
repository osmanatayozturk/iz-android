package com.atay.iz.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Direct readings are deliberately separate from Samsung Health's replaceable imports. */
@Entity(tableName = "watch_health_sessions", foreignKeys = [ForeignKey(entity = Journey::class,
    parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.CASCADE)], indices = [Index("journeyId")])
data class WatchHealthSession(@PrimaryKey val id: String, val journeyId: String, val watchId: String,
    val deviceName: String, val createdAt: Long, @ColumnInfo(defaultValue = "1") val acceptsUploads: Boolean = true)

@Entity(tableName = "watch_health_samples", primaryKeys = ["sessionId", "sequence"],
    foreignKeys = [ForeignKey(entity = Journey::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WatchHealthSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("journeyId"), Index("sessionId")])
data class WatchHealthSample(val sessionId: String, val sequence: Long, val journeyId: String,
    val metric: HealthMetric, val startAt: Long, val endAt: Long, val value: Double)

object WatchHealthRules {
    const val MAX_FUTURE_RETRY_MILLIS = 5 * 60_000L
    private fun id(value: String) = value.isNotBlank() && value.length <= 200 && value.none { it.isWhitespace() || it.isISOControl() }
    fun valid(value: WatchHealthSample): Boolean = id(value.sessionId) && id(value.journeyId) && value.sequence > 0 &&
        value.startAt > 0 && value.endAt >= value.startAt && value.value.isFinite() && when (value.metric) {
            HealthMetric.HEART_RATE_BPM -> value.endAt == value.startAt && value.value in 1.0..300.0
            HealthMetric.STEPS -> value.endAt > value.startAt && value.value in 0.0..1_000_000.0 && value.value % 1.0 == 0.0
            HealthMetric.TOTAL_CALORIES_KCAL -> false
        }
    fun belongsTo(value: WatchHealthSample, journey: Journey, now: Long = System.currentTimeMillis()): Boolean =
        valid(value) && value.journeyId == journey.id && journey.status == JourneyStatus.CONFIRMED &&
            value.startAt >= journey.startedAt && value.startAt < (journey.endedAt ?: now) && value.endAt <= (journey.endedAt ?: now)
    fun validateSnapshot(journeys: List<Journey>, sessions: List<WatchHealthSession>, samples: List<WatchHealthSample>) {
        val trips = journeys.associateBy { it.id }; val bySession = sessions.associateBy { it.id }
        require(bySession.size == sessions.size && samples.map { it.sessionId to it.sequence }.toSet().size == samples.size)
        require(sessions.all { s -> id(s.id) && id(s.watchId) && s.deviceName.length <= 200 && s.deviceName.none { it.isISOControl() } &&
            trips[s.journeyId]?.let { it.status == JourneyStatus.CONFIRMED && s.createdAt >= it.startedAt } == true })
        require(samples.all { s -> val session = bySession[s.sessionId]; val trip = trips[s.journeyId]
            session != null && session.journeyId == s.journeyId && s.startAt >= session.createdAt &&
                trip != null && belongsTo(s, trip, Long.MAX_VALUE) }) { "Invalid direct watch health data" }
    }
    fun summarize(journey: Journey, samples: List<WatchHealthSample>, now: Long = System.currentTimeMillis()): JourneyHealthSummary {
        val valid = samples.distinctBy { it.sessionId to it.sequence }.filter { belongsTo(it, journey, now) }
        val heart = valid.filter { it.metric == HealthMetric.HEART_RATE_BPM }.sortedBy { it.startAt }
        val steps = valid.filter { it.metric == HealthMetric.STEPS }.sortedBy { it.startAt }
        // A single physical watch session owns each captured period. Exclude overlapping intervals.
        val unambiguous = mutableListOf<WatchHealthSample>()
        var first = 0
        while (first < steps.size) {
            var end = first + 1
            var boundary = steps[first].endAt
            while (end < steps.size && steps[end].startAt < boundary) {
                boundary = maxOf(boundary, steps[end].endAt); end++
            }
            if (end == first + 1) unambiguous += steps[first]
            first = end
        }
        return JourneyHealthSummary(journeyId = journey.id, heartRateMeanBpm = heart.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
            heartRateMinBpm = heart.minOfOrNull { it.value }, heartRateMaxBpm = heart.maxOfOrNull { it.value },
            latestHeartRateBpm = heart.lastOrNull()?.value, latestHeartRateAt = heart.lastOrNull()?.startAt,
            heartRateSampleCount = heart.size, watchSteps = unambiguous.takeIf { it.isNotEmpty() }?.sumOf { it.value.toLong() },
            stepCoverageMillis = unambiguous.sumOf { it.endAt - it.startAt }, measurementStartAt = valid.minOfOrNull { it.startAt },
            measurementEndAt = valid.maxOfOrNull { it.endAt })
    }
}

internal class WatchHealthRepository(context: Context) {
    private val db = DiaryDatabase.get(context.applicationContext)
    private val dao get() = db.watchHealthDao()
    val samples: Flow<List<WatchHealthSample>> get() = dao.observeSamples()
    fun samplesForJourney(id: String): Flow<List<WatchHealthSample>> = dao.observeJourneySamples(id)
    val sessions: Flow<List<WatchHealthSession>> get() = dao.observeSessions()
    suspend fun session(id: String) = dao.session(id)
    suspend fun register(value: WatchHealthSession): Boolean = db.withTransaction {
        val trip = db.diaryDao().journey(value.journeyId) ?: return@withTransaction false
        if (trip.status != JourneyStatus.CONFIRMED || trip.endedAt != null || trip.interrupted) return@withTransaction false
        WatchHealthRules.validateSnapshot(listOf(trip), listOf(value), emptyList())
        val old = dao.session(value.id)
        if (old != null) return@withTransaction old.acceptsUploads && old == value
        dao.insertSession(value); true
    }
    /** False is terminal: a cleared/deleted/restored session must never be recreated by a delayed batch. */
    suspend fun import(watchId: String, sessionId: String, values: List<WatchHealthSample>, now: Long): Boolean = db.withTransaction {
        val session = dao.session(sessionId) ?: return@withTransaction false
        if (session.watchId != watchId || !session.acceptsUploads) return@withTransaction false
        val trip = db.diaryDao().journey(session.journeyId) ?: return@withTransaction false
        require(values.all { it.sessionId == sessionId && it.journeyId == session.journeyId && WatchHealthRules.valid(it) })
        val bySequence = values.associateBy { it.sequence }
        if (values.any { bySequence[it.sequence] != it } ||
            dao.existing(sessionId, values.map { it.sequence }).any { bySequence[it.sequence] != it }) return@withTransaction false
        // A valid open-session row slightly ahead of the phone clock must remain in the watch queue.
        // Definitively outside a closed trip can be dropped; implausible clock jumps revoke this transport.
        val future = values.filter { it.startAt >= session.createdAt &&
            WatchHealthRules.belongsTo(it, trip, Long.MAX_VALUE) && it.endAt > now }
        if (future.any { it.endAt - now > WatchHealthRules.MAX_FUTURE_RETRY_MILLIS }) return@withTransaction false
        if (future.isNotEmpty()) throw WatchHealthTimePending()
        dao.insertSamples(values.filter { it.startAt >= session.createdAt && WatchHealthRules.belongsTo(it, trip, now) })
        true
    }
}

internal class WatchHealthTimePending : Exception()
