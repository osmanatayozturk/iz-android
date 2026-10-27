package org.iz.navigation.lean

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.iz.navigation.data.Journey
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** One bounded raw producer per recorder. Persistence/eligibility/generation remains authoritative in LeanStore. */
internal class LeanCaptureController(context: Context, private val onCaptureChanged: () -> Unit) {
    private val context = context.applicationContext
    private val settings = LeanSettingsStore.get(this.context)
    private val store = LeanStore.get(this.context)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val mutableStatus = MutableStateFlow<String?>(null)
    val status = mutableStatus.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var active: Capture? = null
    @Volatile private var blockedKey: String? = null
    private class Capture(val token: LeanSessionToken, val identity: String, val generation: Long, val start: Long) {
        val records = Channel<LeanRecord>(512)
        val dropped = AtomicLong()
        @Volatile var accepting = true
        @Volatile var reason: String? = null
        @Volatile var end: Long? = null
        lateinit var sensor: LeanSensorCapture
        lateinit var writer: Job
    }
    val capturing: Boolean get() = active?.accepting == true
    fun captures(journeyId: String?) = journeyId != null && active?.let { it.accepting && it.token.journeyId == journeyId } == true

    suspend fun sync(journey: Journey?) = mutex.withLock {
        val config = settings.state.value
        if (closed || !leanCaptureEligible(journey, config)) {
            stopLocked(if (journey?.endedAt == null && active != null) "capture_disabled" else null)
            blockedKey = null
            return@withLock
        }
        val reference = requireNotNull(config.calibration)
        val identity = journey!!.id + ":" + reference.epoch
        if (active?.let { it.identity == identity && it.accepting } == true || blockedKey == identity) return@withLock
        stopLocked("calibration_changed")
        blockedKey = identity // Never retry a missing sensor/full store on each GPS callback.
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }
            .getOrDefault("unknown") + ":" + processEpoch
        val token = try { store.begin(journey.id, reference, boot, System.currentTimeMillis(), elapsed) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableStatus.value = "storage_unavailable"; return@withLock }
        if (token == null) { mutableStatus.value = "unavailable_or_capacity"; return@withLock }
        if (closed) {
            try { withContext(NonCancellable) { store.seal(token, SystemClock.elapsedRealtimeNanos(), "capture_interrupted") } }
            catch (_: Exception) { mutableStatus.value = "seal_pending_recovery" }
            return@withLock
        }
        val session = Capture(token, identity, generation.incrementAndGet(), elapsed)
        session.sensor = LeanSensorCapture(context, { record -> offer(session, record) }, { session.dropped.incrementAndGet() })
        session.writer = io.launch(start = CoroutineStart.LAZY) { write(session) }
        active = session
        session.writer.start()
        if (closed) { stopLocked("capture_interrupted"); return@withLock }
        if (!session.sensor.start()) {
            session.reason = "sensor_unavailable"
            stopLocked("sensor_unavailable")
        } else if (session.accepting) { mutableStatus.value = null; onCaptureChanged() }
    }

    private fun offer(session: Capture, record: LeanRecord) {
        if (!session.accepting || active !== session || generation.get() != session.generation || record.elapsedNanos < session.start) return
        if (!session.records.trySend(record).isSuccess) session.dropped.incrementAndGet()
    }

    /** Preserve measurement time/optional-quality presence; callback arrival never substitutes for GNSS time.
     * Source: https://developer.android.com/reference/android/location/Location#getElapsedRealtimeNanos()
     */
    fun onLocation(journeyId: String, location: Location) {
        val session = active?.takeIf { it.token.journeyId == journeyId && it.accepting } ?: return
        fun value(present: Boolean, value: Float, positive: Boolean = true): Double? = value.toDouble()
            .takeIf { present && it.isFinite() && (!positive || it >= 0) }
        val time = location.elapsedRealtimeNanos
        if (time <= 0 || !location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) {
            session.dropped.incrementAndGet(); return
        }
        @Suppress("DEPRECATION") val mock = location.isFromMockProvider
        offer(session, LeanGnssRecord(time, location.time, location.latitude, location.longitude,
            value(location.hasAccuracy(), location.accuracy), value(location.hasSpeed(), location.speed),
            value(location.hasBearing(), location.bearing), value(location.hasSpeedAccuracy(), location.speedAccuracyMetersPerSecond),
            value(location.hasBearingAccuracy(), location.bearingAccuracyDegrees),
            location.elapsedRealtimeUncertaintyNanos.takeIf { location.hasElapsedRealtimeUncertaintyNanos() && it.isFinite() && it >= 0 },
            mock, location.provider?.take(64)))
    }

    private suspend fun write(session: Capture) {
        var unpersisted: List<LeanRecord> = emptyList()
        try {
            while (true) {
                val first = session.records.receiveCatching().getOrNull() ?: break
                val batch = ArrayList<LeanRecord>(256).apply { add(first) }
                unpersisted = batch
                withTimeoutOrNull(1_000) {
                    while (batch.size < 256) batch.add(session.records.receiveCatching().getOrNull() ?: break)
                }
                if (!store.append(session.token, batch)) {
                    session.reason = "raw_capacity_or_invalidated"
                    break
                }
                unpersisted = emptyList()
            }
        } catch (cancelled: CancellationException) { session.reason = "capture_interrupted"; throw cancelled }
        catch (_: Exception) { session.reason = "storage_unavailable" }
        finally {
            session.accepting = false
            session.sensor.stop()
            session.records.close()
            session.dropped.addAndGet(unpersisted.size.toLong())
            while (session.records.tryReceive().getOrNull() != null) session.dropped.incrementAndGet()
            withContext(NonCancellable) {
                try { store.seal(session.token, session.end ?: SystemClock.elapsedRealtimeNanos(), session.reason, session.dropped.get()) }
                catch (_: Exception) { mutableStatus.value = "seal_pending_recovery" }
            }
            if (active === session) active = null
            if (session.reason != null) mutableStatus.value = session.reason
            onCaptureChanged()
        }
    }
    suspend fun stop(reason: String? = null) = mutex.withLock { stopLocked(reason) }
    private suspend fun stopLocked(reason: String?) {
        val session = active ?: return
        session.accepting = false
        generation.incrementAndGet()
        if (session.end == null) session.end = SystemClock.elapsedRealtimeNanos()
        if (session.reason == null) session.reason = reason
        session.sensor.stop()
        withContext(NonCancellable) {
            try { store.stopAt(session.token, requireNotNull(session.end)) }
            catch (_: Exception) { if (session.reason == null) session.reason = "storage_unavailable" }
            session.records.close()
            session.writer.join() // Drain bounded pre-stop measurements before publishing sealed input.
        }
        if (active === session) active = null
    }
    /** Android onDestroy cannot suspend. Stop producer now; independent IO owner drains/seals once. */
    fun close() {
        closed = true
        val session = active
        session?.let {
            it.accepting = false; generation.incrementAndGet()
            it.end = SystemClock.elapsedRealtimeNanos()
            if (it.reason == null) it.reason = "capture_interrupted"
            it.sensor.stop(); it.records.close()
        }
        io.launch {
            mutex.withLock { stopLocked("capture_interrupted") }
            io.cancel()
        }
    }
    companion object { private val processEpoch = UUID.randomUUID().toString() }
}