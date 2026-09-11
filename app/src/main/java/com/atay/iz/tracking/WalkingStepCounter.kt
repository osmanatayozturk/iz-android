package com.atay.iz.tracking

/** Converts hardware readings into steps measured only during this attached walking/running session. */
internal class WalkingStepCounter(
    private val startedAtNanos: Long,
    initialCount: Long? = null,
) {
    private var total = initialCount?.coerceAtLeast(0) ?: 0L
    private var previousCounter: Long? = null
    private var previousTimestamp = Long.MIN_VALUE

    fun observeCounter(value: Float, timestampNanos: Long): Long? {
        if (!value.isFinite() || value < 0 || value.toDouble() >= Long.MAX_VALUE.toDouble() ||
            value.toDouble() % 1.0 != 0.0 || !fresh(timestampNanos)) return null
        val counter = value.toLong()
        val previous = previousCounter
        // The first reading includes steps from before this journey. A reset needs a new baseline too.
        if (previous != null && counter >= previous) add(counter - previous)
        previousCounter = counter
        previousTimestamp = timestampNanos
        return total
    }

    fun observeDetector(value: Float, timestampNanos: Long): Long? {
        if (value != 1f || !fresh(timestampNanos)) return null
        add(1)
        previousTimestamp = timestampNanos
        return total
    }

    private fun fresh(timestampNanos: Long): Boolean =
        timestampNanos >= startedAtNanos && timestampNanos > previousTimestamp

    private fun add(steps: Long) {
        total += steps.coerceAtMost(Long.MAX_VALUE - total)
    }
}
