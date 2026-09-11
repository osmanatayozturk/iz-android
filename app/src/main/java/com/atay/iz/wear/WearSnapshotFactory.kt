package com.atay.iz.wear

import com.atay.iz.data.*
import com.atay.iz.tracking.TrackingPolicy
import com.atay.iz.wearprotocol.*

internal object WearSnapshotFactory {
    fun create(journey: Journey?, points: List<TrackPoint>, now: Long, runningId: String?,
        health: JourneyHealthSummary? = null, weather: WearRouteWeather? = null): WearSnapshot {
        if (journey == null || journey.endedAt != null) return WearSnapshot(generatedAt = now)
        val stats = JourneyStatistics.calculate(journey, points, now)
        val tail = points.filter { it.journeyId == journey.id && it.recordedAt in journey.startedAt..now }
            .sortedBy { it.recordedAt }.takeLast(2)
        val current = if (tail.size == 2 && now - tail.last().recordedAt < WearProtocol.STATE_TTL_MS &&
            DiaryRules.connects(tail[0], tail[1])) {
            DiaryRules.distanceMeters(tail) * 3_600.0 / (tail[1].recordedAt - tail[0].recordedAt)
        } else null
        return WearSnapshot(generatedAt = now, journeyId = journey.id,
            mode = WearMode.entries.firstOrNull { it.name == journey.transport.name },
            startedAt = journey.startedAt, temporary = journey.status == JourneyStatus.TEMPORARY,
            deadlineAt = TrackingPolicy.automaticCandidateDeadline(journey), recording = runningId == journey.id,
            distanceMeters = stats.distanceMeters, elapsedMillis = stats.elapsedMillis,
            averageSpeedKmh = stats.averageSpeedKmh, maxSpeedKmh = stats.maxSpeedKmh,
            currentSpeedKmh = if (runningId == journey.id) current else null,
            stepCount = journey.stepCount.takeIf { journey.transport.supportsSteps },
            averagePaceSecondsPerKm = if (journey.transport == Transport.RUN && stats.distanceMeters > 0 && stats.elapsedMillis > 0)
                stats.elapsedMillis.toDouble() / stats.distanceMeters else null,
            health = health?.takeIf { it.journeyId == journey.id }?.let { summary ->
                WearHealthSummary(heartRateMeanBpm = summary.heartRateMeanBpm,
                    heartRateMinBpm = summary.heartRateMinBpm, heartRateMaxBpm = summary.heartRateMaxBpm,
                    latestHeartRateBpm = summary.latestHeartRateBpm, latestHeartRateAt = summary.latestHeartRateAt,
                    heartRateSampleCount = summary.heartRateSampleCount, totalCaloriesKcal = summary.totalCaloriesKcal,
                    calorieCoverageMillis = summary.calorieCoverageMillis, watchSteps = summary.watchSteps,
                    stepCoverageMillis = summary.stepCoverageMillis, measurementStartAt = summary.measurementStartAt,
                    measurementEndAt = summary.measurementEndAt, lastCheckedAt = summary.lastCheckedAt,
                    partial = summary.heartRateSampleCount == 0 || summary.totalCaloriesKcal == null || summary.watchSteps == null ||
                        summary.calorieCoverageMillis < stats.elapsedMillis || summary.stepCoverageMillis < stats.elapsedMillis)
            },
            weather = weather.takeIf { runningId == journey.id },
            candidateProgressMeters = TrackingPolicy.automaticCandidateProgress(journey, points))
    }
}
