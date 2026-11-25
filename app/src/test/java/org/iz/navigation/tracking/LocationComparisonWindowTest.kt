package org.iz.navigation.tracking

import org.junit.Assert.*
import org.junit.Test

class LocationComparisonWindowTest {
    @Test fun elapsedTimeUncertaintyKeepsMissingInvalidZeroAndSpreadSeparate() {
        val window = LocationComparisonWindow(1, 1_000_000_000)
        val field = ComparisonField.TIME_UNCERTAINTY_MILLIS
        window.batch(1, ComparisonSourceId.FUSED, listOf(
            sample(2_000_000_000, field to null), sample(3_000_000_000, field to Double.NaN),
            sample(4_000_000_000, field to -0.1), sample(5_000_000_000, field to 0.0),
            sample(6_000_000_000, field to 3.75)), 6_100_000_000)
        val stats = window.snapshot(7_000_000_000).fused.fields.getValue(field)
        assertEquals(1, stats.missing); assertEquals(2, stats.invalid); assertEquals(2, stats.values.count)
        assertEquals(0.0, stats.values.minimum!!, 0.0)
        assertEquals(3.75, stats.values.maximum!!, 0.0)
        assertEquals(1.875, stats.values.mean!!, 0.0)
        assertEquals(0, window.snapshot(7_000_000_000).gps.received)
    }
    @Test fun parkGateUsesOnlyFreshValidExistingEvidenceAndAlwaysBlocksAnActiveRide() {
        assertEquals(ComparisonIssue.ACTIVE_RIDE, parkedDiagnosticBlock(true, true, null, null, null, 10_000))
        assertEquals(ComparisonIssue.MOVING, parkedDiagnosticBlock(false, false, 10_000, 2f, 5f, 10_000))
        assertNull(parkedDiagnosticBlock(false, false, 4_999, 2f, 5f, 10_000))
        assertNull(parkedDiagnosticBlock(false, false, 10_001, 2f, 5f, 10_000))
        assertNull(parkedDiagnosticBlock(false, true, 10_000, 2f, 5f, 10_000))
        assertNull(parkedDiagnosticBlock(false, false, 10_000, Float.NaN, 5f, 10_000))
        assertNull(parkedDiagnosticBlock(false, false, 10_000, 2f, 51f, 10_000))
    }
    private fun sample(at: Long, vararg values: Pair<ComparisonField, Double?>) = ComparisonSample(at, values.toMap())
    @Test fun eachSourceHasItsOwnCountsAndIntervalsInTheSameWindow() {
        val window = LocationComparisonWindow(7, 1_000_000_000)
        window.batch(7, ComparisonSourceId.FUSED, listOf(sample(2_000_000_000), sample(3_000_000_000)), 3_100_000_000)
        window.batch(7, ComparisonSourceId.GPS, listOf(sample(5_000_000_000)), 5_100_000_000)
        val state = window.snapshot(11_000_000_000, ComparisonPhase.COMPLETE)
        assertEquals(10_000L, state.elapsedMillis)
        assertEquals(2, state.fused.accepted)
        assertEquals(1, state.fused.callbacks)
        assertEquals(1, state.gps.accepted)
        assertEquals(1000.0, state.fused.intervalMillis.mean!!, 0.0)
        assertNull(state.gps.intervalMillis.mean)
        assertEquals(0.2, state.fused.windowHz!!, 0.0)
        assertEquals(0.1, state.gps.windowHz!!, 0.0)
    }
    @Test fun missingInvalidAndPresentZeroAreDifferent() {
        val window = LocationComparisonWindow(1, 1_000_000_000)
        window.batch(1, ComparisonSourceId.GPS, listOf(
            sample(2_000_000_000, ComparisonField.SPEED to null),
            sample(3_000_000_000, ComparisonField.SPEED to Double.NaN, ComparisonField.BEARING to 360.0),
            sample(4_000_000_000, ComparisonField.SPEED to 0.0, ComparisonField.BEARING to 0.0)), 4_100_000_000)
        val speed = window.snapshot(5_000_000_000).gps.fields.getValue(ComparisonField.SPEED)
        assertEquals(1, speed.missing); assertEquals(1, speed.invalid)
        assertEquals(1, speed.values.count); assertEquals(0.0, speed.values.mean!!, 0.0)
    }
    @Test fun staleFutureDuplicateAndUnorderedSamplesDoNotPolluteQualityOrFrequency() {
        val window = LocationComparisonWindow(1, 1_000_000_000)
        window.batch(1, ComparisonSourceId.GPS, listOf(sample(0), sample(900_000_000), sample(5_000_000_001),
            sample(3_000_000_000), sample(3_000_000_000), sample(2_000_000_000)), 5_000_000_000)
        val gps = window.snapshot(6_000_000_000).gps
        assertEquals(6, gps.received); assertEquals(1, gps.accepted)
        assertEquals(2, gps.invalidTime); assertEquals(1, gps.outsideWindow)
        assertEquals(1, gps.duplicates); assertEquals(1, gps.outOfOrder)
        assertNull(gps.intervalMillis.mean)
    }
    @Test fun aDifferentEpochOrAClosedWindowCannotChangeTheResult() {
        val window = LocationComparisonWindow(2, 1_000_000_000)
        window.batch(1, ComparisonSourceId.FUSED, listOf(sample(2_000_000_000)), 2_000_000_000)
        window.close()
        window.batch(2, ComparisonSourceId.FUSED, listOf(sample(3_000_000_000)), 3_000_000_000)
        assertEquals(0, window.snapshot(4_000_000_000).fused.received)
    }
}
