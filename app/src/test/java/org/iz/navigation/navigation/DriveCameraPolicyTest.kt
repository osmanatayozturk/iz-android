package org.iz.navigation.navigation

import kotlin.math.exp
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

class DriveCameraPolicyTest {
    private val point = WeatherCoordinate(41.0, 29.0)
    private val viewport = DriveCameraViewport(600, 800, 20, 40, 30, 120)
    private fun input(now: Long = 0, speed: Double? = 30.0, bearing: Double? = 0.0) =
        DriveCameraInput(now, point, speed, bearing, fresh = true, enabled = true)

    @Test fun speedKnotsAndIntermediateValuesUseApprovedScale() {
        for ((speed, zoom) in listOf(0.0 to 17.5, 15.0 to 17.25, 30.0 to 17.0,
            45.0 to 16.65, 60.0 to 16.3, 80.0 to 15.9, 100.0 to 15.5, 160.0 to 15.5)) {
            assertEquals("speed=$speed", zoom, DriveCameraPolicy().update(input(speed = speed), viewport)!!.zoom, 1e-9)
        }
    }

    @Test fun adaptiveTargetKeepsRealFixAndPlacesAnchorAtSixtyFivePercentOfSafeHeight() {
        val target = DriveCameraPolicy().update(input(bearing = 80.0), viewport)!!
        assertEquals(point, target.coordinate)
        assertEquals(45.0, target.pitch, 0.0)
        assertEquals(80.0, target.bearing, 0.0)
        assertEquals(listOf(20.0, 232.0, 30.0, 120.0), target.padding)
        val anchorY = (target.padding[1] + viewport.heightPx - target.padding[3]) / 2.0
        assertEquals(40.0 + .65 * 640.0, anchorY, 1e-9)
    }

    @Test fun unknownOrTinyViewportFallsBackFlatWithoutUnsafeInsets() {
        for (small in listOf(viewport.copy(known = false), DriveCameraViewport(100, 120))) {
            val target = DriveCameraPolicy().update(input(bearing = 70.0), small)!!
            assertEquals(0.0, target.pitch, 0.0)
            assertEquals(0.0, target.bearing, 0.0)
            assertTrue(target.padding.all { it.isFinite() && it >= 0.0 })
            assertTrue(target.padding[0] + target.padding[2] < small.widthPx)
            assertTrue(target.padding[1] + target.padding[3] < small.heightPx)
        }
    }

    @Test fun impossibleInsetsNeverInventAnExposedViewportOrRecenterBehindPanels() {
        val policy = DriveCameraPolicy()
        policy.update(input(), viewport)
        val concealedFix = input(1000).copy(coordinate = WeatherCoordinate(42.0, 30.0))
        for (occluded in listOf(viewport.copy(topPx = 680, bottomPx = 120),
            viewport.copy(topPx = 700, bottomPx = 120),
            viewport.copy(leftPx = 580, rightPx = 30), viewport.copy(leftPx = -1),
            viewport.copy(topPx = Int.MAX_VALUE, bottomPx = Int.MAX_VALUE),
            viewport.copy(known = false, topPx = 700, bottomPx = 120))) {
            assertNull(policy.update(concealedFix, occluded))
            assertNull("Explicit recenter must also honor occlusion", policy.update(concealedFix, occluded, force = true))
        }
        assertEquals("Suppressed input must not spend the cadence", concealedFix.coordinate,
            policy.update(concealedFix, viewport)!!.coordinate)
    }

    @Test fun disabledStaleAndMissingFixNeverMoveCamera() {
        val policy = DriveCameraPolicy()
        assertNull(policy.update(input().copy(enabled = false), viewport))
        assertNull(policy.update(input().copy(fresh = false), viewport))
        assertNull(policy.update(input().copy(coordinate = null), viewport))
        assertNotNull(policy.update(input(), viewport))
    }

