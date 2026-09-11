package com.atay.iz.data

import org.junit.Assert.*
import org.junit.Test

class HealthRulesTest {
    private val journey = Journey(id = "trip", startedAt = 1_000, endedAt = 5_000)
    private fun sample(id: String, start: Long, end: Long = start, value: Double = 80.0,
        metric: HealthMetric = HealthMetric.HEART_RATE_BPM) = ImportedHealthSample(
        id, "com.sec.android.app.shealth", 1, "Samsung", "Watch8 Classic", metric, start, end, value)
    private fun summary(vararg values: ImportedHealthSample) = HealthRules.summarize(
        journey, values.map { it.forJourney(journey.id) }, now = 6_000)

    @Test fun heartRateUsesOnlyRealWatchSamplesWithinHalfOpenJourneyRange() {
        val result = summary(sample("at-start", 1_000, value = 60.0), sample("later", 3_000, value = 100.0),
            sample("before", 999), sample("at-end", 5_000), sample("phone", 2_000).copy(deviceType = 2),
            sample("unknown", 2_100).copy(deviceType = null), sample("other-app", 2_200).copy(originPackage = "other"))
        assertNotNull("Valid in-trip watch HR must be summarized", result.heartRateMeanBpm)
        assertEquals(80.0, result.heartRateMeanBpm!!, 0.0)
        assertEquals(60.0, result.heartRateMinBpm!!, 0.0)
        assertEquals(100.0, result.heartRateMaxBpm!!, 0.0)
        assertEquals(100.0, result.latestHeartRateBpm!!, 0.0)
        assertEquals(3_000L, result.latestHeartRateAt)
        assertEquals(2, result.heartRateSampleCount)
    }

    @Test fun intervalsAreContainedAndNeverProratedAndZeroIsNotMissing() {
        val result = summary(sample("zero", 1_000, 2_000, 0.0, HealthMetric.TOTAL_CALORIES_KCAL),
            sample("inside", 2_000, 4_000, 12.0, HealthMetric.TOTAL_CALORIES_KCAL),
            sample("straddles", 4_500, 5_500, 30.0, HealthMetric.TOTAL_CALORIES_KCAL),
            sample("steps", 1_000, 5_000, 0.0, HealthMetric.STEPS))
        assertNotNull("Contained calorie intervals must be summarized", result.totalCaloriesKcal)
        assertEquals(12.0, result.totalCaloriesKcal!!, 0.0)
        assertEquals(3_000L, result.calorieCoverageMillis)
        assertEquals(0L, result.watchSteps)
        assertEquals(4_000L, result.stepCoverageMillis)
        assertNull(summary().totalCaloriesKcal)
        assertNull(summary().watchSteps)
        assertNull(summary().latestHeartRateAt)
    }

    @Test fun duplicateIntervalsCountOnceAndConflictingOverlapsAreExcluded() {
        val a = sample("a", 1_000, 2_000, 10.0, HealthMetric.TOTAL_CALORIES_KCAL)
        val result = summary(a, a.copy(sourceId = "duplicate"),
            sample("b", 2_500, 3_500, 20.0, HealthMetric.TOTAL_CALORIES_KCAL),
            sample("c", 3_000, 4_000, 30.0, HealthMetric.TOTAL_CALORIES_KCAL))
        assertNotNull("Non-conflicting real calories must survive deduplication", result.totalCaloriesKcal)
        assertEquals(10.0, result.totalCaloriesKcal!!, 0.0)
        assertEquals(1_000L, result.calorieCoverageMillis)
    }

    @Test fun invalidNumbersAndForeignJourneyRecordsNeverContribute() {
        val invalid = listOf(sample("nan", 2_000, value = Double.NaN), sample("zero-hr", 2_200, value = 0.0),
            sample("negative", 2_300, 2_400, -1.0, HealthMetric.STEPS),
            sample("fractional", 2_400, 2_500, 1.5, HealthMetric.STEPS),
            sample("backwards", 3_000, 2_000, 10.0, HealthMetric.TOTAL_CALORIES_KCAL))
        val result = HealthRules.summarize(journey, invalid.map { it.forJourney(journey.id) } +
            sample("foreign", 2_000).forJourney("another"), now = 6_000)
        assertNull(result.heartRateMeanBpm)
        assertNull(result.totalCaloriesKcal)
        assertNull(result.watchSteps)
    }

    @Test fun successfulEmptyReadIsDistinctFromNeverChecked() {
        val result = HealthRules.summarize(journey, emptyList(),
            listOf(JourneyHealthSync(journey.id, HealthMetric.HEART_RATE_BPM, 8_000)), now = 9_000)
        assertNull(result.latestHeartRateBpm)
        assertEquals(8_000L, result.metricCheckedAt[HealthMetric.HEART_RATE_BPM])
        assertFalse(HealthMetric.STEPS in result.checkedMetrics)
        assertEquals(8_000L, result.lastCheckedAt)
    }

    @Test fun unconfirmedAutomaticCandidatesCannotOwnHealthData() {
        val candidate = journey.copy(status = JourneyStatus.TEMPORARY, expiresAt = 10_000)
        assertFalse(HealthRules.belongsTo(sample("pending", 2_000), candidate, 6_000))
        assertNull(HealthRules.summarize(candidate, listOf(sample("pending", 2_000).forJourney(candidate.id)), now = 6_000).latestHeartRateBpm)
    }

    @Test fun duplicateHeartSamplesHaveNoExtraWeightAndConflictingInstantsStayUnknown() {
        val result = summary(sample("one", 1_000, value = 60.0), sample("copy", 1_000, value = 60.0),
            sample("conflict-a", 2_000, value = 90.0), sample("conflict-b", 2_000, value = 100.0),
            sample("last", 3_000, value = 80.0))
        assertEquals(2, result.heartRateSampleCount)
        assertEquals(70.0, result.heartRateMeanBpm!!, 0.0)
        assertEquals(3_000L, result.latestHeartRateAt)
    }

    @Test fun runPreservesMeasuredStepsWhenConfirmingOrValidatingBackup() {
        val run = Transport.entries.find { it.name == "RUN" }
        assertNotNull("RUN must be represented without changing existing enum values", run)
        val pending = Journey(transport = Transport.WALK, stepCount = 42, status = JourneyStatus.TEMPORARY)
        assertEquals(42L, DiaryRules.confirmed(pending, run!!).stepCount)
        DiaryRules.validate(DiarySnapshot(journeys = listOf(pending.copy(transport = run))))
        assertEquals(listOf("CAR", "MOTORCYCLE", "BICYCLE", "WALK", "UNKNOWN", "PASSENGER"),
            Transport.entries.take(6).map { it.name })
    }
}
