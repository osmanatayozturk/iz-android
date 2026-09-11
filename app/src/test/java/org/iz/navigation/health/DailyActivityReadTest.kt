package org.iz.navigation.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.iz.navigation.wearprotocol.WearDailyHealthStatus.*

class DailyActivityReadTest {
    private class Reader : DailyHealthReader {
        var stepsResult: Long? = 0
        var distanceResults = mutableListOf<Double?>(null)
        var sessions = listOf(DailyExercise(DailyRange(10, 30), true))
        var stepsCalls = 0
        val distanceCalls = mutableListOf<DailyRange>()
        override suspend fun steps(range: DailyRange): Long? { stepsCalls++; return stepsResult }
        override suspend fun exercises(range: DailyRange) = sessions
        override suspend fun distance(range: DailyRange): Double? { distanceCalls += range; return distanceResults.removeAt(0) }
    }
    @Test fun partialPermissionsKeepIndependentStepsWithoutReadingExercises() = runBlocking {
        val reader = Reader()
        val result = readDailyHealth(reader, DailyRange(0, 100), true, false, true)
        assertEquals(0L, result.steps)
        assertEquals(AVAILABLE, result.stepsStatus)
        assertNull(result.exerciseMillis)
        assertEquals(PERMISSION_REQUIRED, result.exerciseStatus)
        assertEquals(PERMISSION_REQUIRED, result.distanceStatus)
        assertTrue(reader.distanceCalls.isEmpty())
    }
    @Test fun duplicateSessionsYieldOneDistanceQueryAndMissingIsNotZero() = runBlocking {
        val reader = Reader().apply { sessions = sessions + sessions; stepsResult = null }
        val result = readDailyHealth(reader, DailyRange(0, 100), true, true, true)
        assertEquals(20L, result.exerciseMillis)
        assertEquals(listOf(DailyRange(10, 30)), reader.distanceCalls)
        assertNull(result.steps)
        assertNull(result.exerciseMeters)
        assertEquals(UNAVAILABLE, result.distanceStatus)
    }
    @Test fun partialDistanceDoesNotDiscardSuccessfulRange() = runBlocking {
        val reader = Reader().apply {
            sessions = listOf(DailyExercise(DailyRange(10, 30), true), DailyExercise(DailyRange(50, 70), true))
            distanceResults = mutableListOf(100.0, null)
        }
        val result = readDailyHealth(reader, DailyRange(0, 100), false, true, true)
        assertEquals(100.0, result.exerciseMeters!!, 0.0)
        assertEquals(PARTIAL, result.distanceStatus)
        assertEquals(0, reader.stepsCalls)
    }
    @Test fun knownSamsungCyclingSessionMeansZeroWalkingExercise() = runBlocking {
        val reader = Reader().apply { sessions = listOf(DailyExercise(DailyRange(10, 30), false)) }
        val result = readDailyHealth(reader, DailyRange(0, 100), true, true, true)
        assertEquals(0L, result.exerciseMillis)
        assertEquals(0.0, result.exerciseMeters!!, 0.0)
        assertEquals(AVAILABLE, result.exerciseStatus)
        assertTrue(reader.distanceCalls.isEmpty())
    }
    @Test fun emptySamsungExerciseSourceRemainsMissing() = runBlocking {
        val reader = Reader().apply { sessions = emptyList(); stepsResult = null }
        val result = readDailyHealth(reader, DailyRange(0, 100), true, true, true)
        assertNull(result.exerciseMillis)
        assertNull(result.exerciseMeters)
        assertEquals(UNAVAILABLE, result.exerciseStatus)
    }
}
