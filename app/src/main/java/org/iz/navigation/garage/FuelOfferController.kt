package org.iz.navigation.garage

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.iz.navigation.IzApplication
import org.iz.navigation.data.*
import org.iz.navigation.navigation.*
import org.iz.navigation.weather.WeatherEngine
import java.util.UUID

data class FuelOffer(val id: String, val title: String, val expiresAtElapsedMs: Long,
    val distanceToStationMeters: Double, val additionalSeconds: Double?)

@OptIn(ExperimentalCoroutinesApi::class)
class FuelOfferController internal constructor(
    app: IzApplication,
    private val navigation: JourneyNavigationCoordinator = app.navigation,
    private val garage: GarageRepository = app.garage,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val search: suspend () -> FuelStationPlan? = { navigation.findFuelStation() },
    private val applyPlan: suspend (FuelStationPlan, () -> Boolean) -> Boolean = { plan, valid ->
        navigation.acceptFuelStation(plan, valid)
    },
    private val announce: (NavigationCueRequest) -> Unit = { NavigationSpeech.get(app).speakWhenIdle(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val externalSearch: suspend (Transport, NavigationFix, () -> Boolean) -> FuelStationPlan? =
        { mode, fix, valid -> navigation.findFuelStation(mode, fix, valid) },
    private val externalApply: suspend (FuelStationPlan, NavigationFix, () -> Boolean) -> Boolean =
        { plan, fix, valid -> navigation.acceptFuelStation(plan, fix, valid) },
) {
    private val phoneOwners = mutableSetOf<Any>()
    private val autoOwners = linkedMapOf<Any, Any>()
    private val activeMapOwner get() = autoOwners.values.lastOrNull()
    private val autoMapOwners = mutableSetOf<Any>()
    private val presentable = MutableStateFlow(false)
    private val audience = MutableStateFlow(false)
    private val car = MutableStateFlow(false)
    val autoVisible = car.asStateFlow()
    private var displayEpoch = 0L
    private val mutableOffer = MutableStateFlow<FuelOffer?>(null)
    val offer = mutableOffer.asStateFlow()

    // Display-only identity lives for this projection. It never starts a diary or navigation session.
    private data class Projection(val id: String, val mode: Transport? = null, val fix: NavigationFix? = null,
        val selectedVehicle: String? = null)
    private val projection = MutableStateFlow<Projection?>(null)
    private data class Drive(val id: String, val journey: String?, val mode: Transport, val external: Boolean)
    private data class Selection(val drive: Drive, val vehicle: GarageVehicle)
    private data class Owner(val selection: Selection, val revision: Long)
    private data class Pending(val owner: Owner, val plan: FuelStationPlan, val offer: FuelOffer)
    private val attempted = LinkedHashSet<String>()
    private var pending: Pending? = null
    private var accepting: String? = null
    private val eligibleOwner = MutableStateFlow<Owner?>(null)
    private data class DistanceAnchor(val drive: Drive, val vehicleId: String, val since: Long)
    private data class LiveDistance(val anchor: DistanceAnchor, val km: Double)
    private val liveDistance = MutableStateFlow<LiveDistance?>(null)

    private fun extraDistance(choice: Selection, stats: FuelStats, distance: LiveDistance?): Double =
        distance?.takeIf { it.anchor.drive == choice.drive && it.anchor.vehicleId == choice.vehicle.id &&
            it.anchor.since == stats.distanceBaselineAt }?.km ?: 0.0

    private fun drive(state: NavigationState, display: Projection?): Drive? {
        if (state.simulation) return null
        if (state.sessionId != null) return state.sessionTransport?.let {
            Drive(state.sessionId, state.journey?.id, it, false)
        }
        if (state.recording || state.journey != null || state.route != null || state.trackFollow != null) return null
        return display?.mode?.let { Drive(display.id, null, it, true) }
    }

    private fun selection(drive: Drive): Flow<Selection?> {
        val selected = when {
            drive.external -> projection.map { it?.takeIf { p -> p.id == drive.id }?.selectedVehicle }
            drive.journey != null -> garage.assignedVehicleId(drive.journey)
            else -> garage.unrecordedBinding.map { it?.takeIf { b -> b.sessionId == drive.id }?.vehicleId }
        }
        return combine(garage.vehicles, selected.distinctUntilChanged()) { fleet, id ->
            val options = fleet.filter { it.transport == drive.mode }
            val vehicle = if (drive.external && id == null) options.singleOrNull() else options.find { it.id == id }
            vehicle?.let { Selection(drive, it) }
        }.distinctUntilChanged()
    }

    private fun usableFix(d: Drive, state: NavigationState, display: Projection?): NavigationFix? {
        if (d != drive(state, display) || state.paused || state.arrived || state.loading || state.recordingPending ||
            state.journey?.status == JourneyStatus.TEMPORARY || state.trackFollow != null ||
            state.guidance != (state.route != null) || (state.route != null && state.progress?.offRoute != false)) return null
        val fix = if (d.external) display?.fix else state.fix.takeUnless { state.gpsStale }
        return fix?.takeIf { JourneyNavigationCoordinator.fresh(it, wallClock()) }
    }

    init {
        val selected = combine(navigation.state, projection, ::drive).distinctUntilChanged()
            .flatMapLatest { d -> if (d == null) flowOf(null) else selection(d).onStart { emit(null) } }
            .stateIn(scope, SharingStarted.Eagerly, null)

        val history = selected.flatMapLatest { choice ->
            if (choice == null) flowOf(null)
            else garage.offerStats(choice.vehicle).map { stats -> stats?.let { choice to it } }.onStart { emit(null) }
        }.stateIn(scope, SharingStarted.Eagerly, null)
        scope.launch {
            var owner: DistanceAnchor? = null
            var previous: TrackPoint? = null
            combine(navigation.state, projection, history) { state, display, data ->
                val key = data?.takeIf { !state.recording && it.first.drive == drive(state, display) }
                    ?.let { (choice, stats) -> DistanceAnchor(choice.drive, choice.vehicle.id, stats.distanceBaselineAt) }
                key to key?.let { usableFix(it.drive, state, display) }
            }.collect { (key, fix) ->
                if (key != owner) {
                    owner = key
                    previous = null
                    liveDistance.value = null
                }
                if (key == null || fix == null || fix.recordedAt < key.since) { previous = null; return@collect }
                val p = TrackPoint(journeyId = key.drive.id, latitude = fix.coordinate.latitude,
                    longitude = fix.coordinate.longitude, accuracy = fix.accuracyMeters, recordedAt = fix.recordedAt)
                val a = previous
                if (a != null && p.recordedAt <= a.recordedAt) return@collect
                previous = p
                if (a != null) liveDistance.value = LiveDistance(key,
                    (liveDistance.value?.km ?: 0.0) + DiaryRules.distanceContributionMeters(a, p) / 1000)
            }
        }
        scope.launch {
            combine(history, navigation.state, projection, liveDistance, audience) { data, state, display, km, visible ->
                if (data == null || !visible) return@combine null
                val (choice, stats) = data
                if (choice != selected.value || usableFix(choice.drive, state, display) == null ||
                    !stats.needsFuel(if (state.recording) 0.0 else extraDistance(choice, stats, km))) return@combine null
                Owner(choice, state.routeRevision)
            }.distinctUntilChanged().collectLatest { owner ->
                eligibleOwner.value = owner
                clear()
                if (owner == null || owner.selection.drive.id in attempted) return@collectLatest
                presentable.first { it }
                val d = owner.selection.drive
                fun owned() = eligibleOwner.value == owner && navigation.state.value.routeRevision == owner.revision &&
                    selected.value == owner.selection && usableFix(d, navigation.state.value, projection.value) != null
                val fix = usableFix(d, navigation.state.value, projection.value) ?: return@collectLatest
                markAttempt(d.id)
                val plan = try { if (d.external) externalSearch(d.mode, fix, ::owned) else search() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null } ?: return@collectLatest
                presentable.first { it }
                if (!current(owner, plan)) return@collectLatest
                val item = FuelOffer(UUID.randomUUID().toString(), plan.title, elapsed() + 20_000,
                    plan.distanceToStationMeters, plan.additionalSeconds)
                pending = Pending(owner, plan, item)
                mutableOffer.value = item
                if (!navigation.state.value.muted) announce(NavigationCueRequest("Yakında bir yakıt istasyonu var.",
                    item.expiresAtElapsedMs, stillCurrent = { pending?.offer?.id == item.id &&
                        presentable.value && !navigation.state.value.muted }, onFinished = {}))
                delay((item.expiresAtElapsedMs - elapsed()).coerceAtLeast(0))
                if (pending?.offer?.id == item.id) clear()
            }
        }
        scope.launch {
            combine(navigation.state, projection, selected) { _, _, choice -> choice }.collect { choice ->
                val p = pending ?: return@collect
                if (choice != p.owner.selection || !current(p.owner, p.plan)) clear()
            }
        }
    }

    private fun markAttempt(id: String) {
        attempted.add(id)
        if (attempted.size > 8) attempted.remove(attempted.first { it != projection.value?.id })
    }

    private fun current(owner: Owner, plan: FuelStationPlan): Boolean {
        val state = navigation.state.value
        val display = projection.value
        if (owner.selection.drive.external && display?.selectedVehicle?.let { it != owner.selection.vehicle.id } == true)
            return false
        val fix = usableFix(owner.selection.drive, state, display) ?: return false
        return eligibleOwner.value == owner && state.routeRevision == owner.revision && audience.value &&
            fix.recordedAt >= plan.owner.fix.recordedAt &&
            WeatherEngine.distanceMeters(fix.coordinate, plan.owner.fix.coordinate) <= FuelStationRouting.MAX_ORIGIN_DRIFT_METERS
    }

    fun setAutoVisible(owner: Any, visible: Boolean, mapOwner: Any = owner) {
        val previous = activeMapOwner
        if (visible) autoOwners[owner] = mapOwner else autoOwners.remove(owner)?.let(autoMapOwners::remove)
        if (previous !== activeMapOwner) {
            displayEpoch++
            car.value = autoOwners.isNotEmpty()
            projection.value = if (car.value) Projection(UUID.randomUUID().toString()) else null
        }
        updateDisplay()
    }

    fun isAutoMapActive(owner: Any): Boolean = owner === activeMapOwner && owner in autoMapOwners

    fun setAutoMapVisible(owner: Any, visible: Boolean) {
        if (visible) autoMapOwners.add(owner) else autoMapOwners.remove(owner)
        updateDisplay()
    }

    fun setAutoMapFix(owner: Any, transport: Transport, fix: NavigationFix?) {
        val display = projection.value ?: return
        if (owner !== activeMapOwner || (fix != null && owner !in autoMapOwners)) return
        projection.value = display.copy(mode = transport, fix = fix,
            selectedVehicle = display.selectedVehicle.takeIf { display.mode == transport })
    }

    suspend fun selectAutoVehicle(vehicleId: String, transport: Transport) {
        val display = requireNotNull(projection.value)
        val vehicle = garage.vehicles.first().find { it.id == vehicleId && it.transport == transport }
        require(vehicle != null && projection.value?.id == display.id && (projection.value?.mode == null || projection.value?.mode == transport) &&
            navigation.state.value.sessionId == null && navigation.state.value.journey == null) {
            "Araç veya sürüş değişti."
        }
        projection.value = requireNotNull(projection.value).copy(mode = transport, selectedVehicle = vehicleId)
    }

    fun setPhoneVisible(owner: Any, visible: Boolean) {
        if (visible) phoneOwners.add(owner) else phoneOwners.remove(owner)
        updateDisplay()
    }

    private fun updateDisplay() {
        val ready = if (car.value) activeMapOwner?.let(autoMapOwners::contains) == true else phoneOwners.isNotEmpty()
        if (presentable.value != ready) displayEpoch++
        presentable.value = ready
        audience.value = car.value || phoneOwners.isNotEmpty()
    }

    suspend fun accept(id: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val p = pending ?: return@withContext false
        val epoch = displayEpoch
        fun valid() = pending === p && p.offer.id == id && epoch == displayEpoch && presentable.value &&
            elapsed() < p.offer.expiresAtElapsedMs && current(p.owner, p.plan)
        if (!valid() || accepting != null) return@withContext false
        accepting = id
        val choice = p.owner.selection
        val d = choice.drive
        val extraKm = { stats: FuelStats ->
            if (navigation.state.value.recording) 0.0 else extraDistance(choice, stats, liveDistance.value)
        }
        try {
            val selectedVehicle = projection.value?.selectedVehicle
            if (d.external) garage.applyForProjection(choice.vehicle, selectedVehicle, extraKm) {
                val fix = usableFix(d, navigation.state.value, projection.value)
                if (fix == null || !valid()) false else externalApply(p.plan, fix, ::valid).also { applied ->
                    if (applied) navigation.state.value.takeIf { !it.recording && it.journey == null }?.sessionId?.let { session ->
                        markAttempt(session)
                        garage.rememberUnrecorded(session, choice.vehicle.id,
                            explicitChoice = selectedVehicle == choice.vehicle.id)
                    }
                }
            } else garage.applyForOwner(d.id, d.journey, choice.vehicle.id, extraKm) {
                valid() && applyPlan(p.plan, ::valid)
            }
        } finally {
            if (pending === p && epoch == displayEpoch) clear()
            accepting = null
        }
    }

    fun reject(id: String) {
        if (pending?.offer?.id == id) clear()
    }

    private fun clear() {
        pending = null
        mutableOffer.value = null
    }
}
