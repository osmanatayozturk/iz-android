package org.iz.navigation.navigation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.iz.navigation.integration.OsmPlaces
import org.iz.navigation.integration.RouteCorridorPlace
import org.iz.navigation.integration.RouteCorridorKind
import org.iz.navigation.weather.*

enum class RouteStopLookupKind { FUEL, REST }
data class RouteStopLookupState(val owner: RouteEditOwner? = null, val requestId: String? = null,
    val kind: RouteStopLookupKind? = null, val loading: Boolean = false, val results: List<RouteCorridorPlace> = emptyList(),
    val fetchedAt: Long? = null, val error: String? = null, val atRemainingMeters: Double? = null)

/** Explicit bounded public POI lookups, retired whenever the navigation owner changes. */
class RouteOperationsController(context: Context, private val navigation: JourneyNavigationCoordinator) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val places = OsmPlaces(context.applicationContext)
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutableOnline = MutableStateFlow<Boolean?>(null)
    val online = mutableOnline.asStateFlow()
    private val mutableLookup = MutableStateFlow(RouteStopLookupState())
    val lookup = mutableLookup.asStateFlow()
    private var generation = 0L
    private var lookupJob: Job? = null
    private fun refreshNetwork() {
        mutableOnline.value = runCatching {
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrNull()
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scope.launch { refreshNetwork() } }
        override fun onLost(network: Network) { scope.launch { refreshNetwork() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { scope.launch { refreshNetwork() } }
    }
    init {
        refreshNetwork()
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        scope.launch { navigation.state.collect { nav ->
            val owner = mutableLookup.value.owner
            if (owner != null && (!owner.matches(nav) || nav.paused || !nav.guidance || nav.arrived)) cancelLookup()
            else if (owner != null && !mutableLookup.value.loading) {
                val before = mutableLookup.value
                val remaining = nav.progress?.remainingMeters
                if (remaining != null && remaining.isFinite() && remaining >= 0 && before.atRemainingMeters != null) {
                    val advanced = (before.atRemainingMeters - remaining).coerceAtLeast(0.0)
                    mutableLookup.value = before.copy(results = before.results.mapNotNull { item ->
                        (item.forwardMeters - advanced).takeIf { it >= 0 }?.let { item.copy(forwardMeters = it) }
                    }, atRemainingMeters = minOf(before.atRemainingMeters, remaining))
                }
            }
        } }
    }
    fun cancelLookup() {
        generation++; lookupJob?.cancel(); lookupJob = null
        mutableLookup.value = RouteStopLookupState()
    }
    suspend fun lookup(expected: RouteEditOwner, kind: RouteStopLookupKind) = coroutineScope {
        val nav = navigation.state.value
        require(expected.matches(nav) && nav.guidance && !nav.paused && !nav.gpsStale && !nav.arrived &&
            nav.fix?.let { JourneyNavigationCoordinator.fresh(it, System.currentTimeMillis()) } == true) { "Güncel rota konumu bekleniyor." }
        require(online.value == true) { "Çevrimiçi durak araması için internet bağlantısı gerekiyor." }
        val route = requireNotNull(nav.route); val progress = requireNotNull(nav.progress)
        require(!progress.offRoute)
        cancelLookup()
        val token = generation
        val id = UUID.randomUUID().toString()
        lookupJob = currentCoroutineContext()[Job]
        mutableLookup.value = RouteStopLookupState(expected, id, kind, loading = true)
        try {
            val result = places.routeFuelAndRest(route, progress.elapsedSeconds)
            currentCoroutineContext().ensureActive()
            if (token == generation && expected.matches(navigation.state.value) && !navigation.state.value.paused) {
                val selected = result.filter { when (kind) {
                    RouteStopLookupKind.FUEL -> it.kind == RouteCorridorKind.FUEL
                    RouteStopLookupKind.REST -> it.kind == RouteCorridorKind.REST
                } }.filter { it.place.osmRef != null }.take(50)
                val remaining = navigation.state.value.progress?.remainingMeters ?: progress.remainingMeters
                val advanced = (progress.remainingMeters - remaining).coerceAtLeast(0.0)
                mutableLookup.value = RouteStopLookupState(expected, id, kind,
                    results = selected.mapNotNull { item -> (item.forwardMeters - advanced).takeIf { it >= 0 }?.let { item.copy(forwardMeters = it) } },
                    fetchedAt = System.currentTimeMillis(), atRemainingMeters = minOf(progress.remainingMeters, remaining))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (token == generation && expected.matches(navigation.state.value)) mutableLookup.value =
                RouteStopLookupState(expected, id, kind, error = "Duraklar alınamadı. Bağlantı veya servis sorgu sınırını kontrol et.")
        } finally { if (token == generation) { lookupJob = null; mutableLookup.value = mutableLookup.value.copy(loading = false) } }
    }
    suspend fun select(expected: RouteEditOwner, requestId: String, resultId: String): String? {
        val current = lookup.value
        require(current.owner == expected && expected.matches(navigation.state.value) && current.requestId == requestId &&
            !current.loading && current.fetchedAt?.let { System.currentTimeMillis() - it in 0..600_000 } == true) { "Durak listesi değişti; yeniden ara." }
        val selected = current.results.singleOrNull { identity(it) == resultId } ?: error("Durak artık listede yok.")
        val nowProgress = requireNotNull(navigation.state.value.progress)
        val advanced = ((current.atRemainingMeters ?: nowProgress.remainingMeters) - nowProgress.remainingMeters).coerceAtLeast(0.0)
        require(selected.forwardMeters >= advanced) { "Bu durak geçildi. Durak listesini yenile." }
        return navigation.previewEdit(RouteEditAction.AddStop(RouteStop(selected.place.name,
            WeatherCoordinate(selected.place.latitude, selected.place.longitude))), expected)
    }
    companion object {
        fun identity(value: RouteCorridorPlace): String? = value.place.osmRef?.let { "${it.type.name}:${it.id}" }
    }
}
