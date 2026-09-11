package com.atay.iz.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.atay.iz.data.HealthMetric
import com.atay.iz.data.ImportedHealthSample
import java.time.Instant
import kotlin.reflect.KClass

internal const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
internal const val BACKGROUND_HEALTH_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

internal fun HealthMetric.recordType(): KClass<out Record> = when (this) {
    HealthMetric.HEART_RATE_BPM -> HeartRateRecord::class
    HealthMetric.TOTAL_CALORIES_KCAL -> TotalCaloriesBurnedRecord::class
    HealthMetric.STEPS -> StepsRecord::class
}
internal fun HealthMetric.readPermission(): String = HealthPermission.getReadPermission(recordType())

/** Counts describe this sync read, including replayed change records; they are not stored health totals. */
data class HealthSourceCounts(val records: Int = 0, val watchRecords: Int = 0,
    val missingDeviceRecords: Int = 0, val otherDeviceRecords: Int = 0, val samples: Int = 0)

internal class HealthConnectSource(private val client: HealthConnectClient, private val beforeRead: () -> Unit = {}) {
    val counts = mutableMapOf<HealthMetric, HealthSourceCounts>()
    private fun measured(record: Record): List<ImportedHealthSample> {
        val metric = when (record) {
            is HeartRateRecord -> HealthMetric.HEART_RATE_BPM
            is StepsRecord -> HealthMetric.STEPS
            is TotalCaloriesBurnedRecord -> HealthMetric.TOTAL_CALORIES_KCAL
            else -> return emptyList()
        }
        val samples = eligibleHealthSamples(record)
        val old = counts[metric] ?: HealthSourceCounts()
        val type = record.metadata.device?.type
        counts[metric] = old.copy(records = old.records + 1,
            watchRecords = old.watchRecords + if (type == Device.TYPE_WATCH) 1 else 0,
            missingDeviceRecords = old.missingDeviceRecords + if (type == null || type == Device.TYPE_UNKNOWN) 1 else 0,
            otherDeviceRecords = old.otherDeviceRecords + if (type != null && type != Device.TYPE_UNKNOWN && type != Device.TYPE_WATCH) 1 else 0,
            samples = old.samples + samples.size)
        return samples
    }
    suspend fun newToken(metric: HealthMetric): String {
        beforeRead()
        return client.getChangesToken(
        ChangesTokenRequest(recordTypes = setOf(metric.recordType()), dataOriginFilters = setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE))),
    )
    }

    suspend fun read(metric: HealthMetric, window: HealthReadWindow): List<ImportedHealthSample> = when (metric) {
        HealthMetric.HEART_RATE_BPM -> readType(HeartRateRecord::class, window)
        HealthMetric.TOTAL_CALORIES_KCAL -> readType(TotalCaloriesBurnedRecord::class, window)
        HealthMetric.STEPS -> readType(StepsRecord::class, window)
    }

    private suspend fun <T : Record> readType(type: KClass<T>, window: HealthReadWindow): List<ImportedHealthSample> =
        collectHealthPages<ImportedHealthSample> { token ->
            beforeRead()
            val response = client.readRecords(ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(window.startAt), Instant.ofEpochMilli(window.endAt)),
                dataOriginFilter = setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE)), pageToken = token,
            ))
            HealthPage(response.records.flatMap(::measured), response.pageToken)
        }

    suspend fun changes(token: String): CollectedHealthChanges<ImportedHealthSample> = collectHealthChanges(token) { cursor ->
        beforeRead()
        val response = client.getChanges(cursor)
        HealthChangePage(response.changes.mapNotNull { change ->
            when (change) {
                is UpsertionChange -> HealthSourceChange(change.record.metadata.id, measured(change.record))
                is DeletionChange -> HealthSourceChange(change.recordId, emptyList())
                else -> null
            }
        }, response.nextChangesToken, response.hasMore, response.changesTokenExpired)
    }
}

/** Metadata is supplied by Samsung Health; WATCH does not assert wrist wear duration. */
internal fun eligibleHealthSamples(record: Record): List<ImportedHealthSample> {
    val meta = record.metadata
    val device = meta.device
    if (meta.id.isBlank() || meta.dataOrigin.packageName != SAMSUNG_HEALTH_PACKAGE || device?.type != Device.TYPE_WATCH) return emptyList()
    fun sample(metric: HealthMetric, start: Instant, end: Instant, value: Double) = ImportedHealthSample(
        sourceId = meta.id, originPackage = meta.dataOrigin.packageName, deviceType = device.type,
        deviceManufacturer = device.manufacturer, deviceModel = device.model, metric = metric,
        startAt = start.toEpochMilli(), endAt = end.toEpochMilli(), value = value,
    )
    return when (record) {
        is HeartRateRecord -> record.samples.map { sample(HealthMetric.HEART_RATE_BPM, it.time, it.time, it.beatsPerMinute.toDouble()) }
        is TotalCaloriesBurnedRecord -> listOf(sample(HealthMetric.TOTAL_CALORIES_KCAL, record.startTime, record.endTime, record.energy.inKilocalories))
        is StepsRecord -> listOf(sample(HealthMetric.STEPS, record.startTime, record.endTime, record.count.toDouble()))
        else -> emptyList()
    }
}
