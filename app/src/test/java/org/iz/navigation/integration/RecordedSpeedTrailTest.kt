package org.iz.navigation.integration

import org.iz.navigation.data.TrackPoint
import org.junit.Assert.*
import org.junit.Test

class RecordedSpeedTrailTest {
    private fun p(index: Int, speed: Float? = 10f, journey: String = "j") = TrackPoint(
        id = "$journey-$index", journeyId = journey, recordedAt = index * 5_000L,
        latitude = 41.0, longitude = 29.0 + index * .0001, accuracy = 5f, speed = speed)

    @Test fun measuredZeroAndHighestSpeedReachPaletteEndpoints() {
        val result = recordedSpeedTrail(listOf(p(0, 0f), p(1, 10f), p(2, 20f)))
        assertEquals(0.0, result.scales.single().minKmh, .001)
        assertEquals(72.0, result.scales.single().maxKmh, .001)
        assertEquals("#2563EB", result.lines.first().color)
        assertEquals("#DC2626", result.lines.last().color)
        assertTrue(result.lines.map { it.color }.distinct().size > 4)
    }

    @Test fun identicalSpeedsUseSingleBlueToneWithActualLegend() {
        val result = recordedSpeedTrail(listOf(p(0), p(1), p(2)))
        assertEquals(setOf("#2563EB"), result.lines.map { it.color }.toSet())
        assertEquals(36.0, result.scales.single().minKmh, .001)
        assertEquals(36.0, result.scales.single().maxKmh, .001)
    }

    @Test fun absentSpeedKeepsGeometryAndUsesGrayWithoutInventingSpeed() {
        val result = recordedSpeedTrail(listOf(p(0, null), p(1, null)))
        assertEquals(1, result.paths.size)
        assertEquals("#87939B", result.lines.single().color)
        assertTrue(result.scales.isEmpty())
    }

    @Test fun unknownEndpointMakesThatIntervalGray() {
        val result = recordedSpeedTrail(listOf(p(0, 0f), p(1, 10f), p(2, null)))
        assertEquals("#87939B", result.lines.last().color)
        assertEquals(36.0, result.scales.single().maxKmh, .001)
    }

    @Test fun invalidMetadataStillBreaksTheRecordedGeometry() {
        val result = recordedSpeedTrail(listOf(p(0), p(1), p(2, Float.NaN), p(3), p(4)))
        assertEquals(listOf(2, 2), result.paths.map { it.size })
        assertTrue(result.lines.none { line -> line.coordinates.any { it.longitude == p(2).longitude } })
    }

    @Test fun explicitBreakTimeGapAndJourneyIdentityNeverGetBridged() {
        val result = recordedSpeedTrail(listOf(p(0), p(1), p(2).copy(breakBefore = true), p(3),
            p(40), p(41), p(0, journey = "b"), p(1, journey = "b")))
        assertEquals(4, result.paths.size)
        assertEquals(setOf("j", "b"), result.scales.map { it.journeyId }.toSet())
        assertEquals(4, result.lines.size)
    }

    @Test fun isolatedHighSpeedCannotChangeDrawableJourneyScale() {
        val result = recordedSpeedTrail(listOf(p(0, 100f), p(1, 5f).copy(breakBefore = true), p(2, 10f)))
        assertEquals(36.0, result.scales.single().maxKmh, .001)
        assertEquals(18.0, result.scales.single().minKmh, .001)
    }

    @Test fun implausibleReportedSpeedIsNeutralRatherThanStretchingColorScale() {
        val result = recordedSpeedTrail(listOf(p(0, 0f), p(1, 10f), p(2, 500f)))
        assertEquals(36.0, result.scales.single().maxKmh, .001)
        assertEquals("#87939B", result.lines.last().color)
    }

    @Test fun newLiveMaximumRescalesOldTrailButPreservesMeasurements() {
        val points = listOf(p(0, 0f), p(1, 10f))
        val earlier = recordedSpeedTrail(points)
        val later = recordedSpeedTrail(points + p(2, 20f))
        assertEquals(36.0, earlier.scales.single().maxKmh, .001)
        assertEquals(72.0, later.scales.single().maxKmh, .001)
        assertEquals(10f, points.last().speed)
    }

    @Test fun journeyScalesAreIndependentAndInputIsOrderedWithoutMutatingIt() {
        val points = listOf(p(2, 2f, "slow"), p(0, 0f, "fast"), p(0, 1f, "slow"), p(1, 30f, "fast"))
        val result = recordedSpeedTrail(points)
        assertEquals(7.2, result.scales.first { it.journeyId == "slow" }.maxKmh, .001)
        assertEquals(108.0, result.scales.first { it.journeyId == "fast" }.maxKmh, .001)
        assertEquals("slow-2", points.first().id)
    }

    @Test fun longConstantSpeedTrackCoalescesInsteadOfCreatingOneFeaturePerFix() {
        val result = recordedSpeedTrail((0 until 20_000).map { p(it) })
        assertEquals(1, result.lines.size)
        assertEquals(p(0).longitude, result.lines.single().coordinates.first().longitude, 1e-9)
        assertEquals(p(19_999).longitude, result.lines.single().coordinates.last().longitude, 1e-9)
    }
}
