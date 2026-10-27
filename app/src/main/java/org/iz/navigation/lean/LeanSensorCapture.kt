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
import kotlin.math.sqrt

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
    @Synchronized fun start(): Boolean {
        if (active.get()) return true
        val sensors = manager ?: return false
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        val worker = HandlerThread("IzLeanSensors").also { it.start() }
        thread = worker
        active.set(true)
        return try {
            val handler = Handler(worker.looper)
            val first = sensors.registerListener(listener, accel, 20_000, 0, handler)
            val second = sensors.registerListener(listener, gyro, 20_000, 0, handler)
            if (!first || !second) { stop(); false } else true
        } catch (_: RuntimeException) { stop(); false }
    }
    @Synchronized fun stop() {
        active.set(false)
        runCatching { manager?.unregisterListener(listener) }
        thread?.quitSafely(); thread = null
    }
    companion object {
        /** Explicit bounded foreground reference only; no raw session, navigation or recording start. */
        suspend fun calibrate(context: Context): LeanCalibration? = withContext(Dispatchers.Default) {
            val samples = LeanCalibrationAccumulator()
            val capture = LeanSensorCapture(context, samples::add)
            if (!capture.start()) return@withContext null
            try {
                delay(3_000)
                samples.finish(UUID.randomUUID().toString(), System.currentTimeMillis())
            } finally { capture.stop() }
        }
    }
}

/** Bounded reference statistics, not a chassis-bank estimate or complete three-axis calibration. */
internal class LeanCalibrationAccumulator {
    private val samples = arrayOf(ArrayList<LeanImuRecord>(160), ArrayList<LeanImuRecord>(160))
    @Synchronized fun add(record: LeanImuRecord) {
        val slot = if (record.kind == LeanSensorKind.ACCELEROMETER) 0 else 1
        if (record.accuracy <= 0 || listOf(record.x, record.y, record.z).any { !it.isFinite() } ||
            samples[slot].size >= 200) return
        samples[slot].add(record)
    }
    @Synchronized fun finish(epoch: String, createdAt: Long): LeanCalibration? {
        if (samples.any { it.size < 75 || it.last().elapsedNanos - it.first().elapsedNanos < 2_000_000_000L }) return null
        fun vector(list: List<LeanImuRecord>): List<Double> = listOf(list.map { it.x }.average(),
            list.map { it.y }.average(), list.map { it.z }.average())
        fun variance(list: List<LeanImuRecord>, mean: List<Double>): Double = list.sumOf { record ->
            (record.x - mean[0]) * (record.x - mean[0]) + (record.y - mean[1]) * (record.y - mean[1]) +
                (record.z - mean[2]) * (record.z - mean[2])
        } / list.size
        val gravity = vector(samples[0]); val bias = vector(samples[1])
        if (sqrt(gravity.sumOf { it * it }) !in 8.8..10.8 || variance(samples[0], gravity) > .15 * .15 ||
            variance(samples[1], bias) > .03 * .03 || sqrt(bias.sumOf { it * it }) > .1) return null
        return LeanCalibration(epoch, createdAt, gravity, bias).takeIf(::validLeanReference)
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