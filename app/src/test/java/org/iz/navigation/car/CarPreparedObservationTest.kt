package org.iz.navigation.car

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.iz.navigation.navigation.PreparedRouteState
import org.iz.navigation.offline.*
import org.iz.navigation.weather.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CarPreparedObservationTest {
    private val autoLimit = "Auto haritası için internet gerekebilir; çevrimdışı kullanım doğrulanmadı."
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private class ConsumerLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
        fun moveTo(state: Lifecycle.State) { lifecycle.currentState = state }
    }
    private fun route() = PlannedRoute("prepared-route",
        listOf(RouteStop("Start", WeatherCoordinate(40.0, 29.0)), RouteStop("End", WeatherCoordinate(40.01, 29.01))),
        listOf(RouteVertex(WeatherCoordinate(40.0, 29.0), 0.0), RouteVertex(WeatherCoordinate(40.01, 29.01), 60.0)),
        1000.0, 60.0, 1L)
    private fun prepared(status: OfflinePackStatus = OfflinePackStatus.READY): PreparedRouteState {
        val route = route()
        val request = OfflineCorridorRequest(route.id, "fingerprint-A",
            listOf(OfflinePoint(40.0, 29.0), OfflinePoint(40.01, 29.01)), 200.0, 10, 12)
        return PreparedRouteState(route, "fingerprint-A", OfflinePack(request, status,
            tileCount = 10, downloadedBytes = 1000L, regionId = 1L))
    }

    @Test fun loadedGeometryAndVerifiedMatchingSelectedMapAreSeparateFacts() {
        val expected = CarPreparedPresentation("Telefonda rota çevrimdışı kullanım için hazırlanmadı.",
            "Telefonda harita alanı hazır değil. $autoLimit")
        assertEquals(expected, carPreparedPresentation(PreparedRouteState(), route(), true))
        val state = prepared()
        assertEquals("Telefonda rota hazır.", carPreparedPresentation(state, state.route, false).route)
        assertEquals("Telefonda harita alanı doğrulanamadı. $autoLimit", carPreparedPresentation(state, state.route, false).map)
        assertEquals("Telefonda seçili harita alanı hazır. $autoLimit",
            carPreparedPresentation(state, state.route, true).map)
        assertEquals(expected, carPreparedPresentation(state, route().copy(createdAt = 2L), true))
        val pack = checkNotNull(state.pack)
        val wrongPack = pack.copy(request = pack.request.copy(sourceFingerprint = "other"))
        assertEquals("Telefonda harita alanı güncel değil. $autoLimit", carPreparedPresentation(state.copy(pack = wrongPack), state.route, true).map)
        assertEquals(expected, carPreparedPresentation(state.copy(fingerprint = null), state.route, true))
    }

    @Test fun actualPartialPausedErrorAndDownloadStatesCannotBecomeReadyFromATrueFlag() {
        val labels = mapOf(
            OfflinePackStatus.PREPARED to "Telefonda harita alanı hazır değil.",
            OfflinePackStatus.DOWNLOADING to "Telefonda harita alanı indiriliyor.",
            OfflinePackStatus.PAUSED to "Telefonda indirme duraklatıldı.",
            OfflinePackStatus.PARTIAL to "Telefonda indirme yarım kaldı.",
            OfflinePackStatus.ERROR to "Telefonda harita alanı hazırlanamadı. Mevcut rota korundu.",
        )
        for ((status, label) in labels) {
            val state = prepared(status)
            val view = carPreparedPresentation(state, state.route, true)
            assertEquals("Telefonda rota hazır.", view.route)
            assertEquals("$label $autoLimit", view.map)
        }
        val state = prepared()
        assertEquals("Telefonda rota hazırlanıyor.", carPreparedPresentation(state.copy(busy = true), state.route, true).route)
        assertEquals("Telefonda harita alanı hazır değil. $autoLimit", carPreparedPresentation(state.copy(pack = null), state.route, true).map)
    }

    @Test fun aVerifiedPhonePackageNeverClaimsThatTheActualAutoRasterIsOfflineReady() {
        val state = prepared()
        val notice = carPreparedPresentation(state, state.route, true)
        assertTrue(notice.map.startsWith("Telefonda seçili harita alanı hazır."))
        assertTrue(notice.map.contains(autoLimit))
        assertTrue(notice.map.length <= 250)
        assertFalse(notice.map.contains("Auto haritası hazır"))
        val stale = state.copy(pack = state.pack!!.copy(request = state.pack!!.request.copy(routeId = "other-route")))
        assertTrue(carPreparedPresentation(stale, state.route, true).map.startsWith("Telefonda harita alanı güncel değil."))
    }

    @Test fun visibleConsumerReceivesTheActualPreparedUpdatesWithoutHomeOrGpsRefresh() = runTest {
        val owner = ConsumerLifecycle()
        val flow = MutableStateFlow(PreparedRouteState())
        val received = mutableListOf<CarPreparedPresentation>()
        observeCarPreparedNotice(backgroundScope, owner.lifecycle, flow, null, { false }, received::add)
        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        assertEquals(0, flow.subscriptionCount.value)
        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertEquals(listOf(carPreparedPresentation(flow.value, null, false)), received)
        assertEquals(1, flow.subscriptionCount.value)
        flow.value = prepared(OfflinePackStatus.DOWNLOADING)
        runCurrent()
        assertEquals("Telefonda harita alanı indiriliyor. $autoLimit", received.last().map)
        val beforeGenerationOnly = received.size
        flow.value = flow.value.copy(generation = 42L)
        runCurrent()
        assertEquals(beforeGenerationOnly, received.size)
        flow.value = flow.value.copy(pack = flow.value.pack!!.copy(status = OfflinePackStatus.PARTIAL))
        runCurrent()
        assertEquals("Telefonda indirme yarım kaldı. $autoLimit", received.last().map)
        owner.moveTo(Lifecycle.State.DESTROYED)
        runCurrent()
    }

    @Test fun stoppedConsumerUnsubscribesThenResumesOnlyTheCurrentPreparedFact() = runTest {
        val owner = ConsumerLifecycle()
        val flow = MutableStateFlow(prepared(OfflinePackStatus.DOWNLOADING))
        val received = mutableListOf<CarPreparedPresentation>()
        val job = observeCarPreparedNotice(backgroundScope, owner.lifecycle, flow, route(), { false }, received::add)
        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertEquals(1, received.size)
        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        assertEquals(0, flow.subscriptionCount.value)
        flow.value = prepared(OfflinePackStatus.PARTIAL)
        flow.value = prepared(OfflinePackStatus.ERROR)
        runCurrent()
        assertEquals(1, received.size)
        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertEquals(2, received.size)
        assertEquals("Telefonda harita alanı hazırlanamadı. Mevcut rota korundu. $autoLimit", received.last().map)
        owner.moveTo(Lifecycle.State.DESTROYED)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(0, flow.subscriptionCount.value)
        flow.value = prepared()
        runCurrent()
        assertEquals(2, received.size)
    }

    @Test fun firstPreparationWithoutASavedOwnerEmitsBusyStateToTheVisibleConsumer() = runTest {
        val owner = ConsumerLifecycle()
        val flow = MutableStateFlow(PreparedRouteState())
        val received = mutableListOf<CarPreparedPresentation>()
        observeCarPreparedNotice(backgroundScope, owner.lifecycle, flow, null, { false }, received::add)
        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertEquals(1, received.size)
        // PreparedRouteManager first save sets busy before its IO publishes any route/fingerprint.
        flow.value = flow.value.copy(busy = true, generation = 1L)
        runCurrent()
        assertEquals("The actual first preparation must redraw even without a saved owner", 2, received.size)
        assertEquals("Telefonda rota hazırlanıyor.", received.last().route)
        assertNull(flow.value.route)
        assertNull(flow.value.fingerprint)
        assertTrue(received.last().map.contains(autoLimit))
        val retainedOwner = prepared().copy(busy = true)
        val retained = carPreparedPresentation(retainedOwner, retainedOwner.route, true)
        assertEquals("Telefonda rota hazırlanıyor.", retained.route)
        assertEquals("Telefonda harita alanı hazır değil. $autoLimit", retained.map)
        owner.moveTo(Lifecycle.State.DESTROYED)
        runCurrent()
    }
}
