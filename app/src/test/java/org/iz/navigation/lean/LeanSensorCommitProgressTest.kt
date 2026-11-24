package org.iz.navigation.lean

import org.junit.Assert.assertEquals
import org.junit.Test

class LeanSensorCommitProgressTest {
    @Test fun emptyAndSingleSensorBatchesDoNotClaimRecording() {
        val progress = LeanSensorCommitProgress(0)
        progress.commit(LeanPersistedBatch())
        assertEquals(LeanRecordingPhase.WAITING_FOR_DATA, progress.phase(seconds(3)))
        progress.commit(LeanPersistedBatch(accelerometerNanos = seconds(9)))
        assertEquals(LeanRecordingPhase.GYROSCOPE_DATA_MISSING, progress.phase(seconds(12)))
        progress.commit(LeanPersistedBatch(gyroscopeNanos = seconds(12)))
        assertEquals(LeanRecordingPhase.RECORDING, progress.phase(seconds(13)))
        assertEquals(LeanRecordingPhase.ACCELEROMETER_DATA_MISSING, progress.phase(seconds(20)))
        assertEquals(LeanRecordingPhase.BOTH_SENSORS_DATA_MISSING, progress.phase(seconds(23)))
    }

    @Test fun aNewSessionNeedsItsOwnBothSensorCommits() {
        val progress = LeanSensorCommitProgress(seconds(20))
        progress.commit(LeanPersistedBatch(accelerometerNanos = seconds(19), gyroscopeNanos = seconds(21)))
        assertEquals(LeanRecordingPhase.WAITING_FOR_DATA, progress.phase(seconds(22)))
        progress.commit(LeanPersistedBatch(accelerometerNanos = seconds(23)))
        assertEquals(LeanRecordingPhase.RECORDING, progress.phase(seconds(24)))
    }

    private fun seconds(seconds: Long) = seconds * 1_000_000_000L
}
