package org.iz.navigation.ui

import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.prepareRouteStartStops
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.StopOrderProposal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class PhoneDirectionsState(
    val transport: Transport = Transport.CAR,
    val origin: RouteStop? = null,
    val originCurrent: Boolean = true,
    val destination: RouteStop? = null,
    val via: List<RouteStop> = emptyList(),
    val preview: PlannedRoute? = null,
    val locating: Boolean = false,
    val previewing: Boolean = false,
    val starting: Boolean = false,
    val message: String? = null,
    val startedCount: Long = 0,
    val recordJourney: Boolean = true,
    val ordering: Boolean = false,
    val orderProposal: StopOrderProposal? = null,
) {
    val busy get() = previewing || starting || ordering
    val stops get() = listOfNotNull(origin) + via + listOfNotNull(destination)
    val canPreview get() = destination != null && (origin != null || originCurrent) && !busy
}

/** Planning owns no recorder. Only the explicit start command calls activate. */
internal class PhoneDirectionsCoordinator(
    private val scope: CoroutineScope,
    private val locate: suspend () -> WeatherCoordinate,
    private val plan: suspend (List<RouteStop>, Transport) -> PlannedRoute,
    private val activate: suspend (PlannedRoute) -> Unit,
    private val activateWithRecording: suspend (PlannedRoute, Boolean) -> Unit = { route, _ -> activate(route) },
    private val suggestOrder: suspend (List<RouteStop>, Long, Transport) -> StopOrderProposal? = { _, _, _ -> null },
    private val credentialRevision: () -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(PhoneDirectionsState())
    val state = mutableState.asStateFlow()
    private var entry: Long? = null
    private var revision = 0L
    private var originRevision = 0L
    private var locationJob: Job? = null
    private var previewJob: Job? = null
    private var starting = false
    val revisionToken: Long get() = revision

    fun enter(entryId: Long, initialStops: List<RouteStop>? = null, initialTransport: Transport? = null,
        locateOnOpen: Boolean = true) {
        if (entry == entryId) return
        entry = entryId
        invalidatePreview()
        originRevision++
        locationJob?.cancel()
        val provided = initialStops?.takeIf { it.size in 2..5 }
        mutableState.value = PhoneDirectionsState(
            transport = initialTransport?.takeIf { it != Transport.UNKNOWN } ?: state.value.transport,
            origin = provided?.first(), originCurrent = provided == null || provided.first().label in setOf("Mevcut konum", "Konumum"),
            destination = provided?.last(), via = provided?.drop(1)?.dropLast(1).orEmpty(),
            starting = starting, startedCount = state.value.startedCount,
        )
        if (provided == null && !starting && locateOnOpen) useCurrentOrigin()
    }

    fun setOrigin(stop: RouteStop) {
        if (starting) return
        originRevision++
        locationJob?.cancel()
        invalidatePreview()
        mutableState.update { it.copy(origin = stop, originCurrent = false, locating = false, message = null) }
    }

    fun setDestination(stop: RouteStop) {
        if (starting) return
        invalidatePreview()
        mutableState.update { it.copy(destination = stop, message = null) }
    }

    fun transport(transport: Transport) {
        if (starting || transport == Transport.UNKNOWN || state.value.transport == transport) return
        invalidatePreview()
        mutableState.update { it.copy(transport = transport, message = null) }
    }

    fun useCurrentOrigin(): Job? {
        if (starting) return null
        invalidatePreview()
        locationJob?.cancel()
        val token = ++originRevision
        mutableState.update { it.copy(origin = null, originCurrent = true, locating = true, message = null) }
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                val coordinate = locate()
                if (token == originRevision && state.value.originCurrent) {
                    mutableState.update { it.copy(origin = currentStop(coordinate)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (token == originRevision) reportError(error) }
            finally { if (token == originRevision) mutableState.update { it.copy(locating = false) } }
        }.also { locationJob = it; it.start() }
    }

    fun preview(): Job? {
        val before = state.value
        if (!before.canPreview || starting) return null
        originRevision++
        locationJob?.cancel()
        val token = ++revision
        mutableState.update { it.copy(previewing = true, locating = false, message = null) }
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                val origin = if (before.originCurrent) currentStop(locate()) else requireNotNull(before.origin)
                if (token != revision) return@launch
                val stops = listOf(origin) + before.via + requireNotNull(before.destination)
                val route = plan(stops, before.transport)
                if (token != revision) return@launch
                require(route.transport == before.transport) { "Rota seçilen yolculuk türüyle eşleşmiyor." }
                mutableState.update { it.copy(origin = origin, preview = route) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (token == revision) reportError(error) }
            finally { if (token == revision) mutableState.update { it.copy(previewing = false) } }
        }.also { previewJob = it; it.start() }
    }

    fun start(): Job? {
        val before = state.value
        if (starting || before.busy || before.preview == null) return null
        starting = true
        originRevision++
        locationJob?.cancel()
        val token = revision
        mutableState.update { it.copy(starting = true, locating = false, message = null) }
        return scope.launch {
            try {
                val coordinate = locate()
                val plannedStops = if (before.originCurrent)
                    listOf(currentStop(coordinate)) + before.via + requireNotNull(before.destination)
                else before.stops
                val route = plan(prepareRouteStartStops(plannedStops, coordinate), before.transport)
                require(route.transport == before.transport) { "Rota seçilen yolculuk türüyle eşleşmiyor." }
                activateWithRecording(route, before.recordJourney)
                mutableState.update { latest -> latest.copy(
                    preview = null, startedCount = latest.startedCount + 1,
                    origin = if (token == revision && before.originCurrent) currentStop(coordinate) else latest.origin,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportError(error) }
            finally { starting = false; mutableState.update { it.copy(starting = false) } }
        }
    }

    fun setRecordJourney(enabled: Boolean) {
        if (starting) return
        mutableState.update { it.copy(recordJourney = enabled) }
    }

    fun suggestStopOrder(): Job? {
        val before = state.value
        if (before.busy || before.stops.size !in 4..5 || before.origin == null || before.destination == null ||
            before.transport !in setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE)) return null
        val token = ++revision
        mutableState.update { it.copy(ordering = true, orderProposal = null, message = null) }
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                val proposal = suggestOrder(before.stops, clock(), before.transport)
                if (token != revision) return@launch
                mutableState.update { it.copy(orderProposal = proposal,
                    message = if (proposal == null) "Daha hızlı bir durak sırası bulunamadı." else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (token == revision) reportError(error) }
            finally { if (token == revision) mutableState.update { it.copy(ordering = false) } }
        }.also { previewJob = it; it.start() }
    }

    fun acceptStopOrder() {
        val before = state.value
        val proposal = before.orderProposal ?: return
        if (before.busy) return
        if (proposal.originalStops != before.stops || proposal.credentialRevision != credentialRevision() ||
            clock() - proposal.createdAt !in 0L..120_000L) {
            mutableState.update { it.copy(orderProposal = null, message = "Öneri güncel değil. Yeniden hesapla.") }
            return
        }
        invalidatePreview()
        mutableState.update { it.copy(via = proposal.orderedStops.drop(1).dropLast(1),
            message = "Durak sırası uygulandı. Rotayı önizleyebilirsin.") }
    }

    fun dismissStopOrder() { mutableState.update { it.copy(orderProposal = null) } }

    fun addVia(stop: RouteStop) {
        if (starting || state.value.via.size >= 3) return
        invalidatePreview()
        mutableState.update { it.copy(via = it.via + stop, message = null) }
    }

    fun removeVia(index: Int) {
        if (starting || index !in state.value.via.indices) return
        invalidatePreview()
        mutableState.update { it.copy(via = it.via.filterIndexed { i, _ -> i != index }, message = null) }
    }
    fun showLiveRoute() { invalidatePreview() }
    fun sessionStarted() { invalidatePreview(); mutableState.update { it.copy(startedCount = it.startedCount + 1) } }
    fun reportMessage(message: String?) { mutableState.update { it.copy(message = message) } }

    private fun invalidatePreview() {
        revision++
        previewJob?.cancel()
        previewJob = null
        mutableState.update { it.copy(preview = null, previewing = false, ordering = false, orderProposal = null) }
    }
    private fun reportError(error: Exception) = reportMessage(error.message ?: "İşlem tamamlanamadı.")
    private fun currentStop(coordinate: WeatherCoordinate) = RouteStop("Mevcut konum", coordinate)
}

internal fun finishNavigationWork(directions: PhoneDirectionsCoordinator, work: PhoneNavigationWork,
    finish: suspend () -> Unit) {
    if (directions.state.value.starting) return
    directions.showLiveRoute()
    work.mutate(finish)
}

