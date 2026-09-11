package com.atay.iz.tracking

import org.junit.Assert.*
import org.junit.Test

class WalkingStepCounterTest {
    @Test fun cumulativeReadingStartsAtZeroInsteadOfCountingStepsBeforeTheJourney() {
        val counter = WalkingStepCounter(startedAtNanos = 100)
        assertNull(counter.observeCounter(9_000f, 99))
        assertEquals(0L, counter.observeCounter(9_004f, 100))
        assertEquals(7L, counter.observeCounter(9_011f, 110))
        assertEquals(7L, counter.observeCounter(9_011f, 120))
    }

    @Test fun resumedSessionPreservesSavedStepsWithoutCountingTheUnobservedGap() {
        val counter = WalkingStepCounter(startedAtNanos = 200, initialCount = 43)
        assertEquals(43L, counter.observeCounter(30_000f, 200))
        assertEquals(48L, counter.observeCounter(30_005f, 210))
    }

    @Test fun switchingJourneysCreatesANewBaselineAndRejectsQueuedOldEvents() {
        val first = WalkingStepCounter(startedAtNanos = 100)
        first.observeCounter(50f, 100)
        assertEquals(12L, first.observeCounter(62f, 150))
        val next = WalkingStepCounter(startedAtNanos = 200)
        assertNull(next.observeCounter(64f, 190))
        assertEquals(0L, next.observeCounter(70f, 200))
        assertEquals(3L, next.observeCounter(73f, 210))
    }

    @Test fun rebootOrSensorResetKeepsAlreadyMeasuredStepsWithoutNegativeDeltas() {
        val counter = WalkingStepCounter(startedAtNanos = 0)
        counter.observeCounter(1_000f, 100)
        assertEquals(6L, counter.observeCounter(1_006f, 110))
        assertEquals(6L, counter.observeCounter(2f, 120))
        assertEquals(10L, counter.observeCounter(6f, 130))
    }

    @Test fun duplicateOutOfOrderAndInvalidSensorEventsNeverAddSteps() {
        val counter = WalkingStepCounter(startedAtNanos = 100)
        assertNull(counter.observeCounter(Float.NaN, 100))
        assertNull(counter.observeCounter(Float.POSITIVE_INFINITY, 100))
        assertNull(counter.observeCounter(-1f, 100))
        assertNull(counter.observeCounter(2.5f, 100))
        assertEquals(0L, counter.observeCounter(20f, 110))
        assertNull(counter.observeCounter(40f, 110))
        assertNull(counter.observeCounter(99f, 105))
        assertEquals(3L, counter.observeCounter(23f, 120))
    }

    @Test fun detectorFallbackCountsOnlyIndividualStepsAfterAttachment() {
        val counter = WalkingStepCounter(startedAtNanos = 100)
        assertNull(counter.observeDetector(1f, 99))
        assertNull(counter.observeDetector(2f, 100))
        assertNull(counter.observeDetector(Float.NaN, 100))
        assertEquals(1L, counter.observeDetector(1f, 100))
        assertNull(counter.observeDetector(1f, 100))
        assertEquals(2L, counter.observeDetector(1f, 110))
    }

    @Test fun extremelyLargeValuesCannotOverflowIntoNegativeSteps() {
        val counter = WalkingStepCounter(startedAtNanos = 0, initialCount = Long.MAX_VALUE - 1)
        assertNull(counter.observeCounter(Float.MAX_VALUE, 100))
        counter.observeCounter(1f, 100)
        assertEquals(Long.MAX_VALUE, counter.observeCounter(20f, 110))
    }
}
