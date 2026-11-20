package org.iz.navigation.lean

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** No retained SensorEvent/values array. Android reuses them after onSensorChanged returns.
 * Source: https://developer.android.com/reference/android/hardware/SensorEvent#timestamp
 * Requested 20ms is a target, not an actual-rate guarantee; estimator validates real timestamps.
 */
internal class LeanSensorCapture(context: Context, private val onRecord: (LeanImuRecord) -> Unit,
    private val onDropped: () -> Unit = {}) {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private val active = AtomicBoolean(false)
    private val sequence = LeanSensorSequences()
    private val last = LongArray(2)
    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            if (!active.get()) return
            val kind = when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> LeanSensorKind.ACCELEROMETER
                Sensor.TYPE_GYROSCOPE -> LeanSensorKind.GYROSCOPE
                else -> return
            }
            val seq = sequence.next(kind)
            val slot = if (kind == LeanSensorKind.ACCELEROMETER) 0 else 1
            val timestamp = event.timestamp
            val values = event.values
            val range = event.sensor.maximumRange.toDouble()
            if (values.size < 3 || timestamp <= last[slot] || timestamp <= 0 ||
                (0..2).any { !values[it].isFinite() || range > 0 && abs(values[it].toDouble()) >= range * .999 }) {
                onDropped(); return
            }
            last[slot] = timestamp
            val record = LeanImuRecord(kind, seq, timestamp, values[0].toDouble(), values[1].toDouble(),
                values[2].toDouble(), event.accuracy)
            if (active.get()) onRecord(record)
        }
    }
    @Synchronized fun startOrFailure(): CalibrationFailure? {
        if (active.get()) return null
        val sensors = manager ?: return CalibrationFailure.SENSOR_SERVICE
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return CalibrationFailure.ACCELEROMETER_MISSING
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return CalibrationFailure.GYROSCOPE_MISSING
        val worker = HandlerThread("IzLeanSensors")
        thread = worker
        var failure = CalibrationFailure.SENSOR_SERVICE
        return try {
            worker.start()
            active.set(true)
            val handler = Handler(worker.looper)
            failure = CalibrationFailure.ACCELEROMETER_START
            if (!sensors.registerListener(listener, accel, 20_000, 0, handler)) return failure.also { stop() }
            failure = CalibrationFailure.GYROSCOPE_START
            if (!sensors.registerListener(listener, gyro, 20_000, 0, handler)) return failure.also { stop() }
            null
        } catch (_: RuntimeException) {
            stop()
            failure
        }
    }
    @Synchronized fun stop() {
        active.set(false)
        runCatching { manager?.unregisterListener(listener) }
        thread?.quitSafely(); thread = null
    }
    companion object {
        /** Explicit bounded foreground reference only; no raw session, navigation or recording start. */
        suspend fun calibrate(context: Context): CalibrationResult = withContext(Dispatchers.Default) {
            val samples = LeanCalibrationAccumulator()
            val capture = LeanSensorCapture(context, samples::add)
            // Let the tap that started calibration settle before the stationary window.
            delay(750)
            capture.startOrFailure()?.let { return@withContext CalibrationResult.Failed(it) }
            try {
                delay(3_000)
                samples.finish(UUID.randomUUID().toString(), System.currentTimeMillis())
            } finally { capture.stop() }
        }
    }
}

/** Bounded reference statistics, not a chassis-bank estimate or complete three-axis calibration. */
internal class LeanCalibrationAccumulator {
    private val accel = ReferenceStatistics()
    private val gyro = ReferenceStatistics()

    @Synchronized fun add(record: LeanImuRecord) {
        (if (record.kind == LeanSensorKind.ACCELEROMETER) accel else gyro).add(record)
    }

    @Synchronized fun finish(epoch: String, createdAt: Long): CalibrationResult {
        val failure = accel.failure() ?: gyro.failure() ?: when {
            accel.mean.norm() !in 8.8..10.8 -> CalibrationFailure.GRAVITY
            accel.variance > .15 * .15 || gyro.variance > .03 * .03 -> CalibrationFailure.MOVEMENT
            gyro.mean.norm() > .1 -> CalibrationFailure.GYRO_DRIFT
            else -> null
        }
        if (failure != null) return CalibrationResult.Failed(failure)
        return CalibrationResult.Ready(LeanCalibration(epoch, createdAt, accel.mean.values(), gyro.mean.values()))
    }
}

/** Streaming population variance covers the full measurement window at any delivered sample rate. */
private class ReferenceStatistics {
    private var count = 0
    private var unreliable = 0
    private var firstNanos = 0L
    private var lastNanos = 0L
    var mean = LeanVector(0.0, 0.0, 0.0)
        private set
    private var squaredDeviations = 0.0
    val variance get() = squaredDeviations / count

    fun add(record: LeanImuRecord) {
        if (record.accuracy <= 0) { unreliable++; return }
        if (!record.x.isFinite() || !record.y.isFinite() || !record.z.isFinite() || record.elapsedNanos <= lastNanos) return
        if (count == 0) firstNanos = record.elapsedNanos
        lastNanos = record.elapsedNanos
        count++
        val point = LeanVector(record.x, record.y, record.z)
        val delta = point - mean
        mean = LeanVector(mean.x + delta.x / count, mean.y + delta.y / count, mean.z + delta.z / count)
        squaredDeviations += delta.dot(point - mean)
    }

    fun failure(): CalibrationFailure? = when {
        count < 75 && unreliable > 0 && count + unreliable >= 75 -> CalibrationFailure.UNRELIABLE_SAMPLES
        count < 75 -> CalibrationFailure.INSUFFICIENT_SAMPLES
        lastNanos - firstNanos < 2_000_000_000L -> CalibrationFailure.SHORT_WINDOW
        else -> null
    }
}
/** Sequence is per sensor stream, never per batch. Observed dropped events still consume a number. */
internal class LeanSensorSequences {
    private val values = LongArray(2)
    @Synchronized fun next(kind: LeanSensorKind): Long {
        val slot = if (kind == LeanSensorKind.ACCELEROMETER) 0 else 1
        return values[slot]++
    }
}