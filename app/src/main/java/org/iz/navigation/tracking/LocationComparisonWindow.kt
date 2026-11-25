package org.iz.navigation.tracking

internal enum class ComparisonSourceId { FUSED, GPS }
internal enum class ComparisonPhase { IDLE, REGISTERING, MEASURING, COMPLETE, CANCELLED, ERROR }
internal enum class ComparisonIssue { FINE_PERMISSION, LOCATION_DISABLED, GPS_UNAVAILABLE,
    REGISTRATION_FAILED, REGISTRATION_TIMEOUT, PROVIDER_DISABLED, ACTIVE_RIDE, MOVING }

internal fun parkedDiagnosticBlock(activeRide: Boolean, gpsStale: Boolean, fixAtMillis: Long?,
    speedMps: Float?, accuracyMeters: Float?, nowMillis: Long): ComparisonIssue? = when {
    activeRide -> ComparisonIssue.ACTIVE_RIDE
    !gpsStale && fixAtMillis != null && nowMillis - fixAtMillis in 0..5_000 &&
        accuracyMeters != null && accuracyMeters.isFinite() && accuracyMeters in 0f..50f &&
        speedMps != null && speedMps.isFinite() && speedMps > 1.5f -> ComparisonIssue.MOVING
    else -> null
}
internal enum class ComparisonField {
    HORIZONTAL_ACCURACY, SPEED, SPEED_ACCURACY, BEARING, BEARING_ACCURACY, ALTITUDE, VERTICAL_ACCURACY,
    TIME_UNCERTAINTY_MILLIS;
    fun valid(value: Double): Boolean = value.isFinite() && when (this) {
        ALTITUDE -> true
        BEARING -> value >= 0.0 && value < 360.0
        else -> value >= 0.0
    }
}

/** Deliberately has no coordinates, wall time, extras or platform Location reference. */
internal data class ComparisonSample(val elapsedNanos: Long, val fields: Map<ComparisonField, Double?>,
    val mock: Boolean = false) {
    fun motionEvidence(now: Long): Boolean {
        val speed = fields[ComparisonField.SPEED]
        val accuracy = fields[ComparisonField.HORIZONTAL_ACCURACY]
        return elapsedNanos > 0 && now - elapsedNanos in 0..5_000_000_000L &&
            speed != null && speed.isFinite() && speed > 1.5 &&
            accuracy != null && accuracy.isFinite() && accuracy in 0.0..50.0
    }
}
internal data class ComparisonValues(val count: Int = 0, val minimum: Double? = null,
    val maximum: Double? = null, val total: Double = 0.0) {
    val mean get() = total.takeIf { count > 0 }?.div(count)
    fun add(value: Double) = copy(count = count + 1, minimum = minimum?.coerceAtMost(value) ?: value,
        maximum = maximum?.coerceAtLeast(value) ?: value, total = total + value)
}
internal data class ComparisonFieldSummary(val missing: Int = 0, val invalid: Int = 0,
    val values: ComparisonValues = ComparisonValues())
internal data class ComparisonStreamSummary(val callbacks: Int = 0, val received: Int = 0, val accepted: Int = 0,
    val invalidTime: Int = 0, val outsideWindow: Int = 0, val duplicates: Int = 0, val outOfOrder: Int = 0,
    val mock: Int = 0, val available: Boolean? = null, val unavailableReports: Int = 0,
    val intervalMillis: ComparisonValues = ComparisonValues(), val deliveryAgeMillis: ComparisonValues = ComparisonValues(),
    val windowHz: Double? = null,
    val fields: Map<ComparisonField, ComparisonFieldSummary> = ComparisonField.entries.associateWith { ComparisonFieldSummary() })
internal data class LocationComparisonSnapshot(val epoch: Long = 0, val phase: ComparisonPhase = ComparisonPhase.IDLE,
    val startedElapsedNanos: Long? = null, val elapsedMillis: Long = 0, val plannedMillis: Long = 10_000,
    val registrationMillis: Long = 0, val issue: ComparisonIssue? = null,
    val fused: ComparisonStreamSummary = ComparisonStreamSummary(), val gps: ComparisonStreamSummary = ComparisonStreamSummary(),
    val issueSource: ComparisonSourceId? = null)

/** Online aggregates only. Confined to the capture's caller thread; old epochs never mutate a new window. */
internal class LocationComparisonWindow(private val epoch: Long, val started: Long,
    val durationNanos: Long = 10_000_000_000L) {
    private val summaries = ComparisonSourceId.entries.associateWith { ComparisonStreamSummary() }.toMutableMap()
    private val previous = mutableMapOf<ComparisonSourceId, Long>()
    private var closed = false
    fun close() { closed = true }
    fun availability(token: Long, source: ComparisonSourceId, available: Boolean) {
        if (closed || token != epoch) return
        val old = summaries.getValue(source)
        summaries[source] = old.copy(available = available, unavailableReports = old.unavailableReports + if (available) 0 else 1)
    }
    fun batch(token: Long, source: ComparisonSourceId, samples: List<ComparisonSample>, receivedNanos: Long) {
        if (closed || token != epoch) return
        var summary = summaries.getValue(source).let { it.copy(callbacks = it.callbacks + 1, received = it.received + samples.size) }
        for (sample in samples) {
            val at = sample.elapsedNanos
            when {
                at <= 0 || at > receivedNanos -> summary = summary.copy(invalidTime = summary.invalidTime + 1)
                at < started || at > started + durationNanos || receivedNanos < started || receivedNanos > started + durationNanos ->
                    summary = summary.copy(outsideWindow = summary.outsideWindow + 1)
                at == previous[source] -> summary = summary.copy(duplicates = summary.duplicates + 1)
                at < (previous[source] ?: Long.MIN_VALUE) -> summary = summary.copy(outOfOrder = summary.outOfOrder + 1)
                else -> {
                    val interval = previous[source]?.let { (at - it) / 1_000_000.0 }
                    previous[source] = at
                    summary = summary.copy(accepted = summary.accepted + 1, mock = summary.mock + if (sample.mock) 1 else 0,
                        intervalMillis = interval?.let { summary.intervalMillis.add(it) } ?: summary.intervalMillis,
                        deliveryAgeMillis = summary.deliveryAgeMillis.add((receivedNanos - at) / 1_000_000.0),
                        fields = summary.fields.mapValues { (field, old) ->
                            val value = sample.fields[field]
                            when {
                                value == null -> old.copy(missing = old.missing + 1)
                                !field.valid(value) -> old.copy(invalid = old.invalid + 1)
                                else -> old.copy(values = old.values.add(value))
                            }
                        })
                }
            }
        }
        summaries[source] = summary
    }
    fun snapshot(now: Long, phase: ComparisonPhase = ComparisonPhase.MEASURING,
        registrationMillis: Long = 0, issue: ComparisonIssue? = null): LocationComparisonSnapshot {
        val elapsed = (now - started).coerceIn(0, durationNanos)
        fun stream(source: ComparisonSourceId) = summaries.getValue(source).let {
            it.copy(windowHz = if (elapsed > 0) it.accepted * 1_000_000_000.0 / elapsed else null)
        }
        return LocationComparisonSnapshot(epoch, phase, started, elapsed / 1_000_000, durationNanos / 1_000_000,
            registrationMillis, issue, stream(ComparisonSourceId.FUSED), stream(ComparisonSourceId.GPS))
    }
}
