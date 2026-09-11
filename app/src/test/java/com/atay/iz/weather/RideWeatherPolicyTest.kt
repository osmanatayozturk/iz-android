package com.atay.iz.weather

import org.junit.Assert.*
import org.junit.Test

class RideWeatherPolicyTest {
    @Test fun gpsExpiryAndFutureClockBoundaries() {
        assertTrue(RideWeatherTiming.gpsFresh(100_000L, 189_999L))
        assertFalse(RideWeatherTiming.gpsFresh(100_000L, 190_000L))
        assertTrue(RideWeatherTiming.gpsFresh(104_999L, 100_000L))
        assertFalse(RideWeatherTiming.gpsFresh(105_001L, 100_000L))
    }
    @Test fun forecastExpiryDoesNotBecomeFreshWhenUiRepackagesIt() {
        assertTrue(RideWeatherTiming.forecastFresh(1_000L, 3_600_999L))
        assertFalse(RideWeatherTiming.forecastFresh(1_000L, 3_601_000L))
        assertFalse(RideWeatherTiming.forecastFresh(null, 20_000L))
    }
    @Test fun deviationRequiresThreeAccurateFixesSpanningFifteenSeconds() {
        val gate = OffRouteGate()
        assertFalse(gate.accept(300.0, 20f, 100_000L, 100_000L))
        assertFalse(gate.accept(300.0, 20f, 107_000L, 107_000L))
        assertFalse(gate.accept(300.0, 20f, 114_000L, 114_000L))
        assertTrue(gate.accept(300.0, 20f, 115_000L, 115_000L))
        assertFalse(gate.accept(300.0, 20f, 130_000L, 130_000L))
        assertFalse(gate.accept(300.0, 20f, 140_000L, 140_000L))
        assertFalse(gate.accept(300.0, 20f, 174_999L, 174_999L))
        assertTrue(gate.accept(300.0, 20f, 175_000L, 175_000L))
    }
    @Test fun badFixOrReturningToRouteResetsDeviationEvidence() {
        val gate = OffRouteGate()
        gate.accept(300.0, 20f, 100_000L, 100_000L)
        gate.accept(300.0, 20f, 108_000L, 108_000L)
        assertFalse(gate.accept(300.0, 70f, 116_000L, 116_000L))
        assertFalse(gate.accept(300.0, 20f, 120_000L, 120_000L))
        assertFalse(gate.accept(250.0, 20f, 140_000L, 140_000L))
        assertFalse(gate.accept(300.0, 20f, 160_000L, 160_000L))
    }
    @Test fun staleOrDuplicateFixCannotTriggerDeviation() {
        val gate = OffRouteGate()
        assertFalse(gate.accept(400.0, 5f, 10_000L, 200_000L))
        assertFalse(gate.accept(400.0, 5f, 201_000L, 201_000L))
        repeat(5) { assertFalse(gate.accept(400.0, 5f, 201_000L, 201_000L)) }
        assertFalse(gate.accept(400.0, 5f, 216_000L, 216_000L))
    }
    @Test fun newHazardIsNotRepeatedWhileContinuouslyPresentOrWithinCooldown() {
        val gate = WeatherAlertGate()
        assertEquals(setOf("RAIN"), gate.events(setOf("RAIN"), 100_000L, true))
        assertTrue(gate.events(setOf("RAIN"), 2_000_000L, true).isEmpty())
        assertTrue(gate.events(emptySet(), 2_100_000L, true).isEmpty())
        assertEquals(setOf("RAIN"), gate.events(setOf("RAIN"), 2_200_000L, true))
        gate.events(emptySet(), 2_300_000L, true)
        assertTrue(gate.events(setOf("RAIN"), 2_400_000L, true).isEmpty())
        assertEquals(setOf("WIND"), gate.events(setOf("RAIN", "WIND"), 2_500_000L, true))
    }
    @Test fun staleDataNeverAnnouncesOrClearsKnownHazards() {
        val gate = WeatherAlertGate()
        assertTrue(gate.events(setOf("RAIN"), 100_000L, false).isEmpty())
        assertEquals(setOf("RAIN"), gate.events(setOf("RAIN"), 100_001L, true))
        gate.events(emptySet(), 2_500_000L, false)
        assertTrue(gate.events(setOf("RAIN"), 2_600_000L, true).isEmpty())
    }
}
