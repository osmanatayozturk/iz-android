package org.iz.navigation.lean

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Samples from a park calibration stay in memory. Only a completed, accepted profile can be saved. */
internal suspend fun captureHandlebarCalibration(
    context: Context, onStep: (HandlebarCalibrationStep) -> Unit, onAccepted: () -> Unit,
    awaitRetry: suspend (HandlebarCalibrationStep, CalibrationFailure) -> Unit,
): CalibrationResult {
    val accepted = mutableListOf<List<LeanImuRecord>>()
    val epoch = UUID.randomUUID().toString()
    for (step in HandlebarCalibrationStep.entries) {
        while (true) {
            coroutineContext.ensureActive()
            val window = HandlebarSampleWindow()
            val capture = LeanSensorCapture(context, window::add)
            capture.startOrFailure()?.let { return CalibrationResult.Failed(it) }
            val samples = try {
                // Discard the touch that started this attempt. The cue marks the beginning of the step.
                delay(750)
                window.begin(SystemClock.elapsedRealtimeNanos())
                onStep(step)
                delay(step.durationMillis)
                window.finish()
            } finally {
                capture.stop()
            }
            if (samples == null) {
                awaitRetry(step, CalibrationFailure.BUFFER_FULL)
                continue
            }
            when (val checked = evaluateHandlebarSteps(accepted + listOf(samples), epoch, System.currentTimeMillis())) {
                is CalibrationResult.Failed -> awaitRetry(step, checked.reason)
                is CalibrationResult.Ready -> {
                    onAccepted()
                    delay(300)
                    return checked
                }
                StepAccepted -> {
                    accepted += listOf(samples)
                    onAccepted()
                    delay(450)
                    break
                }
            }
        }
    }
    error("Final handlebar step must produce a reference")
}

internal class HandlebarSampleWindow {
    private val samples = ArrayList<LeanImuRecord>()
    private var startedAt = Long.MAX_VALUE
    private var overflow = false

    @Synchronized fun begin(time: Long) {
        samples.clear()
        startedAt = time
        overflow = false
    }

    @Synchronized fun add(record: LeanImuRecord) {
        if (record.elapsedNanos < startedAt) return
        // Five seconds at 200 Hz per sensor plus delivery margin.
        if (samples.size < 2_560) samples += record else overflow = true
    }

    @Synchronized fun finish(): List<LeanImuRecord>? {
        startedAt = Long.MAX_VALUE
        return if (overflow) null else samples.toList()
    }
}
