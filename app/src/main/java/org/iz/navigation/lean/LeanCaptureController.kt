package org.iz.navigation.lean

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
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
    private val owner = Any()
    init {
        synchronized(statusLock) {
            statusOwner = owner
            mutableStatus.value = null
        }
    }
    @Volatile private var closed = false
    @Volatile private var active: Capture? = null
    @Volatile private var blockedKey: String? = null
    private class Capture(val token: LeanSessionToken, val identity: String, val calibrationEpoch: String,
        val generation: Long, val start: Long, val mount: LeanMount) {
        val progress = LeanSensorCommitProgress(start)
        var lastFix: LeanGnssRecord? = null
        var gnssHint: String? = null
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
            publish(null)
            return@withLock
        }
        val reference = requireNotNull(config.calibration)
        val identity = journey!!.id + ":" + reference.epoch
        if (active?.let { it.identity == identity && it.accepting } == true || blockedKey == identity) return@withLock
        stopLocked("calibration_changed")
        blockedKey = identity // Never retry a missing sensor/full store on each GPS callback.
        publish(LeanRecordingStatus(journey.id, reference.epoch, LeanRecordingPhase.WAITING_FOR_DATA))
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }
            .getOrDefault("unknown") + ":" + processEpoch
        val token = try { store.begin(journey.id, reference, boot, System.currentTimeMillis(), elapsed) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (token == null) {
            publish(LeanRecordingStatus(journey.id, reference.epoch, LeanRecordingPhase.STORAGE_UNAVAILABLE))
            return@withLock
        }
        if (closed) {
            try { withContext(NonCancellable) { store.seal(token, SystemClock.elapsedRealtimeNanos(), "capture_interrupted") } }
            catch (_: Exception) { /* Recover the unsealed session on the next store access. */ }
            return@withLock
        }
        val session = Capture(token, identity, reference.epoch, generation.incrementAndGet(), elapsed, config.mount)
        session.sensor = LeanSensorCapture(context, { record -> offer(session, record) }, { session.dropped.incrementAndGet() })
        session.writer = io.launch(start = CoroutineStart.LAZY) { write(session) }
        active = session
        session.writer.start()
        if (closed) { stopLocked("capture_interrupted"); return@withLock }
        val failure = session.sensor.startOrFailure()
        if (failure != null) {
            session.reason = "sensor_unavailable"
            stopLocked("sensor_unavailable")
            publish(LeanRecordingStatus(journey.id, reference.epoch, LeanRecordingPhase.SENSOR_UNAVAILABLE, failure))
        } else if (session.accepting) onCaptureChanged()
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
        val fix = location.toLeanFix()
        if (fix == null) session.dropped.incrementAndGet() else offer(session, fix)
    }

    private fun publish(status: LeanRecordingStatus?) = synchronized(statusLock) {
        if (statusOwner === owner) mutableStatus.value = status
    }

    private fun publishProgress(session: Capture) = synchronized(statusLock) {
        if (statusOwner === owner && active === session && session.accepting) {
            val now = SystemClock.elapsedRealtimeNanos()
            val phase = session.progress.phase(now)
            val freshFix = session.lastFix?.let { now - it.elapsedNanos in 0..2_500_000_000L } == true
            mutableStatus.value = LeanRecordingStatus(session.token.journeyId, session.calibrationEpoch, phase,
                gnssHint = session.gnssHint.takeIf { phase == LeanRecordingPhase.RECORDING && freshFix })
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun receive(session: Capture, timeoutMs: Long): ChannelResult<LeanRecord>? = select {
        session.records.onReceiveCatching { it }
        onTimeout(timeoutMs) { null }
    }

    private suspend fun write(session: Capture) {
        var unpersisted: List<LeanRecord> = emptyList()
        try {
            while (true) {
                val next = receive(session, 1_000)
                if (next == null) {
                    publishProgress(session)
                    continue
                }
                val first = next.getOrNull() ?: break
                val batch = ArrayList<LeanRecord>(256).apply { add(first) }
                unpersisted = batch
                val deadline = SystemClock.elapsedRealtime() + 1_000
                while (batch.size < 256) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    batch.add(receive(session, remaining)?.getOrNull() ?: break)
                }
                val committed = store.appendCaptureBatch(session.token, batch)
                if (committed == null) {
                    session.reason = "raw_capacity_or_invalidated"
                    break
                }
                unpersisted = emptyList()
                session.progress.commit(committed)
                for (record in committed.fixes) {
                    val previous = session.lastFix
                    session.gnssHint = if (previous != null && record.elapsedNanos > previous.elapsedNanos) {
                        leanMovingGnssHint(previous, record, session.mount)
                    } else null
                    session.lastFix = record.takeIf { previous == null || it.elapsedNanos > previous.elapsedNanos }
                }
                publishProgress(session)
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
                catch (_: Exception) { session.reason = "storage_unavailable" }
            }
            if (active === session) {
                active = null
                when (session.reason) {
                    "storage_unavailable", "raw_capacity_or_invalidated" -> publish(LeanRecordingStatus(
                        session.token.journeyId, session.calibrationEpoch, LeanRecordingPhase.STORAGE_UNAVAILABLE))
                    else -> publish(null)
                }
            }
            onCaptureChanged()
        }
    }
    suspend fun stop(reason: String? = null) = mutex.withLock { stopLocked(reason) }
    private suspend fun stopLocked(reason: String?) {
        val session = active ?: return
        session.accepting = false
        publish(null)
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
        synchronized(statusLock) {
            if (statusOwner === owner) {
                statusOwner = null
                mutableStatus.value = null
            }
        }
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
    companion object {
        private val processEpoch = UUID.randomUUID().toString()
        private val statusLock = Any()
        private var statusOwner: Any? = null
        private val mutableStatus = MutableStateFlow<LeanRecordingStatus?>(null)
        val recordingStatus = mutableStatus.asStateFlow()
    }
}
