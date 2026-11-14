package org.iz.navigation.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.iz.navigation.placeinfo.*
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
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
