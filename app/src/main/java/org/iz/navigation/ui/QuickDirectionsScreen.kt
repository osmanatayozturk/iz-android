@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DirectionsBoat
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.iz.navigation.data.Place
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.OsmPlaces
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.NavigationStopSnapshot
import org.iz.navigation.navigation.QuickNavigationOrigin
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RoutePreferences
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.integration.WeatherRouteMap

internal enum class QuickRoutePreference(val label: String, val icon: ImageVector) {
    HIGHWAYS("Otoyol", Icons.Outlined.Route),
    TOLLS("Ücretli yol", Icons.Outlined.Toll),
    FERRIES("Feribot", Icons.Outlined.DirectionsBoat),
    UNPAVED("Toprak yol", Icons.Outlined.Terrain);

    fun supports(transport: Transport): Boolean = when (this) {
        HIGHWAYS, FERRIES -> true
        TOLLS, UNPAVED -> transport in setOf(Transport.CAR, Transport.MOTORCYCLE, Transport.PASSENGER)
    }

    fun applyTo(base: RoutePreferences, avoid: Boolean): RoutePreferences = when (this) {
        HIGHWAYS -> base.copy(avoidHighways = avoid)
        TOLLS -> base.copy(avoidTolls = avoid)
        FERRIES -> base.copy(avoidFerries = avoid)
        UNPAVED -> base.copy(avoidUnpaved = avoid)
    }
}

private enum class QuickRoutePhase(val label: String) {
    LOCATION("Başlangıç konumu alınıyor…"), ROUTE("Rota hesaplanıyor…"), START("Rota açılıyor…")
}

private data class QuickRouteRequest(
    val destination: RouteStop,
    val transport: Transport,
    val choices: Map<QuickRoutePreference, Boolean>,
    val recordJourney: Boolean,
    val navigation: NavigationState,
    val configuration: String = "",
    val targetOwner: Any? = null,
)
private data class QuickRoutePreview(val route: PlannedRoute, val origin: QuickNavigationOrigin, val request: QuickRouteRequest)

private fun NavigationState.canQuickStart(transport: Transport): Boolean =
    !loading && (!recording || transport == (journey?.transport ?: sessionTransport))

