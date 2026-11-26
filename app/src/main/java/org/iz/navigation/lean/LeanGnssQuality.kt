package org.iz.navigation.lean

import kotlin.math.abs

internal enum class LeanGnssIssueCode(val label: String) {
    GNSS_MOCK_FIX("GPS konumu benzetim kaynağından geliyor"),
    GNSS_LATITUDE_OUT_OF_RANGE("GPS enlem değeri geçersiz"),
    GNSS_LONGITUDE_OUT_OF_RANGE("GPS boylam değeri geçersiz"),
    GNSS_SPEED_MISSING("GPS hız bilgisi eksik"),
    GNSS_SPEED_OUT_OF_RANGE("GPS hızı ölçüm sınırının dışında"),
    GNSS_POSITION_ACCURACY_MISSING("GPS konum doğruluğu bilgisi eksik"),
    GNSS_POSITION_ACCURACY_OUT_OF_RANGE("GPS konum doğruluğu yetersiz"),
    GNSS_SPEED_ACCURACY_MISSING("GPS hız doğruluğu bilgisi eksik"),
    GNSS_SPEED_ACCURACY_OUT_OF_RANGE("GPS hız doğruluğu yetersiz"),
    GNSS_TIME_UNCERTAINTY_MISSING("GPS zaman doğruluğu bilgisi eksik"),
    GNSS_TIME_UNCERTAINTY_OUT_OF_RANGE("GPS zaman doğruluğu yetersiz"),
    GNSS_BEARING_MISSING("GPS yön bilgisi eksik"),
    GNSS_BEARING_OUT_OF_RANGE("GPS yön değeri geçersiz"),
    GNSS_BEARING_ACCURACY_MISSING("GPS yön doğruluğu bilgisi eksik"),
    GNSS_BEARING_ACCURACY_OUT_OF_RANGE("GPS yön doğruluğu yetersiz"),
    GNSS_DUPLICATE_TIME("GPS ölçüm zamanı yinelendi"),
    GNSS_DATA_GAP("GPS ölçümleri arasında kesinti var"),
    GNSS_RATE_UNSUITABLE("GPS ölçüm sıklığı yatış hesabına uygun değil"),
    GNSS_INSUFFICIENT_FIXES("Yatış hesabı için GPS ölçümü az"),
    CLOCK_DISCONTINUITY("GPS ölçüm saati tutarsız"),
    LOW_SPEED_UNSUPPORTED("Yatış hesabı için hız düşük"),
    SPEED_TRANSIENT("GPS hızı fazla hızlı değişti"),
    GNSS_COURSE_UNCERTAIN("GPS yön değişimi güvenilir değil"),
    GNSS_MOTION_DISAGREEMENT("GPS konumu ve hareket bilgisi uyuşmuyor"),
    MODEL_CHANNEL_UNAVAILABLE("GPS verisi yatış hesabının sınırını aştı"),
}

internal fun leanGnssIssueLabel(code: String): String =
    LeanGnssIssueCode.entries.firstOrNull { it.name == code }?.label ?: "GPS ölçümü değerlendirilemedi"

internal class LeanGnssIssueCounter {
    private data class Key(val code: LeanGnssIssueCode, val unit: String)
    private data class Totals(var count: Long = 0, var durationNanos: Long = 0)
    private val totals = mutableMapOf<Key, Totals>()

    fun fix(code: LeanGnssIssueCode, count: Long = 1) = add(code, "fix", count, 0)
    fun pair(code: LeanGnssIssueCode, durationNanos: Long) = add(code, "pair", 1, durationNanos)

    private fun add(code: LeanGnssIssueCode, unit: String, count: Long, duration: Long) {
        val total = totals.getOrPut(Key(code, unit)) { Totals() }
        total.count += count
        total.durationNanos += duration.coerceAtMost(Long.MAX_VALUE - total.durationNanos)
    }

    fun finish(): LeanGnssDiagnostics = LeanGnssDiagnostics(totals.entries
        .sortedWith(compareBy({ it.key.code.ordinal }, { it.key.unit }))
        .map { (key, total) -> LeanGnssIssue(key.code.name, key.unit, total.count, total.durationNanos) })
}

internal object LeanGnssQuality {
    private const val HANDLEBAR_MAX_SPEED = 70.0
    private const val BODY_MAX_SPEED = 100.0
    internal const val BODY_MAX_GAP_SECONDS = 1.5
    internal const val HANDLEBAR_PARKED_SPEED = 1.0

    private enum class Measurement(val missing: LeanGnssIssueCode, val outOfRange: LeanGnssIssueCode) {
        SPEED(LeanGnssIssueCode.GNSS_SPEED_MISSING, LeanGnssIssueCode.GNSS_SPEED_OUT_OF_RANGE),
        POSITION_ACCURACY(LeanGnssIssueCode.GNSS_POSITION_ACCURACY_MISSING, LeanGnssIssueCode.GNSS_POSITION_ACCURACY_OUT_OF_RANGE),
        SPEED_ACCURACY(LeanGnssIssueCode.GNSS_SPEED_ACCURACY_MISSING, LeanGnssIssueCode.GNSS_SPEED_ACCURACY_OUT_OF_RANGE),
        TIME_UNCERTAINTY(LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_MISSING, LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_OUT_OF_RANGE),
        BEARING(LeanGnssIssueCode.GNSS_BEARING_MISSING, LeanGnssIssueCode.GNSS_BEARING_OUT_OF_RANGE),
        BEARING_ACCURACY(LeanGnssIssueCode.GNSS_BEARING_ACCURACY_MISSING, LeanGnssIssueCode.GNSS_BEARING_ACCURACY_OUT_OF_RANGE),
    }

