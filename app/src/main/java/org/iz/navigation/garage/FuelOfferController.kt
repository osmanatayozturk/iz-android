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

/** One decision per drive. The screen may change while the same elapsed-time deadline runs. */
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
    private val announce: () -> Unit = { NavigationSpeech.get(app).speakTurn("Yakında bir yakıt istasyonu var.") },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val phoneOwners = mutableSetOf<Any>()
    private val autoOwners = mutableSetOf<Any>()
    private val autoMapOwners = mutableSetOf<Any>()
    private val presentable = MutableStateFlow(false)
    private val audience = MutableStateFlow(false)
    private val car = MutableStateFlow(false)
    val autoVisible = car.asStateFlow()
    private var displayEpoch = 0L
    private val mutableOffer = MutableStateFlow<FuelOffer?>(null)
    val offer = mutableOffer.asStateFlow()

    private data class Source(val session: String, val journey: String?, val vehicle: GarageVehicle, val stats: FuelStats)
    private data class Owner(val session: String, val journey: String?, val revision: Long, val vehicle: String)
    private data class Pending(val owner: Owner, val plan: FuelStationPlan, val offer: FuelOffer)
    private var attempted: String? = null
    private var pending: Pending? = null
    private var accepting: String? = null
    private val eligibleOwner = MutableStateFlow<Owner?>(null)
    private val liveDistance = MutableStateFlow(0.0)

    init {
        scope.launch {
            var owner: GarageRepository.Binding? = null
            var previous: TrackPoint? = null
            combine(navigation.state, garage.unrecordedBinding) { state, binding ->
                state to binding?.takeIf { it.sessionId == state.sessionId && !state.recording && !state.simulation }
            }.collect { (state, binding) ->
                if (owner != binding) {
                    owner = binding
                    previous = null
                    liveDistance.value = 0.0
                }
                if (binding == null) return@collect
                val fix = state.fix
                if (fix == null || state.gpsStale || state.paused) { previous = null; return@collect }
                val p = TrackPoint(journeyId = binding.sessionId, latitude = fix.coordinate.latitude,
                    longitude = fix.coordinate.longitude, accuracy = fix.accuracyMeters, recordedAt = fix.recordedAt)
                val a = previous
                if (a != null && p.recordedAt <= a.recordedAt) return@collect
                previous = p
                if (a != null) liveDistance.value += DiaryRules.distanceContributionMeters(a, p) / 1000
            }
        }
        scope.launch {
            navigation.state.map { it.sessionId to it.journey?.id }.distinctUntilChanged()
                .flatMapLatest { (session, journey) ->
                    if (session == null) return@flatMapLatest flowOf(null)
                    val assignment = if (journey == null) garage.unrecordedBinding.map { it?.takeIf { b -> b.sessionId == session }?.vehicleId }
                        else garage.assignedVehicleId(journey)
                    assignment.distinctUntilChanged().flatMapLatest { id ->
                        if (id == null) flowOf(null)
                        else garage.vehicles.map { fleet -> fleet.firstOrNull { it.id == id } }.distinctUntilChanged()
                            .flatMapLatest { v ->
                                if (v == null) flowOf(null) else garage.offerStats(v).map { stats ->
                                    stats?.let { Source(session, journey, v, it) }
                                }
                            }
                            .onStart { emit(null) }
                    }.onStart { emit(null) }
                }.combine(navigation.state) { data, state -> data to state }
                .combine(liveDistance) { (data, state), extra ->
                    if (data == null || data.session != state.sessionId || data.journey != state.journey?.id) return@combine null
                    val vehicle = data.vehicle
                    val stats = data.stats
                    val session = data.session
                    if (state.sessionTransport != vehicle.transport || !ready(state)) return@combine null
                    val km = if (state.recording) 0.0 else extra
                    if (!stats.needsFuel(vehicle, km)) return@combine null
                    Owner(session, state.journey?.id, state.routeRevision, vehicle.id)
                }.combine(audience) { owner, visible -> owner.takeIf { visible } }
                .distinctUntilChanged().collectLatest { owner ->
                    eligibleOwner.value = owner
                    clear()
                    if (owner == null || attempted == owner.session) return@collectLatest
                    presentable.first { it }
                    attempted = owner.session
                    val plan = try { search() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null } ?: return@collectLatest
                    presentable.first { it }
                    if (!current(owner, plan)) return@collectLatest
                    val item = FuelOffer(UUID.randomUUID().toString(), plan.title, elapsed() + 20_000,
                        plan.distanceToStationMeters, plan.additionalSeconds)
                    pending = Pending(owner, plan, item)
                    mutableOffer.value = item
                    if (!navigation.state.value.muted) announce()
                    delay((item.expiresAtElapsedMs - elapsed()).coerceAtLeast(0))
                    if (pending?.offer?.id == item.id) clear()
                }
        }
        scope.launch {
            navigation.state.collect {
                val active = pending ?: return@collect
                if (!current(active.owner, active.plan)) clear()
            }
        }
    }

    private fun ready(state: NavigationState): Boolean =
        !state.simulation && !state.gpsStale && !state.paused && !state.arrived && !state.loading &&
            !state.recordingPending && state.trackFollow == null && state.journey?.status != JourneyStatus.TEMPORARY &&
            state.guidance == (state.route != null) && (state.route == null || state.progress?.offRoute == false) &&
            state.fix?.let { JourneyNavigationCoordinator.fresh(it, wallClock()) } == true

    private fun current(owner: Owner, plan: FuelStationPlan): Boolean {
        val state = navigation.state.value
        val fix = state.fix ?: return false
        return eligibleOwner.value == owner && state.sessionId == owner.session && state.journey?.id == owner.journey &&
            state.routeRevision == owner.revision && ready(state) && audience.value &&
            fix.recordedAt >= plan.owner.fix.recordedAt &&
            WeatherEngine.distanceMeters(fix.coordinate, plan.owner.fix.coordinate) <= FuelStationRouting.MAX_ORIGIN_DRIFT_METERS
    }

    fun setAutoVisible(owner: Any, visible: Boolean) {
        if (visible) autoOwners.add(owner) else autoOwners.remove(owner)
        if (car.value != autoOwners.isNotEmpty()) displayEpoch++
        car.value = autoOwners.isNotEmpty()
        updateDisplay()
    }

    fun setAutoMapVisible(owner: Any, visible: Boolean) {
        if (visible) autoMapOwners.add(owner) else autoMapOwners.remove(owner)
        updateDisplay()
    }

    fun setPhoneVisible(owner: Any, visible: Boolean) {
        if (visible) phoneOwners.add(owner) else phoneOwners.remove(owner)
        updateDisplay()
    }

    private fun updateDisplay() {
        val ready = if (car.value) autoMapOwners.isNotEmpty() else phoneOwners.isNotEmpty()
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
        try {
            garage.applyForOwner(p.owner.session, p.owner.journey, p.owner.vehicle, liveDistance.value) {
                valid() && applyPlan(p.plan, ::valid)
            }
        }
        finally { if (pending === p) clear(); accepting = null }
    }

    fun reject(id: String) {
        if (pending?.offer?.id == id) clear()
    }

    private fun clear() {
        pending = null
        mutableOffer.value = null
    }
}
