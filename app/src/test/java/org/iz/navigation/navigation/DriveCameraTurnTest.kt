package org.iz.navigation.navigation

import kotlin.math.exp
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class DriveCameraTurnTest {
    private val point = WeatherCoordinate(41.0, 29.0)
    private val viewport = DriveCameraViewport(600, 800)

    private fun state(type: Int, meters: Double = 100.0, seconds: Double = 20.0): NavigationState {
        val end = WeatherCoordinate(41.001, 29.001)
        val route = PlannedRoute("camera-turn", listOf(RouteStop("A", point), RouteStop("B", end)),
            listOf(RouteVertex(point, 0.0), RouteVertex(end, 60.0)), 500.0, 60.0, 1L,
            maneuvers = listOf(RouteManeuver(type, "Turn / Düz devam", "Turn / Düz devam", emptyList(),
                0, 1, 20.0, 60.0)))
        return NavigationState(route = route, guidance = true, sessionTransport = Transport.CAR,
            progress = NavigationProgress(20.0 - seconds, 40.0 + seconds, 500.0, 0.0, 0, meters))
    }

    private fun input(state: NavigationState, now: Long = 0L): DriveCameraInput {
        val turn = state.driveCameraTurn()
        return DriveCameraInput(now, point.copy(longitude = 29.0 + now / 1e8), 100.0, 0.0,
            fresh = true, enabled = true, maneuverMeters = turn?.meters, maneuverSeconds = turn?.seconds)
    }

    @Test fun directionalTurnsRampsExitsAndRoundaboutsSupplyTypedProximity() {
        val types = mapOf("slight right" to 9, "right" to 10, "sharp right" to 11,
            "right U-turn" to 12, "left U-turn" to 13, "sharp left" to 14, "left" to 15,
            "slight left" to 16, "right ramp" to 18, "left ramp" to 19,
            "right exit" to 20, "left exit" to 21, "roundabout entry" to 26, "roundabout exit" to 27)
        for ((name, type) in types) {
            assertEquals(name, DriveCameraTurn(100.0, 20.0), state(type).driveCameraTurn())
        }
    }

    @Test fun nonTurnsAndUnknownTypesIgnoreEvenTurnWordingAndNearProximity() {
        val types = mapOf("unknown" to 0, "depart" to 1, "arrival" to 4, "arrival right" to 5,
            "arrival left" to 6, "rename" to 7, "continue" to 8, "straight ramp" to 17,
            "keep straight" to 22, "keep right" to 23, "keep left" to 24, "merge" to 25,
            "ferry" to 28, "merge right" to 37, "merge left" to 38,
            "invalid" to -1, "future unknown" to Int.MAX_VALUE)
        for ((name, type) in types) assertNull(name, state(type, 0.0, 0.0).driveCameraTurn())
    }

    @Test fun missingInactiveOrInvalidManeuverCannotInventAnUpcomingTurn() {
        val valid = state(10)
        val progress = checkNotNull(valid.progress)
        val variants = listOf(valid.copy(guidance = false), valid.copy(arrived = true),
            valid.copy(route = null), valid.copy(progress = null),
            valid.copy(progress = progress.copy(arrived = true)), valid.copy(progress = progress.copy(offRoute = true)),
            valid.copy(progress = progress.copy(maneuverIndex = -1)), valid.copy(progress = progress.copy(maneuverIndex = 1)))
        for ((index, variant) in variants.withIndex()) assertNull("variant=$index", variant.driveCameraTurn())
    }

    @Test fun distanceAndTimeRemainIndependentWhenOneHintIsInvalidOrPastTheTurn() {
        val valid = state(10, meters = 0.0, seconds = 8.0)
        val progress = checkNotNull(valid.progress)
        val pastTime = valid.copy(progress = progress.copy(elapsedSeconds = 21.0))
        assertEquals(DriveCameraTurn(0.0, null), pastTime.driveCameraTurn())
        assertEquals(17.5, DriveCameraPolicy().update(input(pastTime), viewport)!!.zoom, 1e-9)
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val validTime = valid.copy(progress = progress.copy(nextManeuverDistanceMeters = bad))
            assertEquals(DriveCameraTurn(null, 8.0), validTime.driveCameraTurn())
            assertEquals(17.5, DriveCameraPolicy().update(input(validTime), viewport)!!.zoom, 1e-9)
            assertEquals(DriveCameraTurn(0.0, null),
                valid.copy(progress = progress.copy(elapsedSeconds = bad)).driveCameraTurn())
            assertNull(valid.copy(progress = progress.copy(
                nextManeuverDistanceMeters = bad, elapsedSeconds = bad)).driveCameraTurn())
        }
        val route = checkNotNull(valid.route)
        assertEquals(DriveCameraTurn(0.0, null), valid.copy(route = route.copy(maneuvers = listOf(
            route.maneuvers.single().copy(beginElapsedSeconds = Double.NaN)))).driveCameraTurn())
        assertEquals(valid.driveCameraTurn(), valid.copy(progress = progress.copy(
            remainingSeconds = Double.NaN, remainingMeters = -1.0,
            distanceFromRouteMeters = Double.POSITIVE_INFINITY)).driveCameraTurn())
    }

    @Test fun onlyRealNearbyTurnsPullTheFilteredCameraAwayFromItsSpeedTarget() {
        val alpha = 1.0 - exp(-.5)
        for ((meters, seconds) in listOf(100.0 to 20.0, 150.0 to 8.0)) {
            for (type in listOf(8, 10)) {
                val policy = DriveCameraPolicy()
                policy.update(input(state(8, 300.0, 20.0)), viewport)
                val target = policy.update(input(state(type, meters, seconds), 1000L), viewport)!!
                val anchor = (target.padding[1] + viewport.heightPx - target.padding[3]) / (2.0 * viewport.heightPx)
                assertEquals("type=$type", if (type == 10) 15.5 + 2.0 * alpha else 15.5, target.zoom, 1e-9)
                assertEquals("type=$type", if (type == 10) .70 - .05 * alpha else .70, anchor, 1e-9)
            }
        }
        val farTurn = DriveCameraPolicy().update(input(state(10, 100.1, 8.1)), viewport)!!
        assertEquals(15.5, farTurn.zoom, 1e-9)
    }
}
