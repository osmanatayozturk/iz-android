package org.iz.navigation.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class HealthMetric { HEART_RATE_BPM, TOTAL_CALORIES_KCAL, STEPS }

/** One actual HC sample or interval, before association with an existing journey. Times are epoch millis. */
data class ImportedHealthSample(
    val sourceId: String,
    val originPackage: String,
    val deviceType: Int?,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val metric: HealthMetric,
    val startAt: Long,
    val endAt: Long,
    val value: Double,
) {
    fun forJourney(journeyId: String) = JourneyHealthSample(journeyId, sourceId, originPackage, deviceType,
        deviceManufacturer, deviceModel, metric, startAt, endAt, value)
}

@Entity(tableName = "journey_health_samples",
    primaryKeys = ["journeyId", "sourceId", "metric", "startAt", "endAt"],
    foreignKeys = [ForeignKey(entity = Journey::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("journeyId"), Index("sourceId")])
data class JourneyHealthSample(
    val journeyId: String,
    val sourceId: String,
    val originPackage: String,
    val deviceType: Int?,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val metric: HealthMetric,
    val startAt: Long,
    val endAt: Long,
    val value: Double,
) {
    fun imported() = ImportedHealthSample(sourceId, originPackage, deviceType, deviceManufacturer,
        deviceModel, metric, startAt, endAt, value)
}

/** Local successful reads, including empty responses; never grants permission and never enters a backup. */
@Entity(tableName = "journey_health_sync", primaryKeys = ["journeyId", "metric"],
    foreignKeys = [ForeignKey(entity = Journey::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("journeyId")])
data class JourneyHealthSync(val journeyId: String, val metric: HealthMetric, val checkedAt: Long)

data class JourneyHealthSummary(
    val journeyId: String,
    val heartRateMeanBpm: Double? = null,
    val heartRateMinBpm: Double? = null,
    val heartRateMaxBpm: Double? = null,
    val latestHeartRateBpm: Double? = null,
    val latestHeartRateAt: Long? = null,
    val heartRateSampleCount: Int = 0,
    val totalCaloriesKcal: Double? = null,
    val calorieCoverageMillis: Long = 0,
    val watchSteps: Long? = null,
    val stepCoverageMillis: Long = 0,
    val measurementStartAt: Long? = null,
    val measurementEndAt: Long? = null,
    val metricCheckedAt: Map<HealthMetric, Long> = emptyMap(),
) {
    val lastCheckedAt: Long? get() = metricCheckedAt.values.maxOrNull()
    val checkedMetrics: Set<HealthMetric> get() = metricCheckedAt.keys
}
