package org.iz.navigation.lean

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicReference

/** A local foreground check that keeps only aggregate measurements in memory. */
internal class AndroidLeanImuCheck(context: Context) {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)

    fun check(): Flow<LeanImuCheckProgress> = flow {
        val worker = HandlerThread("IzLeanImuCheck")
        val sensors = mapOf(LeanSensorKind.ACCELEROMETER to Sensor.TYPE_ACCELEROMETER,
            LeanSensorKind.GYROSCOPE to Sensor.TYPE_GYROSCOPE)
        val sensorManager = manager
        val activeWindow = AtomicReference<LeanImuCheckWindow?>()
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

            override fun onSensorChanged(event: SensorEvent) {
                val kind = when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> LeanSensorKind.ACCELEROMETER
                    Sensor.TYPE_GYROSCOPE -> LeanSensorKind.GYROSCOPE
                    else -> return
                }
                val values = event.values
                activeWindow.get()?.accept(kind, event.timestamp, SystemClock.elapsedRealtimeNanos(),
                    values.size >= 3 && values[0].isFinite() && values[1].isFinite() && values[2].isFinite())
            }
        }
        val registration = try {
            if (sensorManager == null) sensors.mapValues { LeanImuCheckRegistration.FAILED }
            else {
                worker.start()
                val handler = Handler(worker.looper)
                sensors.mapValues { (_, type) ->
                    try {
                        val sensor = sensorManager.getDefaultSensor(type)
                        when {
                            sensor == null -> LeanImuCheckRegistration.MISSING
                            sensorManager.registerListener(listener, sensor, 20_000, 0, handler) -> LeanImuCheckRegistration.LISTENING
                            else -> LeanImuCheckRegistration.FAILED
                        }
                    } catch (_: SecurityException) {
                        LeanImuCheckRegistration.PERMISSION_RESTRICTED
                    } catch (_: RuntimeException) {
                        LeanImuCheckRegistration.FAILED
                    }
                }
            }
        } catch (_: RuntimeException) {
            sensors.mapValues { LeanImuCheckRegistration.FAILED }
        }
        val window = LeanImuCheckWindow(SystemClock.elapsedRealtimeNanos())
        activeWindow.set(window)
        try {
            if (LeanImuCheckRegistration.LISTENING in registration.values) {
                while (true) {
                    val remaining = window.remainingNanos(SystemClock.elapsedRealtimeNanos())
                    if (remaining == 0L) break
                    emit(LeanImuCheckProgress(remaining, registration, window.counts(), complete = false))
                    delay(minOf(250L, (remaining + 999_999L) / 1_000_000L))
                }
            }
        } finally {
            activeWindow.set(null)
            try { sensorManager?.unregisterListener(listener) }
            finally { if (worker.isAlive) worker.quitSafely() }
        }
        emit(LeanImuCheckProgress(0L, registration, window.counts(), complete = true))
    }.flowOn(Dispatchers.Default)
}
