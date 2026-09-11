package org.iz.navigation.integration

import org.iz.navigation.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSegmentsTest {
    private fun point(
        id: String,
        at: Long,
        latitude: Double = 41.0 + at / 100_000_000.0,
        journey: String = "first",
        accuracy: Float = 5f,
        breakBefore: Boolean = false,
    ) = TrackPoint(
        id = id, journeyId = journey, latitude = latitude, longitude = 29.0,
        recordedAt = at, accuracy = accuracy, breakBefore = breakBefore,
    )

    @Test fun emptyRecordingHasNoInventedRoute() {
        assertTrue(splitRouteSegments(emptyList()).isEmpty())
    }

    @Test fun journeysNeverConnectEvenWhenTheirPointsInterleave() {
        val lines = splitRouteSegments(listOf(
            point("a", 0), point("b", 5_000, journey = "second"),
            point("c", 10_000), point("d", 15_000, journey = "second"),
        ))
        assertEquals(listOf(2, 2), lines.map { it.size })
    }

    @Test fun missingSignalAndExplicitBreakProduceSeparateLines() {
        val lines = splitRouteSegments(listOf(
            point("a", 0), point("b", 10_000),
            point("c", 140_001), point("d", 150_001),
            point("e", 160_001, breakBefore = true), point("f", 170_001),
        ))
        assertEquals(listOf(2, 2, 2), lines.map { it.size })
    }

    @Test fun rejectedFixBreaksTheLineInsteadOfJoiningAcrossIt() {
        val lines = splitRouteSegments(listOf(
            point("a", 0), point("bad", 10_000, accuracy = 500f),
            point("c", 20_000), point("d", 30_000),
        ))
        assertEquals(listOf(1, 2), lines.map { it.size })
    }

    @Test fun repeatedTimestampDoesNotConnect() {
        val lines = splitRouteSegments(listOf(
            point("a", 10_000), point("b", 10_000), point("c", 20_000),
        ))
        assertEquals(listOf(1, 2), lines.map { it.size })
    }

    @Test fun implausibleTeleportDoesNotDrawAFalseJourney() {
        val lines = splitRouteSegments(listOf(
            point("a", 0), point("b", 10_000),
            point("c", 20_000, latitude = 50.0), point("d", 30_000, latitude = 50.0001),
        ))
        assertEquals(listOf(2, 2), lines.map { it.size })
    }

    @Test fun outOfOrderFixesAreSortedWithinTheirJourney() {
        val lines = splitRouteSegments(listOf(point("c", 20_000), point("a", 0), point("b", 10_000)))
        assertEquals(1, lines.size)
        assertEquals(41.0, lines.single().first().latitude, 0.000001)
        assertEquals(41.0002, lines.single().last().latitude, 0.000001)
    }

    @Test fun nonFiniteMetadataIsRejectedWithoutClampingCoordinates() {
        val badCoordinates = point("bad", 10_000, latitude = 91.0)
        val badSpeed = point("speed", 20_000).copy(speed = Float.NaN)
        assertEquals(1, splitRouteSegments(listOf(point("a", 0), badCoordinates, badSpeed)).single().size)
    }
}