    @Test fun cameraEmitsAtMostOneGpsCommandPerSecondAndDoesNotInventNewFixes() {
        val policy = DriveCameraPolicy()
        policy.update(input(), viewport)
        val nextPoint = WeatherCoordinate(41.0001, 29.0001)
        assertNull(policy.update(input(999).copy(coordinate = nextPoint), viewport))
        assertEquals(nextPoint, policy.update(input(1000).copy(coordinate = nextPoint), viewport)!!.coordinate)
        assertNull(policy.update(input(1500).copy(coordinate = point), viewport))
    }

    @Test fun unchangedInputsDoNotSpendCameraCadenceButForceRecenterAlwaysEmits() {
        val policy = DriveCameraPolicy()
        val first = policy.update(input(), viewport)!!
        assertNull(policy.update(input(1000), viewport))
        assertEquals(first, policy.update(input(1001), viewport, force = true))
        assertNull(policy.update(input(1100).copy(coordinate = WeatherCoordinate(41.1, 29.1)), viewport))
    }

    @Test fun zoomAndBearingUseTwoSecondSmoothingAndShortestAngle() {
        val policy = DriveCameraPolicy()
        policy.update(input(speed = 30.0, bearing = 350.0), viewport)
        val next = policy.update(input(1000, 100.0, 10.0), viewport)!!
        val alpha = 1.0 - exp(-.5)
        assertEquals(17.0 + (15.5 - 17.0) * alpha, next.zoom, 1e-9)
        assertEquals(350.0 + 20.0 * alpha, next.bearing, 1e-9)
        assertTrue(next.bearing > 350.0 || next.bearing < 10.0)
    }

    @Test fun stoppedOrMissingSpeedAndInvalidBearingHoldLastHeading() {
        val policy = DriveCameraPolicy()
        policy.update(input(bearing = 70.0), viewport)
        for ((index, speed) in listOf(0.0, 2.0, null, Double.NaN).withIndex()) {
            val next = policy.update(input((index + 1) * 1000L, speed, 210.0)
                .copy(coordinate = WeatherCoordinate(41.0 + (index + 1) * .0001, 29.0)), viewport)!!
            assertEquals(70.0, next.bearing, 1e-9)
        }
        val next = policy.update(input(5000, 30.0, Double.NaN)
            .copy(coordinate = WeatherCoordinate(41.01, 29.0)), viewport)!!
        assertEquals(70.0, next.bearing, 1e-9)
    }

    @Test fun initialStationaryFixDoesNotTrustChangingCompassBearing() {
        assertEquals(0.0, DriveCameraPolicy().update(input(speed = 0.0, bearing = 220.0), viewport)!!.bearing, 0.0)
    }

    @Test fun nearTurnDistanceOrTimeTakesZoomPriorityButInvalidValuesDoNot() {
        for (turn in listOf(input(speed = 100.0).copy(maneuverMeters = 100.0),
            input(speed = 100.0).copy(maneuverSeconds = 8.0))) {
            assertEquals(17.5, DriveCameraPolicy().update(turn, viewport)!!.zoom, 0.0)
        }
        for (turn in listOf(input(speed = 100.0).copy(maneuverMeters = 100.1, maneuverSeconds = 8.1),
            input(speed = 100.0).copy(maneuverMeters = -1.0, maneuverSeconds = Double.NaN))) {
            assertEquals(15.5, DriveCameraPolicy().update(turn, viewport)!!.zoom, 0.0)
        }
    }

    @Test fun simpleTwoDimensionalModeClearsPitchHeadingAndForwardAnchor() {
        val target = DriveCameraPolicy().update(input(bearing = 90.0).copy(mode = DriveCameraMode.SIMPLE_2D), viewport)!!
        assertEquals(0.0, target.pitch, 0.0)
        assertEquals(0.0, target.bearing, 0.0)
        assertEquals(listOf(20.0, 40.0, 30.0, 120.0), target.padding)
    }

    @Test fun resetClearsOldSmoothingAndCadenceAndClockRollbackDoesNotMove() {
        val policy = DriveCameraPolicy()
        policy.update(input(5000, 100.0, 90.0), viewport)
        assertNull(policy.update(input(4000), viewport))
        policy.reset()
        assertEquals(17.5, policy.update(input(0, 0.0), viewport)!!.zoom, 0.0)
    }
}
