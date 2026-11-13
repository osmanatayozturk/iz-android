package org.iz.navigation.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.iz.navigation.placeinfo.*
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.integration.validCoordinate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

internal data class PlaceInformationSession(val state: PlaceInfoState, val instant: Instant,
    val deviceZone: ZoneId, val overrideZoneId: String?, val refresh: () -> Unit,
    val changeZone: (String?) -> Unit)

internal val PlaceInformationSelectionSaver = Saver<SelectedOsmPlace?, List<Any>>(
    save = { place -> place?.osmRef?.let { ref -> listOf(place.name, place.latitude, place.longitude, ref.type.name, ref.id) } ?: emptyList() },
    restore = { values -> runCatching {
        require(values.size == 5)
        val name = (values[0] as String).take(1000)
        val latitude = values[1] as Double
        val longitude = values[2] as Double
        val ref = OsmRef(OsmType.valueOf(values[3] as String), values[4] as Long)
        require(ref.id > 0 && latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        SelectedOsmPlace(name, latitude, longitude, ref)
    }.getOrNull() })

private fun matchingPlaceInformationReceipts(key: PlaceInfoKey?, edits: List<MapEditDraft>): Sequence<MapEditDraft> =
    if (key == null || key.endpoint != PlaceInfoServices.DEFAULT_ENDPOINT) emptySequence() else edits.asSequence().filter {
        it.status == MapEditStatus.SENT && it.targetType == key.ref.type && it.targetId == key.ref.id &&
            (it.appliedVersion ?: 0) > 0
    }

internal fun placeInformationContributionRevision(key: PlaceInfoKey?, edits: List<MapEditDraft>): String =
    matchingPlaceInformationReceipts(key, edits).sortedBy { it.id }.joinToString("|") { "${it.id}:${it.appliedVersion}:${it.submittedAt}" }

internal fun placeInformationContributionVersion(key: PlaceInfoKey?, edits: List<MapEditDraft>): Long? =
    matchingPlaceInformationReceipts(key, edits).mapNotNull { it.appliedVersion }.maxOrNull()

/** Public identity remains owned while a contribution child is visible, but hidden reads/timers stop. */
@Composable
internal fun rememberPlaceInformationSession(key: PlaceInfoKey?, visible: Boolean,
    contributionRevision: String = "",
    repository: PlaceInfoRepository = rememberDefaultPlaceInformationRepository(),
    zoneOverrides: PlaceZoneOverrides = rememberDefaultPlaceInformationOverrides(),
    clock: Clock = Clock.systemUTC(), contributionReady: Boolean = true,
    knownContributionVersion: Long? = null): PlaceInformationSession {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(scope, repository) { PlaceInfoController(scope, repository) }
    val read by controller.state.collectAsStateWithLifecycle()
    val currentKey by rememberUpdatedState(key)
    val currentVisible by rememberUpdatedState(visible)
    var overrideId by remember(key, zoneOverrides) { mutableStateOf(key?.let(zoneOverrides::read)) }
    var previousContribution by remember(key) { mutableStateOf(contributionRevision.takeIf { contributionReady }) }
    var contributionInvalidation by remember(controller, key) { mutableStateOf<Job?>(null) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller, key, visible, lifecycle, contributionReady, knownContributionVersion) {
        controller.close()
        if (key != null && visible && contributionReady) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            contributionInvalidation?.join()
            ensureActive()
            controller.open(key, minimumVersion = knownContributionVersion)
            try { awaitCancellation() } finally { controller.close() }
        }
    }
    LaunchedEffect(controller, key, contributionRevision, contributionReady) {
        if (!contributionReady) return@LaunchedEffect
        if (previousContribution == null) previousContribution = contributionRevision
        else if (key != null && previousContribution != contributionRevision) {
            previousContribution = contributionRevision
            val hidden = !visible || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            // A known version already restarts/reconciles the visible open effect. A synthetic
            // revision without version, or a hidden successful write, still explicitly invalidates.
            if (knownContributionVersion == null || hidden) {
                if (hidden) controller.close()
                contributionInvalidation = controller.contributionSucceeded(key)
            }
        }
    }
    var instant by remember(clock) { mutableStateOf(clock.instant()) }
    var deviceZone by remember { mutableStateOf(ZoneId.systemDefault()) }
    DisposableEffect(context, visible, clock, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                instant = clock.instant()
                deviceZone = ZoneId.systemDefault()
            }
        }
        var registered = false
        fun register() {
            if (!registered && visible) {
                ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            }
        }
        fun unregister() { if (registered) { context.unregisterReceiver(receiver); registered = false } }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) register()
            else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) unregister()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        onDispose { lifecycle.removeObserver(observer); unregister() }
    }
    LaunchedEffect(visible, lifecycle, clock) {
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                instant = clock.instant()
                deviceZone = ZoneId.systemDefault()
                delay(60_000L - Math.floorMod(clock.millis(), 60_000L))
            }
        }
    }
    return PlaceInformationSession(read.takeIf { it.key == key } ?: PlaceInfoState(key = key), instant,
        deviceZone, overrideId, refresh = {
            if (currentVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && currentKey == key && key != null) controller.refresh()
        }, changeZone = { id ->
            if (currentVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && currentKey == key && key != null) {
                val validated = id?.let { runCatching { ZoneId.of(it).id }.getOrNull() }
                if (id == null || validated != null) {
                    zoneOverrides.write(key, validated)
                    overrideId = validated
                    instant = clock.instant()
                }
            }
        })
}

