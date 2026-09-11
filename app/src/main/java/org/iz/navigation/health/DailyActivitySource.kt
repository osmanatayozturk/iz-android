package org.iz.navigation.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/** All-day Samsung source deliberately does not reuse the strict journey WATCH reader. */
internal class DailyActivitySource(private val client: HealthConnectClient, private val beforeRead: () -> Unit) : DailyHealthReader {
    private val samsung = setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE))
    private fun DailyRange.filter() = TimeRangeFilter.between(Instant.ofEpochMilli(startAt), Instant.ofEpochMilli(endAt))
    override suspend fun steps(range: DailyRange): Long? {
        beforeRead()
        return client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range.filter(), samsung))[StepsRecord.COUNT_TOTAL]
    }
    override suspend fun exercises(range: DailyRange): List<DailyExercise> = collectHealthPages { token ->
        beforeRead()
        val response = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range.filter(), dataOriginFilter = samsung, pageToken = token))
        HealthPage(response.records.filter { it.metadata.dataOrigin.packageName == SAMSUNG_HEALTH_PACKAGE }.map { record ->
            DailyExercise(DailyRange(record.startTime.toEpochMilli(), record.endTime.toEpochMilli()), record.exerciseType in setOf(
                ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
        }, response.pageToken)
    }
    override suspend fun distance(range: DailyRange): Double? {
        beforeRead()
        return client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), range.filter(), samsung))[DistanceRecord.DISTANCE_TOTAL]?.inMeters
    }
}
