package org.iz.navigation.watch

/** The service's registration gateway. A confirmed, live session owns every hardware listener. */
internal class WatchHealthSensorController(
    private val capture: WatchHealthCapture,
    private val hardware: Hardware,
    private val resume: (Long) -> Unit,
) {
    enum class Kind { BODY, HEART, STEPS }
    interface Hardware {
        fun available(kind: Kind): Boolean
        fun register(kind: Kind, generation: Long): Boolean
        fun unregister(kind: Kind)
    }
    private val registrations = Kind.entries.associateWith { WatchSensorRegistration() }
    private var session: String? = null
    private var collectionRequested = false
    var collecting = false; private set
    fun registered(kind: Kind) = registrations.getValue(kind).registered

    fun update(allowed: Boolean, nowNanos: Long) {
        val elapsed = nowNanos / 1_000_000
        if (!allowed || capture.expired(elapsed)) capture.detach()
        if (session != capture.sessionId) {
            registrations.forEach { (kind, slot) ->
                if (slot.registered) hardware.unregister(kind)
                slot.reset()
            }
            capture.onBody(null)
            collectionRequested = false
            session = capture.sessionId
        }
        fun ensure(kind: Kind, eligible: Boolean) {
            val slot = registrations.getValue(kind)
            slot.ensure(eligible, hardware.available(kind), elapsed, nowNanos,
                unregister = { hardware.unregister(kind) }) { hardware.register(kind, slot.generation) }
        }
        val eligible = allowed && capture.sessionId != null
        if (!registered(Kind.BODY)) capture.onBody(null)
        ensure(Kind.BODY, eligible)
        val active = eligible && registered(Kind.BODY) && capture.active(elapsed)
        if (active && !collectionRequested) resume(elapsed)
        collectionRequested = active
        ensure(Kind.HEART, active)
        ensure(Kind.STEPS, active)
        collecting = registered(Kind.HEART) || registered(Kind.STEPS)
    }

    fun accept(kind: Kind, generation: Long, eventNanos: Long, nowNanos: Long): Boolean {
        if (capture.sessionId == null || capture.sessionId != session || capture.expired(nowNanos / 1_000_000)) return false
        val registration = registrations.getValue(kind)
        return if (kind == Kind.BODY) registration.acceptBodyState(generation, eventNanos, nowNanos)
            else registration.accept(generation, eventNanos, nowNanos)
    }

    fun onBody(generation: Long, eventNanos: Long, nowNanos: Long, worn: Boolean?): Boolean {
        if (!accept(Kind.BODY, generation, eventNanos, nowNanos)) return false
        capture.onBody(worn)
        return true
    }
}
