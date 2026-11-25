package org.iz.navigation.lean

internal const val LEAN_IMU_CHECK_DURATION_NANOS = 10_000_000_000L

internal enum class LeanImuCheckRegistration { LISTENING, MISSING, PERMISSION_RESTRICTED, FAILED }

internal data class LeanImuCheckCounts(
    val received: Long,
    val usable: Long,
    val longestIntervalNanos: Long? = null,
) {
    val usableHertz: Double get() = usable * (1_000_000_000.0 / LEAN_IMU_CHECK_DURATION_NANOS)
}

/** Event time and arrival time are both in Android's monotonic elapsed-time domain. */
internal class LeanImuCheckWindow(private val startedAtNanos: Long) {
    private class Counter {
        var received = 0L
        var usable = 0L
        var lastEvent: Long? = null
        var longestIntervalNanos: Long? = null
    }
    private val counters = LeanSensorKind.entries.associateWith { Counter() }

    @Synchronized
    fun accept(kind: LeanSensorKind, eventNanos: Long, arrivalNanos: Long, valuesUsable: Boolean) {
        if (arrivalNanos - startedAtNanos !in 0 until LEAN_IMU_CHECK_DURATION_NANOS) return
        val counter = counters.getValue(kind)
        counter.received++
        val lastEvent = counter.lastEvent
        if (!valuesUsable || eventNanos < startedAtNanos || eventNanos > arrivalNanos ||
            (lastEvent != null && eventNanos <= lastEvent)) return
        counter.usable++
        if (lastEvent != null) {
            counter.longestIntervalNanos = maxOf(counter.longestIntervalNanos ?: 0L, eventNanos - lastEvent)
        }
        counter.lastEvent = eventNanos
    }

    @Synchronized
    fun counts(): Map<LeanSensorKind, LeanImuCheckCounts> =
        counters.mapValues { (_, counter) ->
            LeanImuCheckCounts(counter.received, counter.usable, counter.longestIntervalNanos)
        }

    fun remainingNanos(now: Long): Long =
        LEAN_IMU_CHECK_DURATION_NANOS - (now - startedAtNanos).coerceIn(0, LEAN_IMU_CHECK_DURATION_NANOS)
}

internal data class LeanImuCheckProgress(
    val remainingNanos: Long,
    val registration: Map<LeanSensorKind, LeanImuCheckRegistration>,
    val counts: Map<LeanSensorKind, LeanImuCheckCounts>,
    val complete: Boolean,
)
