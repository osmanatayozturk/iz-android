package org.iz.navigation.lean

/** Each continuous absence produces one decision, even if no Location callback arrives. */
internal class LiveFixDeadline(private val openedAt: Long) {
    enum class Absence { NEVER_RECEIVED, STALE }

    private var latest: Long? = null
    private var reported = false
    val expiredWithoutFix: Boolean get() = reported && latest == null

    fun observed(measuredAt: Long, now: Long) {
        if (measuredAt < openedAt || measuredAt > now || measuredAt <= (latest ?: -1L)) return
        if (latest == null || now - measuredAt < LIFETIME_NANOS) reported = false
        latest = measuredAt
    }

    fun remaining(now: Long): Long? = if (reported) null
        else (LIFETIME_NANOS - (now - (latest ?: openedAt))).coerceIn(0L, LIFETIME_NANOS)

    fun expire(now: Long, noInitialFix: Boolean = false): Absence? {
        if (reported) return null
        val initiallyAbsent = noInitialFix && latest == null
        if (!initiallyAbsent && remaining(now) != 0L) return null
        reported = true
        return if (latest == null) Absence.NEVER_RECEIVED else Absence.STALE
    }

    companion object { const val LIFETIME_NANOS = 1_500_000_000L }
}
