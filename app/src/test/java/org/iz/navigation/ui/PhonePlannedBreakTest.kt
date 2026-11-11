package org.iz.navigation.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.SavedWeatherPlan
import org.iz.navigation.weather.StopOrderProposal
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePlannedBreakTest {
    private val origin = RouteStop("Başlangıç", WeatherCoordinate(41.0, 29.0))
    private val via = RouteStop("Ara durak", WeatherCoordinate(41.01, 29.0))
    private val destination = RouteStop("Varış", WeatherCoordinate(41.02, 29.0))

    @Test fun invalidAndUnchangedEditsKeepThePreviewAndClearingKeepsTheStop() = runBlocking {
        val stops = listOf(origin, via.copy(plannedBreakSeconds = 1_200), destination)
        val phone = PhoneDirectionsCoordinator(this, locate = { origin.coordinate }, plan = { points, _ -> route(points) },
            activate = { error("Editing a break must never start guidance") })
        phone.enter(1L, stops, Transport.CAR, locateOnOpen = false)
        phone.preview()!!.join()
        val preview = checkNotNull(phone.state.value.preview)
        val revision = phone.revisionToken
        phone.setPlannedBreakMinutes(-1, 5)
        phone.setPlannedBreakMinutes(1, 5)
        phone.setPlannedBreakMinutes(0, -1)
        phone.setPlannedBreakMinutes(0, 241)
        phone.setPlannedBreakMinutes(0, 20)
        assertSame(preview, phone.state.value.preview)
        assertEquals(revision, phone.revisionToken)
        phone.setPlannedBreakMinutes(0, 240)
        assertEquals(14_400, phone.state.value.via.single().plannedBreakSeconds)
        assertNull(phone.state.value.preview)
        phone.setPlannedBreakMinutes(0, 0)
        assertEquals(listOf(origin, via, destination), phone.state.value.stops)
    }

    @Test fun editingABreakRetiresAnInFlightPreview() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val phone = PhoneDirectionsCoordinator(this, locate = { origin.coordinate }, plan = { points, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            route(points)
        }, activate = {})
        phone.enter(1L, listOf(origin, via, destination), Transport.CAR, locateOnOpen = false)
        val stale = checkNotNull(phone.preview())
        try {
            entered.await()
            phone.setPlannedBreakMinutes(0, 20)
        } finally {
            release.complete(Unit)
        }
        stale.join()
        assertNull(phone.state.value.preview)
        assertEquals(1_200, phone.state.value.via.single().plannedBreakSeconds)
    }

    @Test fun aPreviewThatLosesBreakMetadataCannotBeStarted() = runBlocking {
        val stops = listOf(origin, via.copy(plannedBreakSeconds = 1_200), destination)
        val phone = PhoneDirectionsCoordinator(this, locate = { origin.coordinate },
            plan = { points, _ -> route(points.map { it.copy(plannedBreakSeconds = 0) }) },
            activate = { error("A route without the requested break must not start") })
        phone.enter(1L, stops, Transport.CAR, locateOnOpen = false)
        phone.preview()!!.join()
        assertNull(phone.state.value.preview)
        assertNull(phone.start())
        assertEquals(stops, phone.state.value.stops)
        assertTrue(phone.state.value.message.orEmpty().contains("mola", ignoreCase = true))
    }

    @Test fun savedDuplicateStopsCarrySeparateBreaksThroughReorderAndExplicitStart() = runBlocking {
        val first = via.copy(plannedBreakSeconds = 300)
        val second = via.copy(plannedBreakSeconds = 1_200)
        val saved = SavedWeatherPlan(listOf(origin, first, second, destination), 1_000L, Transport.CAR)
        val ordered = listOf(origin, second, first, destination)
        val approach = WeatherCoordinate(40.99, 29.0)
        var started: PlannedRoute? = null
        val phone = PhoneDirectionsCoordinator(this, locate = { approach }, plan = { points, _ -> route(points) },
            activate = { started = it }, clock = { 1_000L },
            suggestOrder = { input, _, _ ->
                StopOrderProposal(input, ordered, route(input, legSeconds = 90.0), route(ordered), 121_000L, false, 1_000L, "")
            })
        phone.enter(1L, initialPlan = saved, locateOnOpen = false)
        assertEquals(saved.stops, phone.state.value.stops)
        assertNull(started)
        phone.suggestStopOrder()!!.join()
        phone.acceptStopOrder()
        assertEquals(ordered, phone.state.value.stops)
        phone.preview()!!.join()
        assertEquals(ordered, phone.state.value.preview?.stops)
        assertNull(started)
        phone.start()!!.join()
        val active = checkNotNull(started)
        assertEquals(approach, active.stops.first().coordinate)
        assertEquals(ordered, active.stops.drop(1))
        assertEquals(listOf(0, 0, 1_200, 300, 0), active.stops.map { it.plannedBreakSeconds })
        assertEquals(240.0, active.durationSeconds, 0.0)
    }

    @Test fun equalDurationProposalCannotReorderDuplicateStopsOrTheirBreaks() = runBlocking {
        val stops = listOf(origin, via.copy(plannedBreakSeconds = 300), via.copy(plannedBreakSeconds = 1_200), destination)
        val ordered = listOf(stops.first(), stops[2], stops[1], stops.last())
        val phone = PhoneDirectionsCoordinator(this, locate = { origin.coordinate }, plan = { points, _ -> route(points) },
            activate = { error("Rejected proposal must never start") }, clock = { 1_000L },
            suggestOrder = { input, _, _ -> StopOrderProposal(input, ordered, route(input), route(ordered),
                121_000L, false, 1_000L, "") })
        phone.enter(1L, stops, Transport.CAR, locateOnOpen = false)
        phone.suggestStopOrder()!!.join()
        phone.acceptStopOrder()
        assertEquals(stops, phone.state.value.stops)
        assertNull(phone.state.value.orderProposal)
        assertTrue(phone.state.value.message.orEmpty().contains("güncel değil"))
    }

    private fun route(stops: List<RouteStop>, legSeconds: Double = 60.0): PlannedRoute {
        val elapsed = stops.indices.map { it * legSeconds }
        return PlannedRoute("synthetic-phone", stops, stops.zip(elapsed, { stop, at -> RouteVertex(stop.coordinate, at) }),
            2_000.0, elapsed.last(), 1_000L, stopElapsedSeconds = elapsed, transport = Transport.CAR)
    }
}