@Composable
internal fun QuickDirectionsDialog(
    places: List<Place>,
    navigationState: StateFlow<NavigationState>,
    locate: suspend () -> QuickNavigationOrigin,
    validateOrigin: (QuickNavigationOrigin) -> Unit,
    plan: suspend (WeatherCoordinate, RouteStop, Transport, Map<QuickRoutePreference, Boolean>) -> PlannedRoute,
    activate: suspend (PlannedRoute, QuickNavigationOrigin, Boolean) -> String,
    onDismiss: () -> Unit,
    onStarted: () -> Unit,
    commandBusy: Boolean = false,
    stillOnEntry: () -> Boolean = { true },
    searchPlaces: (suspend (String) -> List<SelectedOsmPlace>)? = null,
    hasLocationPermission: (() -> Boolean)? = null,
    initialDestination: RouteStop? = null,
    searchOrigin: suspend () -> WeatherCoordinate? = { null },
    sharedPreview: Boolean = false,
    configuration: () -> String = { "" },
    onSharedText: ((String) -> Unit)? = null,
    previewMap: @Composable (PlannedRoute) -> Unit = { route ->
        WeatherRouteMap(route, null, Modifier.fillMaxSize(), expandable = false, stops = route.stops)
    },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onRouteStarted by rememberUpdatedState(onStarted)
    val currentlyBusy by rememberUpdatedState(commandBusy)
    val entryCurrent by rememberUpdatedState(stillOnEntry)
    val currentSearchOrigin by rememberUpdatedState(searchOrigin)
    val currentConfiguration by rememberUpdatedState(configuration)
    val search = remember(context, scope, searchPlaces) {
        val service by lazy { OsmPlaces(context) }
        val submit: suspend (String) -> List<SelectedOsmPlace> = searchPlaces ?: { query ->
            service.searchForDirections(query, currentSearchOrigin)
        }
        OsmSearchController(scope, "", submit)
    }
    val results by search.state.collectAsStateWithLifecycle()
    val nav by navigationState.collectAsStateWithLifecycle()
    val destinationList = rememberLazyListState()
    var destination by remember(initialDestination) { mutableStateOf(initialDestination) }
    val targetOwner = remember(initialDestination, sharedPreview) { Any() }
    val currentTargetOwner by rememberUpdatedState(targetOwner)
    val currentDestination by rememberUpdatedState(destination)
    var choosingMode by remember(initialDestination) { mutableStateOf(initialDestination != null) }
    var choices by remember { mutableStateOf(emptyMap<QuickRoutePreference, Boolean>()) }
    var recordJourney by rememberSaveable { mutableStateOf(true) }
    var permissionRequest by remember(targetOwner) { mutableStateOf<QuickRouteRequest?>(null) }
    var lastMode by rememberSaveable { mutableStateOf<Transport?>(if (sharedPreview) Transport.CAR else null) }
    var preview by remember(targetOwner) { mutableStateOf<QuickRoutePreview?>(null) }
    var completed by remember(targetOwner) { mutableStateOf(false) }
    var phase by remember(targetOwner) { mutableStateOf<QuickRoutePhase?>(null) }
    var error by remember(targetOwner) { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var routeJob by remember(targetOwner) { mutableStateOf<Job?>(null) }
    var generation by remember { mutableLongStateOf(0L) }
    DisposableEffect(targetOwner) {
        onDispose {
            generation++
            // A replacement cancels read/preview work, not an already explicitly dispatched start.
            if (phase != QuickRoutePhase.START) routeJob?.cancel()
            permissionRequest = null
        }
    }
    DisposableEffect(search) { onDispose { search.close() } }

    fun hasLocation() = hasLocationPermission?.invoke() ?: (
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)

    fun fail(message: String, settings: Boolean = false) {
        error = message
        showSettings = settings
    }

    fun start(request: QuickRouteRequest) {
        val token = ++generation
        fun stillCurrent(): Boolean {
            val current = navigationState.value
            return !completed && token == generation && request.targetOwner === currentTargetOwner &&
                choosingMode && currentDestination == request.destination && !currentlyBusy && entryCurrent() &&
                current.routeRevision == request.navigation.routeRevision &&
                NavigationStopSnapshot.from(current) == NavigationStopSnapshot.from(request.navigation) &&
                currentConfiguration() == request.configuration &&
                current.canQuickStart(request.transport)
        }

        phase = QuickRoutePhase.LOCATION
        routeJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                check(stillCurrent())
                val origin = locate()
                ensureActive()
                check(stillCurrent())
                validateOrigin(origin)
                phase = QuickRoutePhase.ROUTE
                val route = plan(origin.fix.coordinate, request.destination, request.transport, request.choices)
                ensureActive()
                check(stillCurrent())
                if (sharedPreview) preview = QuickRoutePreview(route, origin, request)
                else {
                    phase = QuickRoutePhase.START
                    activate(route, origin, request.recordJourney)
                    if (token == generation && isActive) { completed = true; onRouteStarted() }
                }
            } catch (cancelled: CancellationException) {
                if (token == generation && isActive) fail("Rota isteği kesildi. Yeniden dene.")
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) when {
                    navigationState.value.trackFollow != null -> fail("GPX takibi sürüyor. Yeni rota için önce takibi bitir.")
                    navigationState.value.route != null -> fail("Yol tarifi sürüyor. Yeni rota için önce açık yol tarifini bitir.")
                    !hasLocation() -> fail("Konum izni değişti. Ayarlardan izin verip yeniden dene.", settings = true)
                    phase == QuickRoutePhase.LOCATION -> fail("Başlangıç konumu alınamadı. Konumu açıp yeniden dene.", settings = true)
                    phase == QuickRoutePhase.ROUTE -> fail("Rota hesaplanamadı. Bağlantıyı ve rota tercihlerini kontrol edip yeniden dene.")
                    else -> fail("Rota açılamadı. Mevcut yolculuğu ve konumu kontrol edip yeniden dene.")
                }
            } finally {
                if (token == generation) {
                    phase = null
                    routeJob = null
                }
            }
        }
        routeJob?.start()
    }

    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = permissionRequest
        permissionRequest = null
        if (request != null && request.targetOwner === currentTargetOwner && entryCurrent() && choosingMode && request.destination == currentDestination) {
            if (hasLocation()) start(request)
            else fail("Rota başlangıcı için konum izni ver. Yaklaşık konum yeterli.", settings = true)
        }
    }

    fun pickMode(mode: Transport, previewRequest: Boolean = false) {
        val target = destination ?: return
        val current = navigationState.value
        if (!entryCurrent() || currentlyBusy || routeJob != null || permissionRequest != null || !current.canQuickStart(mode)) return
        lastMode = mode
        error = null
        showSettings = false
        preview = null
        if (sharedPreview && !previewRequest) return
        val request = QuickRouteRequest(target, mode, choices, current.recording || recordJourney, current, currentConfiguration(), targetOwner)
        if (hasLocation()) start(request)
        else {
            permissionRequest = request
            try {
                requestLocation.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            } catch (_: IllegalStateException) {
                permissionRequest = null
                fail("Konum izin ekranı açılamadı. İzni ayarlardan verebilirsin.", settings = true)
            }
        }
    }

    fun back() {
        if (phase == QuickRoutePhase.START) return
        if (preview != null) { preview = null; return }
        if (!choosingMode) {
            search.close()
            onDismiss()
            return
        }
        generation++
        permissionRequest = null
        routeJob?.cancel()
        routeJob = null
        phase = null
        error = null
        if (initialDestination == null) choosingMode = false
        else {
            search.close()
            onDismiss()
        }
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false,
    )) {
        Surface(Modifier.fillMaxSize().testTag("quick-directions")) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp)) {
                TextButton(::back, enabled = phase != QuickRoutePhase.START,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("quick-back")) { Text("Geri") }
                if (choosingMode) {
                    val prepared = preview
                    if (prepared != null) {
                        val valid = prepared.request.targetOwner === currentTargetOwner &&
                            currentDestination == prepared.request.destination && entryCurrent() && currentConfiguration() == prepared.request.configuration &&
                            nav.routeRevision == prepared.request.navigation.routeRevision &&
                            NavigationStopSnapshot.from(nav) == NavigationStopSnapshot.from(prepared.request.navigation)
                        SharedRoutePreviewContent(prepared.request.destination.label, prepared.request.recordJourney,
                            error ?: if (!valid) "Hedef, yapılandırma veya oturum değişti. Geri dönüp yeniden önizle." else null,
                            startEnabled = valid && !completed && phase == null && !commandBusy,
                            modifier = Modifier.weight(1f), previewMap = { previewMap(prepared.route) }, onStart = {
                            if (preview !== prepared || lastMode != prepared.request.transport || !valid ||
                                prepared.request.targetOwner !== currentTargetOwner || prepared.request.destination != currentDestination ||
                                completed || routeJob != null || phase != null) return@SharedRoutePreviewContent
                            val token = ++generation
                            phase = QuickRoutePhase.START
                            routeJob = scope.launch(start = CoroutineStart.LAZY) {
                                try {
                                    check(prepared.request.targetOwner === currentTargetOwner && prepared.request.destination == currentDestination &&
                                        entryCurrent() && currentConfiguration() == prepared.request.configuration)
                                    val live = navigationState.value
                                    check(live.routeRevision == prepared.request.navigation.routeRevision &&
                                        NavigationStopSnapshot.from(live) == NavigationStopSnapshot.from(prepared.request.navigation))
                                    validateOrigin(prepared.origin)
                                    activate(prepared.route, prepared.origin, prepared.request.recordJourney)
                                    if (token == generation && isActive) { completed = true; onRouteStarted() }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { if (token == generation) { preview = null; fail("Rota veya oturum değişti. Yeniden önizle.") } }
                                finally { if (token == generation) { phase = null; routeJob = null } }
                            }.also { it.start() }
                        })
                    } else {
                    Box(Modifier.weight(1f)) { QuickModePicker(destination = checkNotNull(destination), nav = nav, choices = choices, recordJourney = recordJourney,
                        busy = commandBusy || phase != null || permissionRequest != null, phase = phase?.label, error = error,
                        onRecord = { recordJourney = it }, onMode = { pickMode(it) },
                        shared = sharedPreview, selectedMode = lastMode,
                        retry = lastMode?.takeIf(nav::canQuickStart)?.let { mode -> { pickMode(mode, previewRequest = true) } },
                        openSettings = if (showSettings) ({
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            runCatching { context.startActivity(intent) }.onFailure { fail("Uygulama ayarları açılamadı.", settings = true) }
                        }) else null,
                    ) }
                    if (sharedPreview) Button(onClick = {
                        lastMode?.let { pickMode(it, previewRequest = true) }
                    }, enabled = lastMode?.let(nav::canQuickStart) == true && phase == null && !commandBusy && permissionRequest == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shared-preview")) { Text("Rota önizle") }
                    }
                } else {
                    QuickDestinationPicker(results, places, destinationList, choices, search::edit, {
                        val raw = search.state.value.query
                        if (onSharedText != null && (raw.contains("://") || raw.contains("geo:", true) || raw.contains("google.navigation:", true)))
                            onSharedText(raw) else search.submit()
                    }, onPreference = { preference ->
                        choices = when (choices[preference]) {
                            null -> choices + (preference to true)
                            true -> choices + (preference to false)
                            false -> choices - preference
                        }
                    }) { stop ->
                        destination = stop
                        choosingMode = true
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickDestinationPicker(state: OsmSearchUiState, places: List<Place>, listState: LazyListState,
    choices: Map<QuickRoutePreference, Boolean>, onEdit: (String) -> Unit, onSearch: () -> Unit, onPreference: (QuickRoutePreference) -> Unit,
    onChoose: (RouteStop) -> Unit) {
    val focus = LocalFocusManager.current
    val canSearch = state.query.isNotBlank() && !state.busy
    val submit = { if (canSearch) { focus.clearFocus(); onSearch() } }
    val choose: (RouteStop) -> Unit = { focus.clearFocus(); onChoose(it) }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize().testTag("quick-destinations")) {
        item { Text("Nereye?", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium) }
        item {
            CommonPlaceQuery(state.query, state.busy, onEdit, onSearch, "quick-search")
        }
        item { QuickPreferenceButtons(choices, onPreference) }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (state.searched && state.results.isEmpty()) item { Text("Yer bulunamadı. Başka bir ad veya adres dene.") }
        items(state.results) { place ->
            CommonPlaceResult(place) { choose(quickStop(it.name, it.latitude, it.longitude)) }
        }
        item { Text("Arama Nominatim üzerinden yapılır. Sonuçlar © OpenStreetMap katkıcıları.", style = MaterialTheme.typography.bodySmall) }
        item { Text("Kayıtlı yerler", style = MaterialTheme.typography.titleMedium) }
    val located = places.filter { place ->
        place.latitude?.let { it in -90.0..90.0 } == true && place.longitude?.let { it in -180.0..180.0 } == true
    }
        if (located.isEmpty()) item { Text("Konumu olan kayıtlı yer yok.") }
        items(located, key = { it.id }) { place ->
            OutlinedCard({ choose(quickStop(place.name, checkNotNull(place.latitude), checkNotNull(place.longitude))) },
                Modifier.fillMaxWidth()) { Text(place.name, Modifier.heightIn(min = 48.dp).padding(12.dp)) }
        }
    }
}

private fun quickStop(name: String, latitude: Double, longitude: Double) =
    RouteStop(name.trim().take(300).ifBlank { "Hedef" }, WeatherCoordinate(latitude, longitude))

@Composable
private fun QuickPreferenceButtons(choices: Map<QuickRoutePreference, Boolean>, onPreference: (QuickRoutePreference) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickRoutePreference.entries.forEach { preference ->
            val choice = choices[preference]
            val label = when (choice) {
                null -> "Vasıta varsayılanı"
                true -> "Bu gezi için kaçın"
                false -> "Bu gezi için izin ver"
            }
            IconButton(onClick = { onPreference(preference) },
                modifier = Modifier.size(48.dp).testTag("quick-preference-${preference.name}").semantics {
                    contentDescription = preference.label
                    stateDescription = label
                }, colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (choice == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (choice == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                )) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(preference.icon, null, Modifier.size(22.dp))
                    if (choice != null) Surface(Modifier.align(Alignment.BottomEnd).size(14.dp), shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Icon(if (choice) Icons.Outlined.Check else Icons.Outlined.Remove, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickModePicker(destination: RouteStop, nav: NavigationState, choices: Map<QuickRoutePreference, Boolean>,
    recordJourney: Boolean, busy: Boolean, phase: String?, error: String?,
    onRecord: (Boolean) -> Unit, onMode: (Transport) -> Unit, retry: (() -> Unit)?, openSettings: (() -> Unit)?,
    shared: Boolean = false, selectedMode: Transport? = null) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize().testTag("quick-route-options")) {
        item { Text(destination.label, style = MaterialTheme.typography.headlineMedium) }
        val limited = choices.keys.filterNot { it.supports(Transport.WALK) }
        if (limited.isNotEmpty()) item {
            val names = limited.joinToString { it.label }
            Text("$names seçimi bisiklet, yürüyüş ve koşuda uygulanmaz.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val recordLabel = if (shared) "Bu yolculuğu kaydet" else "Yolculuğu kaydet"
                Text(recordLabel, Modifier.weight(1f).clearAndSetSemantics {})
                Switch(checked = nav.recording || recordJourney, onCheckedChange = onRecord, enabled = !nav.recording && !busy,
                    modifier = Modifier.testTag("quick-record").semantics { contentDescription = recordLabel })
            }
            if (nav.recording) Text("Açık kayıt devam eder. Yalnız mevcut ulaşım türü kullanılabilir.", style = MaterialTheme.typography.bodySmall)
            else if (recordJourney) Text("Kayıt, hassas konum izni ve güvenilir konum alındığında başlar.", style = MaterialTheme.typography.bodySmall)
        }
        if (phase != null) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(phase, Modifier.testTag("quick-route-progress"))
        }
        if (error != null) item {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("quick-route-error"))
            Row {
                if (retry != null) TextButton(retry, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("quick-retry")) { Text("Yeniden dene") }
                if (openSettings != null) TextButton(openSettings, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ayarları aç") }
            }
        }
        item { Text(if (shared) "Ulaşım türünü seç" else "Ulaşım türüne dokun ve rotayı aç", style = MaterialTheme.typography.titleMedium) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                transportDisplayOrder.forEach { mode ->
                    OutlinedButton({ onMode(mode) }, enabled = !busy && nav.canQuickStart(mode),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("quick-mode-${mode.name}")
                            .semantics { selected = selectedMode == mode }) {
                        Icon(mode.icon(), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(mode.label())
                    }
                }
            }
        }
    }
}
