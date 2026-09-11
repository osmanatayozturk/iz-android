package org.iz.navigation.data

object HealthRules {
    const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
    const val WATCH_DEVICE_TYPE = 1

    fun isValid(sample: ImportedHealthSample): Boolean =
        sample.sourceId.isNotBlank() && sample.sourceId.length <= 1_024 && sample.sourceId.none { it.code < 32 } &&
            sample.originPackage == SAMSUNG_HEALTH_PACKAGE && sample.deviceType == WATCH_DEVICE_TYPE &&
            (sample.deviceManufacturer == null || sample.deviceManufacturer.length <= 200) &&
            (sample.deviceModel == null || sample.deviceModel.length <= 200) &&
            sample.startAt > 0 && sample.endAt >= sample.startAt && sample.value.isFinite() &&
            when (sample.metric) {
                HealthMetric.HEART_RATE_BPM -> sample.startAt == sample.endAt && sample.value in 1.0..300.0
                HealthMetric.TOTAL_CALORIES_KCAL -> sample.endAt > sample.startAt && sample.value in 0.0..1_000_000.0
                HealthMetric.STEPS -> sample.endAt > sample.startAt && sample.value in 0.0..1_000_000.0 && sample.value % 1.0 == 0.0
            }

    fun belongsTo(sample: ImportedHealthSample, journey: Journey, now: Long): Boolean {
        if (!isValid(sample) || journey.status != JourneyStatus.CONFIRMED || DiaryRules.isExpired(journey, now)) return false
        val end = minOf(journey.endedAt ?: now, now)
        return sample.startAt >= journey.startedAt && sample.startAt < end && sample.endAt <= end
    }

    fun summarize(journey: Journey, samples: List<JourneyHealthSample>,
        syncs: List<JourneyHealthSync> = emptyList(), now: Long = System.currentTimeMillis()): JourneyHealthSummary {
        val valid = samples.filter { it.journeyId == journey.id && belongsTo(it.imported(), journey, now) }
        val heart = observedHeartRateSamples(journey, valid, now)
        val calories = unambiguousIntervals(valid.filter { it.metric == HealthMetric.TOTAL_CALORIES_KCAL })
        val steps = unambiguousIntervals(valid.filter { it.metric == HealthMetric.STEPS })
        val used = heart + calories + steps
        return JourneyHealthSummary(
            journeyId = journey.id,
            heartRateMeanBpm = heart.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
            heartRateMinBpm = heart.minOfOrNull { it.value }, heartRateMaxBpm = heart.maxOfOrNull { it.value },
            latestHeartRateBpm = heart.lastOrNull()?.value, latestHeartRateAt = heart.lastOrNull()?.startAt,
            heartRateSampleCount = heart.size,
            totalCaloriesKcal = calories.takeIf { it.isNotEmpty() }?.sumOf { it.value },
            calorieCoverageMillis = calories.sumOf { it.endAt - it.startAt },
            watchSteps = steps.takeIf { it.isNotEmpty() }?.sumOf { it.value.toLong() },
            stepCoverageMillis = steps.sumOf { it.endAt - it.startAt },
            measurementStartAt = used.minOfOrNull { it.startAt }, measurementEndAt = used.maxOfOrNull { it.endAt },
            metricCheckedAt = syncs.filter { it.journeyId == journey.id }.groupBy { it.metric }
                .mapValues { (_, values) -> values.maxOf { it.checkedAt } },
        )
    }

    /** The same observed series used by summaries and charts: no interpolation or fabricated coverage. */
    fun observedHeartRateSamples(journey: Journey, samples: List<JourneyHealthSample>,
        now: Long = System.currentTimeMillis()): List<JourneyHealthSample> = samples
        .filter { it.journeyId == journey.id && it.metric == HealthMetric.HEART_RATE_BPM && belongsTo(it.imported(), journey, now) }
        .groupBy { it.startAt }.values
        .mapNotNull { atTime -> atTime.takeIf { it.map { sample -> sample.value }.distinct().size == 1 }?.first() }
        .sortedBy { it.startAt }

    /** Whole records only. Deduplicate exact values, then exclude every interval in an overlapping group. */
    private fun unambiguousIntervals(samples: List<JourneyHealthSample>): List<JourneyHealthSample> {
        val sorted = samples.distinctBy { Triple(it.startAt, it.endAt, it.value) }
            .sortedWith(compareBy({ it.startAt }, { it.endAt }))
        val result = mutableListOf<JourneyHealthSample>()
        var index = 0
        while (index < sorted.size) {
            val first = sorted[index]
            var end = first.endAt
            var next = index + 1
            while (next < sorted.size && sorted[next].startAt < end) {
                end = maxOf(end, sorted[next].endAt)
                next++
            }
            if (next == index + 1) result += first
            index = next
        }
        return result
    }

    internal fun validateSnapshot(snapshot: DiarySnapshot) {
        val journeys = snapshot.journeys.associateBy { it.id }
        val keys = snapshot.healthSamples.map { listOf(it.journeyId, it.sourceId, it.metric, it.startAt, it.endAt) }
        require(keys.size == keys.distinct().size) { "Duplicate health sample." }
        require(snapshot.healthSamples.all { sample ->
            val journey = journeys[sample.journeyId]
            journey != null && journey.status == JourneyStatus.CONFIRMED && isValid(sample.imported()) && sample.startAt >= journey.startedAt &&
                (journey.endedAt == null || sample.startAt < journey.endedAt && sample.endAt <= journey.endedAt)
        }) { "Invalid health source, measurement or journey association." }
    }
}
