package org.iz.navigation.speed

import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

class RoadSpeedPolicyTest {
    private fun fix(at: Long = 10_000, lon: Double = .005, speed: Float? = 10f, bearing: Float? = 90f) =
        NavigationFix(WeatherCoordinate(0.0, lon), at, 3f, speed, bearing)
    private fun road(id: Long = 1, lat: Double = 0.0) = OsmRoad(id,
        listOf(WeatherCoordinate(lat, 0.0), WeatherCoordinate(lat, .02)), listOf(id * 10, id * 10 + 1), mapOf("highway" to "residential"))

    @Test fun ownGpsSpeedIncludesZeroButExpiresWithoutAnotherFix() {
        assertEquals(36.0, ownSpeedKmh(fix(), 10_000)!!, .001)
        assertEquals(0.0, ownSpeedKmh(fix(speed = 0f), 15_000)!!, .001)
        assertNull(ownSpeedKmh(fix(), 15_001))
        assertNull(ownSpeedKmh(fix(speed = Float.NaN), 10_000))
        assertNull(ownSpeedKmh(fix(speed = 101f), 10_000))
        assertNull(ownSpeedKmh(fix().copy(accuracyMeters = 51f), 10_000))
    }
    @Test fun numericUnitsAndUnlimitedAreExplicit() {
        assertEquals(OsmSpeedValue.Known(50.0), osmSpeedValue(mapOf("maxspeed" to "50"), Transport.CAR, true))
        assertEquals(48.28032, (osmSpeedValue(mapOf("maxspeed" to "30 mph"), Transport.CAR, true) as OsmSpeedValue.Known).kmh!!, .00001)
        assertEquals(OsmSpeedValue.Known(null), osmSpeedValue(mapOf("maxspeed" to "none"), Transport.CAR, true))
    }
    @Test fun directionAndMotorcycleOverrideGeneralTag() {
        val tags = mapOf("maxspeed" to "90", "maxspeed:forward" to "70", "maxspeed:backward" to "50", "maxspeed:motorcycle:forward" to "60")
        assertEquals(OsmSpeedValue.Known(70.0), osmSpeedValue(tags, Transport.CAR, true))
        assertEquals(OsmSpeedValue.Known(50.0), osmSpeedValue(tags, Transport.CAR, false))
        assertEquals(OsmSpeedValue.Known(60.0), osmSpeedValue(tags, Transport.MOTORCYCLE, true))
        assertEquals(OsmSpeedValue.Unknown, osmSpeedValue(tags, Transport.CAR, null))
    }
    @Test fun unresolvedRestrictionsCannotBecomeMissingAndTriggerGenericFallback() {
        listOf("TR:urban", "walk", "variable", "50;70", "signals").forEach {
            assertEquals(OsmSpeedValue.Unknown, osmSpeedValue(mapOf("maxspeed" to it), Transport.CAR, true))
        }
        assertEquals(OsmSpeedValue.Unknown, osmSpeedValue(mapOf("maxspeed" to "90", "maxspeed:conditional" to "50 @ wet"), Transport.CAR, true))
        assertEquals(OsmSpeedValue.Missing, osmSpeedValue(emptyMap(), Transport.CAR, true))
        assertEquals(OsmSpeedValue.Unknown, osmSpeedValue(mapOf("maxspeed:lanes:forward" to "50|70"), Transport.CAR, true))
        assertEquals(OsmSpeedValue.Known(90.0), osmSpeedValue(mapOf("maxspeed" to "90", "maxspeed:hgv:conditional" to "50 @ wet"), Transport.CAR, true))
    }
    @Test fun stableUniqueRoadMatchesAndDirectionChangeClearsImmediately() {
        val matcher = LocalRoadMatcher()
        assertNull(matcher.match(fix(), listOf(road())))
        assertNull(matcher.match(fix(11_000, .0051), listOf(road())))
        assertEquals("1:f", matcher.match(fix(12_000, .0052), listOf(road()))?.identity)
        assertNull(matcher.match(fix(13_000, .0051, bearing = 270f), listOf(road())))
    }
    @Test fun parallelRoadWithoutSpeedTagStillMakesMatchAmbiguous() {
        val matcher = LocalRoadMatcher()
        repeat(5) { assertNull(matcher.match(fix(10_000L + it * 1_000), listOf(road(), road(2, .00005)))) }
    }

    @Test fun parentVehicleAndLegacyWetRestrictionsNeverTriggerGenericFallback() {
        listOf(Transport.CAR, Transport.MOTORCYCLE).forEach { transport ->
            assertEquals(OsmSpeedValue.Unknown, osmSpeedValue(mapOf("maxspeed" to "90", "maxspeed:vehicle:conditional" to "30 @ wet"), transport, true))
            assertEquals(OsmSpeedValue.Unknown, osmSpeedValue(mapOf("maxspeed:vehicle:conditional" to "30 @ wet"), transport, true))
            assertEquals(OsmSpeedValue.Unknown, osmSpeedValue(mapOf("maxspeed" to "90", "maxspeed:wet" to "50"), transport, true))
        }
        assertEquals(OsmSpeedValue.Known(70.0), osmSpeedValue(mapOf("maxspeed" to "90", "maxspeed:vehicle" to "70"), Transport.CAR, true))
    }

    @Test fun denseStraightGeometryIsOneRoadWhileDisjointSelfCrossingRemainsUnknown() {
        val dense = road().copy(points = (0..200).map { WeatherCoordinate(0.0, it * .000045) })
        val matcher = LocalRoadMatcher()
        repeat(3) { matcher.match(fix(10_000L + it * 1_000, .0045 + it * .000045), listOf(dense)) }
        assertEquals("1:f", matcher.match(fix(13_000, .004635), listOf(dense))?.identity)
        val crossing = road().copy(points = listOf(WeatherCoordinate(0.0, 0.0), WeatherCoordinate(0.0, .02),
            WeatherCoordinate(.02, .02), WeatherCoordinate(.02, .005), WeatherCoordinate(-.02, .005)))
        matcher.reset()
        repeat(4) { assertNull(matcher.match(fix(20_000L + it * 1_000), listOf(crossing))) }
    }
}
