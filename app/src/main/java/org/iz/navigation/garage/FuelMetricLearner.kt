package org.iz.navigation.garage

import kotlin.math.abs
import kotlin.math.pow

internal data class FuelWeightSetting(val orderHalfLife: Int = 6, val calendarPercent: Int = 0)

/** One chronological stream for one vehicle and one metric. Amount/distance pairs keep their exposure. */
internal class FuelMetricLearner(private val minimumImprovement: Double) {
    private val wholeHistory = WeightedRatio()
    private val calendar = WeightedRatio()
    private val orders = listOf(3, 6, 12).associateWith { WeightedRatio() }
    private val candidates = orders.keys.flatMap { halfLife ->
        listOf(0, 10, 20).map { percent -> FuelWeightSetting(halfLife, percent) }
    }.associateWith { ForecastErrors() }
    private var lastAt: Long? = null

    var setting = FuelWeightSetting()
        private set
    var scoredCount = 0
        private set

    val estimate: Double? get() = prediction(setting)

    fun observe(at: Long, numerator: Double, denominator: Double = 1.0, score: Boolean = true) {
        val previous = lastAt
        require(at >= 0 && (previous == null || at >= previous))
        require(numerator.isFinite() && numerator >= 0 && denominator.isFinite() && denominator > 0)
        val actual = numerator / denominator
        require(actual.isFinite())

        if (score) scoreOutcome(actual)
        wholeHistory.add(1.0, numerator, denominator)
        val days = if (previous == null) 0.0 else (at - previous) / MILLIS_PER_DAY
        calendar.add(0.5.pow(days / 84.0), numerator, denominator)
        for ((halfLife, component) in orders) component.add(ORDER_DECAY.getValue(halfLife), numerator, denominator)
        lastAt = at
    }

    /** Score using only information available before a fill confirms any earlier partial purchases. */
    fun scoreOutcome(actual: Double) {
        require(actual.isFinite() && actual >= 0)
        if (estimate == null) return
        for ((candidate, errors) in candidates) {
            errors.add(abs(actual - requireNotNull(prediction(candidate))))
        }
        scoredCount++
        if (scoredCount >= 12) selectForNextObservation()
    }

    private fun prediction(candidate: FuelWeightSetting): Double? {
        val lifetime = wholeHistory.value ?: return null
        val byOrder = orders.getValue(candidate.orderHalfLife).value ?: return null
        val byDate = calendar.value ?: return null
        // Subtracting the common level preserves exact ties on constant histories.
        return lifetime + (0.75 - candidate.calendarPercent / 100.0) * (byOrder - lifetime) +
            candidate.calendarPercent / 100.0 * (byDate - lifetime)
    }

    private fun selectForNextObservation() {
        val current = candidates.getValue(setting)
        var winner = setting
        var bestError = current.score
        var tied = false
        for ((candidate, errors) in candidates) {
            val orderMove = candidate.orderHalfLife != setting.orderHalfLife && candidate.calendarPercent == setting.calendarPercent
            val calendarMove = candidate.orderHalfLife == setting.orderHalfLife &&
                abs(candidate.calendarPercent - setting.calendarPercent) == 10
            if (!orderMove && !calendarMove) continue
            val improvement = current.score - errors.score
            if (improvement < minimumImprovement || improvement < current.score * 0.05 || !errors.wonLastThree(current)) continue
            if (errors.score < bestError) {
                bestError = errors.score
                winner = candidate
                tied = false
            } else if (errors.score == bestError) tied = true
        }
        if (!tied) setting = winner
    }

    /** Two equally discounted averages give sum(weight * numerator) / sum(weight * denominator). */
    private class WeightedRatio {
        private var mass = 0.0
        private var numeratorMean = 0.0
        private var denominatorMean = 0.0
        val value: Double? get() = if (mass == 0.0) null else numeratorMean / denominatorMean

        fun add(decay: Double, numerator: Double, denominator: Double) {
            val retained = mass * decay
            mass = retained + 1.0
            fun blend(previous: Double, next: Double) = if (retained >= 1.0) {
                previous + (next - previous) / mass
            } else {
                next + (previous - next) * (retained / mass)
            }
            numeratorMean = blend(numeratorMean, numerator)
            denominatorMean = blend(denominatorMean, denominator)
        }
    }

    private class ForecastErrors {
        private var count = 0
        private var mean = 0.0
        private var recentMass = 0.0
        private var recent = 0.0
        private val last = DoubleArray(3)
        val score: Double get() = 0.25 * mean + 0.75 * recent

        fun add(error: Double) {
            last[count % last.size] = error
            count++
            mean += (error - mean) / count
            recentMass = recentMass * ERROR_DECAY + 1.0
            recent += (error - recent) / recentMass
        }

        fun wonLastThree(other: ForecastErrors) = count >= 3 && last.indices.all { last[it] < other.last[it] }
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000.0
        val ORDER_DECAY = listOf(3, 6, 12).associateWith { 0.5.pow(1.0 / it) }
        val ERROR_DECAY = ORDER_DECAY.getValue(6)
    }
}