    fun fixIssues(fix: LeanGnssRecord, mount: LeanMount): Set<LeanGnssIssueCode> = buildSet {
        if (fix.mock) add(LeanGnssIssueCode.GNSS_MOCK_FIX)
        if (fix.latitude !in -90.0..90.0) add(LeanGnssIssueCode.GNSS_LATITUDE_OUT_OF_RANGE)
        if (fix.longitude !in -180.0..180.0) add(LeanGnssIssueCode.GNSS_LONGITUDE_OUT_OF_RANGE)
        check(fix.speedMps, maxSpeed(mount), Measurement.SPEED)
        check(fix.accuracyMeters, 20.0, Measurement.POSITION_ACCURACY)
        check(fix.speedAccuracyMps, 1.5, Measurement.SPEED_ACCURACY)
        if (fix.invalidElapsedUncertainty) add(Measurement.TIME_UNCERTAINTY.outOfRange)
        else check(fix.elapsedUncertaintyNanos, 100_000_000.0, Measurement.TIME_UNCERTAINTY)
    }

    fun movingHandlebarIssues(fix: LeanGnssRecord, positionAccuracy: Double, speedAccuracy: Double,
        bearingAccuracy: Double): Set<LeanGnssIssueCode> = buildSet {
        check(fix.accuracyMeters, positionAccuracy, Measurement.POSITION_ACCURACY)
        check(fix.speedAccuracyMps, speedAccuracy, Measurement.SPEED_ACCURACY)
        val bearing = fix.bearingDegrees
        if (bearing == null) add(Measurement.BEARING.missing)
        else if (!(bearing >= 0.0 && bearing < 360.0)) add(Measurement.BEARING.outOfRange)
        check(fix.bearingAccuracyDegrees, bearingAccuracy, Measurement.BEARING_ACCURACY)
    }

    private fun MutableSet<LeanGnssIssueCode>.check(value: Double?, maximum: Double, field: Measurement) {
        if (value == null) add(field.missing)
        else if (value !in 0.0..maximum) add(field.outOfRange)
    }

    fun clockValid(fix: LeanGnssRecord, expectedUtc: Long?, mount: LeanMount): Boolean =
        expectedUtc != null && fix.utcMillis > 0 &&
            abs(fix.utcMillis.toDouble() - expectedUtc) <= if (mount == LeanMount.BODY) 2_000.0 else 1_000.0

    fun movingEvidence(fix: LeanGnssRecord, mount: LeanMount): Boolean = !fix.mock &&
        fix.speedMps?.let { speed -> speed <= maxSpeed(mount) &&
            if (mount == LeanMount.BODY) speed >= 3.0 else speed > HANDLEBAR_PARKED_SPEED } == true

    private fun maxSpeed(mount: LeanMount) = if (mount == LeanMount.BODY) BODY_MAX_SPEED else HANDLEBAR_MAX_SPEED
}

internal fun leanMovingGnssHint(previous: LeanGnssRecord, current: LeanGnssRecord, mount: LeanMount): String? {
    if (mount == LeanMount.UNKNOWN || previous.elapsedNanos <= 0 || current.elapsedNanos <= 0 ||
        (!LeanGnssQuality.movingEvidence(previous, mount) && !LeanGnssQuality.movingEvidence(current, mount))) return null
    val span = current.elapsedNanos - previous.elapsedNanos
    if (span == 0L) return LeanGnssIssueCode.GNSS_DUPLICATE_TIME.label
    if (span < 0L) return LeanGnssIssueCode.GNSS_RATE_UNSUITABLE.label
    val expectedUtc: (Long) -> Long? = { time ->
        val deltaMillis = (time - previous.elapsedNanos) / 1_000_000L
        previous.utcMillis.takeIf { it > 0 && it <= Long.MAX_VALUE - deltaMillis }?.plus(deltaMillis)
    }
    if (mount == LeanMount.HANDLEBAR) return HandlebarLeanEstimator.movingGnssIssue(previous, current, expectedUtc)?.label
    if (!LeanGnssQuality.clockValid(previous, expectedUtc(previous.elapsedNanos), mount) ||
        !LeanGnssQuality.clockValid(current, expectedUtc(current.elapsedNanos), mount)) return LeanGnssIssueCode.CLOCK_DISCONTINUITY.label
    val issue = (LeanGnssQuality.fixIssues(previous, mount) + LeanGnssQuality.fixIssues(current, mount)).firstOrNull()
    return issue?.label ?: LeanGnssIssueCode.GNSS_DATA_GAP.label.takeIf {
        span / 1_000_000_000.0 > LeanGnssQuality.BODY_MAX_GAP_SECONDS
    }
}
