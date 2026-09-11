package com.atay.iz.tracking

import android.content.Context

class TrackerSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tracking", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    /** enabled is the user's persisted request, not proof of a working Play Services subscription. */
    var registrationState: DetectionRegistrationState
        get() = runCatching { DetectionRegistrationState.valueOf(prefs.getString("registrationState", null).orEmpty()) }
            .getOrDefault(if (enabled) DetectionRegistrationState.RETRY_PENDING else DetectionRegistrationState.OFF)
        set(value) { prefs.edit().putString("registrationState", value.name).apply() }
    var registrationError: String?
        get() = prefs.getString("registrationError", null)
        set(value) { prefs.edit().putString("registrationError", value).apply() }
    var lastRegistrationAt: Long
        get() = prefs.getLong("lastRegistrationAt", 0L)
        set(value) { prefs.edit().putLong("lastRegistrationAt", value).apply() }
    var lastRegistrationAttemptAt: Long
        get() = prefs.getLong("lastRegistrationAttemptAt", 0L)
        set(value) { prefs.edit().putLong("lastRegistrationAttemptAt", value).apply() }
    var lastAcceptedLocationAt: Long
        get() = prefs.getLong("lastAcceptedLocationAt", 0L)
        set(value) { prefs.edit().putLong("lastAcceptedLocationAt", value).apply() }
    var stopMinutes: Int
        get() = prefs.getInt("stopMinutes", 10).coerceIn(1, 60)
        set(value) { prefs.edit().putInt("stopMinutes", value.coerceIn(1, 60)).apply() }
    var lastError: String?
        get() = prefs.getString("lastError", null)
        set(value) { prefs.edit().putString("lastError", value).apply() }
    internal var currentActivity: Int
        get() = prefs.getInt("currentActivity", -1)
        set(value) { prefs.edit().putInt("currentActivity", value).apply() }
    internal var suppressedActivity: Int
        get() = prefs.getInt("suppressedActivity", -1)
        set(value) { prefs.edit().putInt("suppressedActivity", value).apply() }
    internal var lastTransitionNanos: Long
        get() = prefs.getLong("lastTransitionNanos", 0L)
        set(value) { prefs.edit().putLong("lastTransitionNanos", value).apply() }

    /** Activity identities and elapsed timestamps belong to one boot, not to the next journey. */
    internal fun resetActivitySessionAfterBoot() {
        prefs.edit().remove("currentActivity").remove("suppressedActivity")
            .remove("lastTransitionNanos").remove("lastTransitionKeys")
            .putString("registrationState", if (enabled) DetectionRegistrationState.RETRY_PENDING.name else DetectionRegistrationState.OFF.name)
            .remove("registrationError").apply()
    }

    /** Several distinct transitions can legitimately have the same elapsed timestamp. */
    internal fun acceptTransition(activity: Int, transition: Int, timestamp: Long, now: Long): Boolean {
        val previous = lastTransitionNanos.takeIf { it <= now } ?: 0L
        if (timestamp < previous || timestamp > now) return false
        val seen = if (timestamp == previous) prefs.getStringSet("lastTransitionKeys", emptySet()).orEmpty() else emptySet()
        val key = "$activity:$transition"
        if (key in seen) return false
        prefs.edit().putLong("lastTransitionNanos", timestamp)
            .putStringSet("lastTransitionKeys", seen + key).apply()
        return true
    }

    var lastActivityEvent: String?
        get() = prefs.getString("lastActivityEvent", null)
        set(value) { prefs.edit().putString("lastActivityEvent", value).apply() }
    var lastActivityEventAt: Long
        get() = prefs.getLong("lastActivityEventAt", 0L)
        set(value) { prefs.edit().putLong("lastActivityEventAt", value).apply() }
    var lastDetectionDecision: String?
        get() = prefs.getString("lastDetectionDecision", null)
        set(value) { prefs.edit().putString("lastDetectionDecision", value).apply() }
}
