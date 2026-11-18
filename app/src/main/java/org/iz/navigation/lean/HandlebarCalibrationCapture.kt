package org.iz.navigation.lean

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal sealed interface HandlebarCalibrationResult {
    data class Ready(val profile: LeanCalibration) : HandlebarCalibrationResult
    data object SensorsUnavailable : HandlebarCalibrationResult
    data object InvalidSamples : HandlebarCalibrationResult
}

/** Bounded foreground capture. Sensor samples stay in memory until the attempt ends. */
internal suspend fun captureHandlebarCalibration(
    context: Context, onStep: (HandlebarCalibrationStep) -> Unit, onAccepted: () -> Unit,
    awaitRetry: suspend (HandlebarCalibrationStep) -> Unit,
): HandlebarCalibrationResult {
    val accepted = mutableListOf<List<LeanImuRecord>>()
    for (step in HandlebarCalibrationStep.entries) {
        while (true) {
            coroutineContext.ensureActive()
            val samples = collectStep(context, step, onStep) ?: return HandlebarCalibrationResult.SensorsUnavailable
            val candidate = accepted + listOf(samples)
            if (step == HandlebarCalibrationStep.CENTER) {
                val profile = handlebarCalibration(candidate[0], candidate[1], candidate[2], candidate[3],
                    UUID.randomUUID().toString(), System.currentTimeMillis())
                if (profile != null) {
                    onAccepted()
                    delay(300)
                    return HandlebarCalibrationResult.Ready(profile)
                }
            } else if (handlebarStepsAreValid(candidate)) {
                accepted += listOf(samples)
                onAccepted()
                delay(450)
                break
            }
            awaitRetry(step)
        }
    }
    return HandlebarCalibrationResult.InvalidSamples
}

private suspend fun collectStep(context: Context, step: HandlebarCalibrationStep,
    onStep: (HandlebarCalibrationStep) -> Unit): List<LeanImuRecord>? {
    val window = HandlebarSampleWindow()
    val capture = LeanSensorCapture(context, window::add)
    if (!capture.start()) return null
    return try {
        // Discard the touch used to start or retry. Move only when the step cue sounds.
        delay(750)
        window.begin(SystemClock.elapsedRealtimeNanos())
        onStep(step)
        delay(step.durationMillis)
        window.finish()
    } finally {
        capture.stop()
    }
}

private class HandlebarSampleWindow {
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
        if (samples.size < 1_024) samples += record else overflow = true
    }

    @Synchronized fun finish(): List<LeanImuRecord> {
        startedAt = Long.MAX_VALUE
        return if (overflow) emptyList() else samples.toList()
    }
}