@Composable
private fun rememberDefaultPlaceInformationRepository(): PlaceInfoRepository {
    val application = LocalContext.current.applicationContext
    return remember(application) { PlaceInfoServices.repository(application) }
}

@Composable
private fun rememberDefaultPlaceInformationOverrides(): PlaceZoneOverrides {
    val application = LocalContext.current.applicationContext
    return remember(application) { PlaceInfoServices.zoneOverrides(application) }
}

internal data class PlaceInformationDiscoveryState(val busy: Boolean = false,
    val choices: List<SelectedOsmPlace> = emptyList(), val overlay: List<SelectedOsmPlace> = emptyList(),
    val message: String? = null, val retryable: Boolean = false, val generation: Long = 0, val identity: Any? = null)

/** The real raster-tap producer. Nearby uses the existing service's shared budgets/settings/cache. */
internal class PlaceInformationDiscoveryOwner(private val scope: CoroutineScope,
    private val nearby: suspend (Double, Double) -> List<SelectedOsmPlace>,
    private val onSelect: (SelectedOsmPlace) -> Unit, private val identity: () -> Any?,
    private val stillCurrent: () -> Boolean = { true }) {
    var state by mutableStateOf(PlaceInformationDiscoveryState())
        private set
    private var generation = 0L
    private var job: Job? = null
    private var lastTap: Triple<Double, Double, Double>? = null

    fun tap(latitude: Double, longitude: Double, zoom: Double) {
        if (!scope.isActive || !stillCurrent() || !validCoordinate(latitude, longitude)) return
        job?.cancel()
        val token = ++generation
        val expected = identity()
        lastTap = Triple(latitude, longitude, zoom)
        if (zoom < 16) {
            state = PlaceInformationDiscoveryState(message = "Yakındaki yerleri seçmek için haritayı yakınlaştır.", generation = token, identity = expected)
            return
        }
        state = PlaceInformationDiscoveryState(busy = true, generation = token, identity = expected)
        job = scope.launch {
            try {
                val found = withTimeoutOrNull(20_000) { nearby(latitude, longitude) } ?: error("Discovery timeout")
                ensureActive()
                if (!accepts(token, expected)) return@launch
                val valid = found.filter { it.osmRef?.id?.let { id -> id > 0 } == true &&
                    validCoordinate(it.latitude, it.longitude) }.distinctBy { it.osmRef }
                val bounded = valid.take(50)
                state = PlaceInformationDiscoveryState(choices = if (bounded.size > 1) bounded else emptyList(), overlay = bounded,
                    message = when { bounded.isEmpty() -> "Yakında bir OSM yeri veya bina bulunamadı. Yeni OSM mekânı için haritaya uzun basabilirsin."
                        valid.size > 50 -> "İlk 50 yer gösteriliyor. Daha belirgin bir noktaya dokunarak daraltabilirsin."
                        else -> null }, generation = token, identity = expected)
                if (bounded.size == 1) selectRendered(bounded.single())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (accepts(token, expected)) state = state.copy(busy = false, retryable = true,
                    message = "Yakındaki yerler alınamadı. Bağlantını kontrol edip yeniden dene.")
            } finally {
                if (generation == token) { state = state.copy(busy = false); job = null }
            }
        }
    }

    private fun accepts(token: Long, expected: Any?) = scope.isActive && token == generation && stillCurrent() && identity() == expected
    fun choose(place: SelectedOsmPlace, token: Long) {
        if (token != state.generation) return
        if (!accepts(token, state.identity)) { close(clearOverlay = true); return }
        if (place in state.choices) selectRendered(place)
    }
    fun selectRendered(place: SelectedOsmPlace): Boolean {
        if (!scope.isActive || !stillCurrent() || state.identity != identity() || place.osmRef?.id?.let { it > 0 } != true ||
            !validCoordinate(place.latitude, place.longitude)) return false
        close()
        onSelect(place)
        return true
    }
    fun retry() { if (!state.busy && state.retryable) lastTap?.let { tap(it.first, it.second, it.third) } }
    fun close(clearOverlay: Boolean = false) {
        generation++
        job?.cancel(); job = null
        state = state.copy(busy = false, choices = emptyList(), message = null, retryable = false,
            overlay = if (clearOverlay) emptyList() else state.overlay, generation = generation)
    }
}

@Composable
internal fun PlaceInformationDiscoveryContent(owner: PlaceInformationDiscoveryOwner) {
    val discovery = owner.state
    if (!discovery.busy && discovery.choices.isEmpty() && discovery.message == null) return
    AlertDialog(onDismissRequest = { owner.close() }, title = { Text("Yakındaki yerler") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("place-info-discovery"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (discovery.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("OSM yerleri aranıyor…") }
            discovery.message?.let { Text(it) }
            discovery.choices.forEachIndexed { index, place ->
                TextButton({ owner.choose(place, discovery.generation) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("place-info-discovery-choice-$index")) {
                    val ref = place.osmRef
                    Text("${index + 1}. ${place.name.ifBlank { "Adsız OSM nesnesi" }} · ${ref?.type?.name?.lowercase()}/${ref?.id}")
                }
            }
            Text("© OpenStreetMap katkıcıları", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { if (discovery.retryable) TextButton({ owner.retry() }, Modifier.heightIn(min = 48.dp)
        .testTag("place-info-discovery-retry")) { Text("Yeniden dene") } },
        dismissButton = { TextButton({ owner.close() }, Modifier.heightIn(min = 48.dp).testTag("place-info-discovery-close")) { Text("Vazgeç") } })
}
