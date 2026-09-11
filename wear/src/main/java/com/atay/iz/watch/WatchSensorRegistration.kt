package com.atay.iz.watch

/** One retry slot per hardware listener: retrying one failed sensor never resets a working sensor. */
internal class WatchSensorRegistration {
    var registered = false; private set
    var registeredSince = 0L; private set
    private var retryAt = 0L
    var generation = 0L; private set
    private var registeredNanos = 0L
    private var lastEventNanos = 0L
    private var receivedEvent = false
    fun ensure(eligible: Boolean, available: Boolean, nowElapsed: Long,
        nowNanos: Long = nowElapsed * 1_000_000, unregister: () -> Unit = {}, register: () -> Boolean): Boolean {
        if (!eligible || !available) { if (registered) unregister(); reset(); return false }
        if (registered) return true
        if (nowElapsed < retryAt) return false
        generation++
        registeredNanos = nowNanos; lastEventNanos = nowNanos; receivedEvent = false
        registered = runCatching(register).getOrDefault(false)
        if (registered) registeredSince = nowElapsed else retryAt = nowElapsed + 30_000
        return registered
    }
    fun accept(token: Long, eventNanos: Long, nowNanos: Long): Boolean {
        if (!registered || token != generation || eventNanos <= registeredNanos || eventNanos <= lastEventNanos ||
            eventNanos > nowNanos || nowNanos - eventNanos > 30_000_000_000L) return false
        lastEventNanos = eventNanos
        receivedEvent = true
        return true
    }
    /** ON_CHANGE registration may deliver cached current state with its original timestamp. */
    fun acceptBodyState(token: Long, eventNanos: Long, nowNanos: Long): Boolean {
        if (receivedEvent) return accept(token, eventNanos, nowNanos)
        if (!registered || token != generation || eventNanos < 0 || eventNanos > nowNanos) return false
        receivedEvent = true
        lastEventNanos = eventNanos
        return true
    }
    fun reset() { registered = false; registeredSince = 0; retryAt = 0; generation++; registeredNanos = 0; lastEventNanos = 0; receivedEvent = false }
}
