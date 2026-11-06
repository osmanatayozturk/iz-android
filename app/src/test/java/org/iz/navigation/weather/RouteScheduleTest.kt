package org.iz.navigation.weather

import org.junit.Assert.*
import org.junit.Test

class RouteScheduleTest {
    private fun route(first: Int = 1200, second: Int = 0): PlannedRoute {
        val points = (0..3).map { WeatherCoordinate(0.0, it * .01) }
        return PlannedRoute("schedule", points.mapIndexed { i,p -> RouteStop("Stop", p, when(i){1->first;2->second;else->0}) },
            points.mapIndexed { i,p -> RouteVertex(p, i * 1200.0) }, 3000.0, 3600.0, 0,
            stopElapsedSeconds = listOf(0.0,1200.0,2400.0,3600.0))
    }
    @Test fun nineOClockHourDriveWithTwentyMinuteBreakArrivesTenTwenty() {
        val r=route(); val departure=9*3_600_000L; val timing=RouteSchedule.planned(r,departure)
        assertEquals(3600.0,timing.drivingSeconds,0.0); assertEquals(1200.0,timing.breakSeconds,0.0)
        assertEquals(4800.0,timing.totalSeconds,0.0); assertEquals(departure+4_800_000,timing.arrivalAt)
        val stops=RouteSchedule.stops(r,departure)
        assertEquals(departure+1_200_000,stops[1].arrivalAt)
        assertEquals(departure+2_400_000,stops[1].departureAt)
        assertEquals(departure+3_600_000,stops[2].arrivalAt)
        assertEquals(3600.0,r.durationSeconds,0.0); assertEquals(1200.0,r.vertices[1].elapsedSeconds,0.0)
    }
    @Test fun weatherAtStopUsesArrivalAndDownstreamUsesCompletedWaiting() {
        val r=route(1200,600); val context=RouteScheduleContext(1_000)
        assertEquals(1_201_000L,RouteSchedule.arrivalAt(r,context,1200.0))
        assertEquals(2_401_001L,RouteSchedule.arrivalAt(r,context,1200.001))
        assertEquals(RouteSchedule.planned(r,1000).arrivalAt,RouteSchedule.arrivalAt(r,context,3600.0))
    }
    @Test fun explicitRemainingAllocationSupportsEarlyDepartureAndOverrunWithoutDoubleCounting() {
        val r=route(1200,600)
        val during=RouteSchedule.remaining(r,RouteScheduleContext(2_000_000,1200.0,mapOf(1 to 900.0,2 to 600.0)))
        val early=RouteSchedule.remaining(r,RouteScheduleContext(2_000_000,1200.0,mapOf(2 to 600.0)))
        assertEquals(900_000L,during.arrivalAt-early.arrivalAt)
        val overrun=RouteSchedule.remaining(r,RouteScheduleContext(2_060_000,1200.0,mapOf(1 to 0.0,2 to 600.0)))
        assertEquals(60_000L,overrun.arrivalAt-early.arrivalAt)
    }
    @Test fun zeroBreakLegacyRouteDoesNotNeedIntermediateBoundaryTimesForTotals() {
        val r=route(0).copy(stopElapsedSeconds=emptyList())
        assertEquals(3600.0,RouteSchedule.planned(r,1000).totalSeconds,0.0)
        assertThrows(IllegalArgumentException::class.java){RouteSchedule.stops(r,1000)}
        assertThrows(IllegalArgumentException::class.java){RouteSchedule.planned(route().copy(stopElapsedSeconds=emptyList()),1000)}
    }
    @Test fun duplicateLabelsReorderWithTheirDurationsAndSkipRemovesOnlyThatOccurrence() {
        val r=route(1200,600); val swapped=r.copy(stops=listOf(r.stops[0],r.stops[2],r.stops[1],r.stops[3]))
        assertEquals(600,RouteSchedule.stops(swapped,0)[1].plannedBreakSeconds)
        assertEquals(1200,RouteSchedule.stops(swapped,0)[2].plannedBreakSeconds)
        assertEquals(600.0,RouteSchedule.remaining(r,RouteScheduleContext(0,0.0,mapOf(2 to 600.0))).breakSeconds,0.0)
    }
    @Test fun fractionalDrivingTimeUsesOneSharedRoundingAtFinalArrival() {
        val p=WeatherCoordinate(0.0,0.0)
        val r=PlannedRoute("fraction",listOf(RouteStop("A",p),RouteStop("B",p)),listOf(RouteVertex(p,0.0),RouteVertex(p,1.0001)),0.0,1.0001,0)
        assertEquals(1001L,RouteSchedule.planned(r,0).arrivalAt)
        assertEquals(1001L,RouteSchedule.arrivalAt(r,RouteScheduleContext(0),1.0001))
    }
}
