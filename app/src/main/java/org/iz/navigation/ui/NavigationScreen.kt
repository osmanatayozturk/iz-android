package org.iz.navigation.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.iz.navigation.IzApplication
import org.iz.navigation.car.NavigationTarget
import org.iz.navigation.data.*
import org.iz.navigation.integration.FullscreenMapDialog
import org.iz.navigation.integration.WeatherRouteMap
import org.iz.navigation.weather.*
import org.iz.navigation.navigation.TrackReplacementConsent
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.trackReplacementKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** New targets invalidate only read work; an explicitly started recording command keeps running. */
internal class PhoneNavigationWork(
    private val scope: CoroutineScope,
    private val onBusy: (Boolean) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private var generation = 0L
    private var readJob: Job? = null
    private var mutating = false
    fun newTarget() {
        generation++
        readJob?.cancel()
        readJob = null
        onBusy(mutating)
    }
    fun read(action: suspend (isCurrent: () -> Boolean) -> Unit) {
        if (mutating || readJob?.isActive == true) return
        val token = ++generation
        onBusy(true)
        readJob = scope.launch(start = CoroutineStart.LAZY) {
            try { action { generation == token } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (generation == token) onError(error) }
            finally {
                if (generation == token) { readJob = null; onBusy(mutating) }
            }
        }.also { it.start() }
    }
    fun mutate(action: suspend () -> Unit) {
        if (mutating || readJob?.isActive == true) return
        mutating = true
        onBusy(true)
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onError(error) }
            finally { mutating = false; onBusy(readJob?.isActive == true) }
        }
    }
}

/** Activity-owned commands survive closing the page; the shared coordinator owns the journey. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PhoneNavigationViewModel(application: Application) : AndroidViewModel(application) {
    val navigation = (application as IzApplication).navigation
    private val repository = DiaryRepository(application)
    private val settingsStore = WeatherSettingsStore(application)
    private val trackConsent = TrackReplacementConsent()
    private suspend fun activateDirections(route: PlannedRoute, record: Boolean): String {
        val key = navigation.state.value.trackReplacementKey()
        val replace = trackConsent.consume(key)
        return navigation.startGuidance(route, record, replace, key.takeIf { replace })
    }
    fun approveTrackReplacement(key: String?) = trackConsent.approve(key)
    val directions = PhoneDirectionsCoordinator(viewModelScope,
        locate = { navigation.currentLocation().coordinate },
        plan = navigation::previewRoute,
        activate = { activateDirections(it, true) },
        activateWithRecording = ::activateDirections,
        readSettings = settingsStore::read,
        savePreferences = { mode, preferences -> settingsStore.save(settingsStore.read(mode).copy(preferences = preferences), mode) },
        planWithPreferences = { stops, mode, speed, preferences ->
            ConfiguredRoutePlanner(application).plan(stops, System.currentTimeMillis(), mode, speed, preferences) },
        routeAlternatives = { stops, mode, speed, preferences ->
            ConfiguredRoutePlanner(application).alternatives(stops, System.currentTimeMillis(), mode, speed, preferences) },
        startRoutePlanner = { ConfiguredRoutePlanner(application) },
        suggestOrderWithPreferences = ConfiguredStopOrderPlanner(application)::propose,
        credentialRevision = { TrafficSettingsStore(application).read().revision })
    val ui = directions.state
    val permissionGate = DirectionsPermissionGate()
    val places = repository.places.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val shortcutLibrary = TravelLibraryRepository(application)
    val shortcuts = shortcutLibrary.destinationShortcuts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val commandBusy = MutableStateFlow(false)
    val commandErrorVersion = MutableStateFlow(0L)
    private val work = PhoneNavigationWork(viewModelScope, { commandBusy.value = it },
        {
            directions.reportMessage(it.message ?: "İşlem tamamlanamadı.")
            commandErrorVersion.update { version -> version + 1 }
        })


    fun enter(entryId: Long, initialStops: List<RouteStop>?, transport: Transport?, initialPlan: SavedWeatherPlan? = null) {
        directions.enter(entryId, initialStops, transport ?: navigation.state.value.sessionTransport ?: navigation.state.value.journey?.transport,
            locateOnOpen = !navigation.state.value.guidance, initialPlan = initialPlan)
        if (initialPlan == null && navigation.state.value.sessionId == null && ui.value.originCurrent && !ui.value.locating) directions.useCurrentOrigin()
    }
    fun target(value: NavigationTarget) {
        value.coordinate?.let { coordinate ->
            directions.setDestination(RouteStop(value.query.ifBlank { "Hedef" }, coordinate))
        }
    }
    fun shortcut(kind: DestinationShortcutKind) {
        if (commandBusy.value || ui.value.starting) return
        work.newTarget()
        work.read { current ->
            val place = shortcutLibrary.destinationShortcuts.first().firstOrNull { it.kind == kind }?.place
            val stop = shortcutRouteStop(place)
            if (current()) {
                if (stop == null) directions.reportMessage("Hedef artık kullanılamıyor. Yerler bölümünden Ev/İş atamasını kontrol et.")
                else {
                    val mode = navigation.state.value.sessionTransport ?: ui.value.transport
                    directions.enter(System.nanoTime(), initialTransport = mode, locateOnOpen = false)
                    directions.setDestination(stop)
                }
            }
        }
    }
    fun lastDestination() {
        navigation.lastRoute?.stops?.lastOrNull()?.let(directions::setDestination)
    }
    fun perform(action: DirectionsAction) {
        if (commandBusy.value || ui.value.starting) return
        when (action) {
            DirectionsAction.CURRENT_ORIGIN -> directions.useCurrentOrigin()
            DirectionsAction.PREVIEW -> directions.preview()
            DirectionsAction.START -> directions.start()
            DirectionsAction.FREE_DRIVE -> freeDrive()
        }
    }
    fun freeDrive() {
        if (ui.value.busy) return
        work.mutate {
            navigation.startFreeDrive(ui.value.transport, ui.value.recordJourney)
            directions.sessionStarted()
        }
    }
    fun confirmEnd(request: PhoneSessionEndRequest) {
        if (commandBusy.value || ui.value.starting || !request.matches(navigation.state.value)) return
        work.mutate {
            val accepted = dispatchPhoneSessionEnd(request, navigation.state.value, ui.value.starting,
                stopGuidance = { navigation.stopGuidance(it) },
                stopRecording = { navigation.stopRecording(it) },
                finish = { navigation.finishSession(it) })
            if (accepted && request.action == PhoneSessionEnd.ALL) directions.showLiveRoute()
        }
    }
}

@Composable
internal fun NavigationScreen(
    onClose: () -> Unit,
    incomingTarget: NavigationTarget? = null,
    initialStops: List<RouteStop>? = null,
    initialTransport: Transport? = null,
    entryId: Long = 0,
    initiallyPlanning: Boolean = true,
    onMenu: () -> Unit = {},
    onGroup: () -> Unit = {},
    onWeather: () -> Unit = {},
    onPin: (GeoCoordinate) -> Unit = {},
    vm: PhoneNavigationViewModel = viewModel(),
    initialPlan: SavedWeatherPlan? = null,
    onSaveRoute: ((List<RouteStop>, Transport, RoutePreferences, Boolean, Double?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.directions.refreshCredentials() }
    val nav by vm.navigation.state.collectAsStateWithLifecycle()
    // Live recording and simulation intentionally publish position only, never a colored trail.
    val points = emptyList<TrackPoint>()
    val places by vm.places.collectAsStateWithLifecycle()
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val commandBusy by vm.commandBusy.collectAsStateWithLifecycle()
    val commandErrorVersion by vm.commandErrorVersion.collectAsStateWithLifecycle()
    val app = context.applicationContext as IzApplication
    val groupState by app.groups.state.collectAsStateWithLifecycle()
    val weather by app.weatherManager.state.collectAsStateWithLifecycle()
    var showPlanning by remember(entryId) { mutableStateOf(initiallyPlanning) }
    var addingVia by remember(entryId) { mutableStateOf(false) }
    var recordDialog by remember { mutableStateOf(false) }
    var sessionActions by remember { mutableStateOf(false) }
    var pendingEnd by remember(entryId) { mutableStateOf<PhoneSessionEndRequest?>(null) }
    fun requestEnd(action: PhoneSessionEnd) {
        if (vm.commandBusy.value || vm.ui.value.starting) return
        val request = phoneSessionEndRequest(action, vm.navigation.state.value) ?: return
        sessionActions = false
        pendingEnd = request
    }
    var pickerOrigin by remember(entryId) { mutableStateOf(false) }
    var showSource by remember(entryId) { mutableStateOf(false) }
    var showSearch by remember(entryId) { mutableStateOf(false) }
    var showPickerMap by remember(entryId) { mutableStateOf(false) }
    var showTrafficSettings by remember { mutableStateOf(false) }
    var incomingQuery by remember(entryId) { mutableStateOf("") }
    var savedPlanPreviewRequested by remember(entryId, initialPlan) { mutableStateOf(false) }
    var trackReplacementDialog by remember(entryId, nav.trackReplacementKey(), vm.directions.revisionToken) {
        mutableStateOf<String?>(null)
    }
    fun hasFineLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val precise = hasFineLocation()
        if (!precise && vm.permissionGate.isPendingFor(entryId, vm.directions.revisionToken)) {
            vm.directions.reportMessage("Güncel konum için hassas konum izni gerekli. İzinleri ayarlayıp yeniden dene.")
        }
        vm.permissionGate.consume(entryId, vm.directions.revisionToken, precise)?.let(vm::perform)
    }
    fun permissionNames() = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (ui.transport.supportsSteps && ui.recordJourney) add(Manifest.permission.ACTIVITY_RECOGNITION)
    }.toTypedArray()
    fun requestAction(action: DirectionsAction, approvedTrackKey: String? = null) {
        val actionState = vm.ui.value
        if (actionState.starting || commandBusy) return
        if (action == DirectionsAction.START) {
            val key = vm.navigation.state.value.trackReplacementKey()
            if (key != null && approvedTrackKey != key) {
                trackReplacementDialog = key
                return
            }
            vm.approveTrackReplacement(approvedTrackKey)
        }
        val required = buildList {
            if ((action != DirectionsAction.PREVIEW || actionState.originCurrent) && !hasFineLocation()) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if ((action == DirectionsAction.START || action == DirectionsAction.FREE_DRIVE) &&
                vm.ui.value.recordJourney && vm.ui.value.transport.supportsSteps &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if ((action == DirectionsAction.START || action == DirectionsAction.FREE_DRIVE) && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (required.isEmpty()) vm.perform(action)
        else if (vm.permissionGate.request(action, entryId, vm.directions.revisionToken)) permissions.launch(required.toTypedArray())
    }
    fun select(stop: RouteStop) {
        if (addingVia) { vm.directions.addVia(stop); addingVia = false }
        else if (pickerOrigin) vm.directions.setOrigin(stop) else vm.directions.setDestination(stop)
    }
    fun closePlanner() { showPlanning = false; onClose() }
    trackReplacementDialog?.let { key ->
        AlertDialog(
            onDismissRequest = { trackReplacementDialog = null },
            title = { Text("GPX takibi değişsin mi?") },
            text = { Text("Bu rotayı başlatınca açık GPX takibi kapanacak. Açık yolculuk kaydın korunur.") },
            confirmButton = { TextButton(onClick = {
                trackReplacementDialog = null
                requestAction(DirectionsAction.START, key)
            }) { Text("Rotayı başlat") } },
            dismissButton = { TextButton(onClick = { trackReplacementDialog = null }) { Text("Vazgeç") } },
        )
    }
    pendingEnd?.let { request ->
        PhoneSessionEndConfirmation(request, nav, commandBusy || ui.starting,
            onDismiss = { pendingEnd = null },
            onConfirm = { pendingEnd = null; vm.confirmEnd(it) })
    }
    BackHandler(enabled = showPlanning, onBack = ::closePlanner)
    var previousSession by remember { mutableStateOf(nav.sessionId) }
    LaunchedEffect(nav.sessionId) {
        if (previousSession != null && nav.sessionId == null && ui.originCurrent) vm.directions.useCurrentOrigin()
        previousSession = nav.sessionId
    }
    val openedStartedCount = remember(entryId) { ui.startedCount }
    LaunchedEffect(ui.startedCount) { if (ui.startedCount > openedStartedCount) showPlanning = false }
    LaunchedEffect(entryId, incomingTarget, initialPlan) {
        vm.enter(entryId, initialStops, initialTransport, initialPlan)
        if (initialPlan != null && !savedPlanPreviewRequested) {
            savedPlanPreviewRequested = true
            requestAction(DirectionsAction.PREVIEW)
        }
        incomingTarget?.let { target ->
            vm.target(target)
            if (target.coordinate == null && target.query.isNotBlank()) {
                incomingQuery = target.query
                pickerOrigin = false
                showSearch = true
            }
        }
    }

    NavigationHomeContent(ui = ui, nav = nav, points = points,
        shortcuts = shortcuts, onShortcut = { kind -> showPlanning = true; vm.shortcut(kind) },
        members = groupState.markers.map { org.iz.navigation.integration.RouteMapMember(it.userId, it.name, it.latitude, it.longitude, it.delayed) },
        showPlanner = showPlanning,
        weatherLabel = weather.assessment?.samples?.firstOrNull()?.reading?.temperatureC?.let { "${it.toInt()}° · Hava" } ?: "Hava durumu",
        onSearch = {
            val current = vm.navigation.state.value
            openPhoneDirectionsPanel(current, showPlanning,
                { vm.enter(System.nanoTime(), null, current.sessionTransport ?: vm.ui.value.transport) }, { showPlanning = true })
        },
        onRecord = {
            if (nav.recording) sessionActions = true
            else if (nav.sessionId != null) {
                vm.directions.transport(nav.sessionTransport ?: ui.transport)
                vm.directions.setRecordJourney(true)
                requestAction(DirectionsAction.FREE_DRIVE)
            } else recordDialog = true
        },
        onGroup = onGroup, onMenu = onMenu, onWeather = onWeather,
        onMute = { vm.navigation.setMuted(!nav.muted) }, onManageSession = { showPlanning = true },
        onLocate = { requestAction(DirectionsAction.CURRENT_ORIGIN) }, onPin = onPin,
    ) {
    PhoneDirectionsContent(
        ui = ui, nav = nav, points = points, commandBusy = commandBusy, commandErrorVersion = commandErrorVersion,
        onClose = ::closePlanner,
        chooseOrigin = { addingVia = false; pickerOrigin = true; showSource = true },
        chooseDestination = { addingVia = false; pickerOrigin = false; showSource = true },
        currentOrigin = { requestAction(DirectionsAction.CURRENT_ORIGIN) },
        transport = vm.directions::transport,
        preview = { requestAction(DirectionsAction.PREVIEW) }, start = { requestAction(DirectionsAction.START) },
        freeDrive = { requestAction(DirectionsAction.FREE_DRIVE) }, finish = { requestEnd(PhoneSessionEnd.ALL) },
        mute = { vm.navigation.setMuted(!nav.muted) }, stopGuidance = { requestEnd(PhoneSessionEnd.GUIDANCE) },
        showLive = vm.directions::showLiveRoute,
        trafficSettings = { showTrafficSettings = true },
        lastDestination = if (vm.navigation.lastRoute != null) vm::lastDestination else null,
        permissions = { permissions.launch(permissionNames()) },
        showMap = false, recordJourney = vm.directions::setRecordJourney, stopRecording = { requestEnd(PhoneSessionEnd.RECORDING) },
        addVia = { addingVia = true; pickerOrigin = false; showSource = true }, removeVia = vm.directions::removeVia,
        suggestStopOrder = { vm.directions.suggestStopOrder() }, acceptStopOrder = vm.directions::acceptStopOrder,
        dismissStopOrder = vm.directions::dismissStopOrder,
        setPreferences = vm.directions::setPreferences,
        requestAlternatives = { vm.directions.requestAlternatives() },
        selectAlternative = vm.directions::selectAlternative,
        saveRoute = onSaveRoute?.let { callback -> { callback(ui.stops, ui.transport, ui.preferences, ui.originCurrent, ui.travelSpeedKmh) } },
        onPrepareGroup = { ui.preview?.let { app.groups.prepareRoute(it.stops, it.transport); onGroup() } },
    )
    }
    if (recordDialog) TransportDialog(ui.transport, "Yolculuğu kaydet", onDismiss = { recordDialog = false }) { selected ->
        vm.directions.transport(selected)
        vm.directions.setRecordJourney(true)
        recordDialog = false
        requestAction(DirectionsAction.FREE_DRIVE)
    }
    if (sessionActions) AlertDialog(onDismissRequest = { sessionActions = false }, title = { Text("Yolculuk işlemleri") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (nav.guidance) OutlinedButton({ requestEnd(PhoneSessionEnd.GUIDANCE) }, enabled = !commandBusy && !ui.starting) { Text(PhoneSessionEnd.GUIDANCE.label) }
            if (nav.recording) OutlinedButton({ requestEnd(PhoneSessionEnd.RECORDING) }, enabled = !commandBusy && !ui.starting) { Text(PhoneSessionEnd.RECORDING.label) }
            Button({ requestEnd(PhoneSessionEnd.ALL) }, enabled = !commandBusy && !ui.starting) { Text(PhoneSessionEnd.ALL.label) }
        }
    }, confirmButton = { TextButton({ sessionActions = false }) { Text("Kapat") } })
    if (showSource) WeatherStopSourceDialog(
        places = places, onDismiss = { showSource = false },
        onSaved = { place ->
            select(RouteStop(place.name, WeatherCoordinate(place.latitude!!, place.longitude!!)))
            showSource = false
        },
        onSearch = { incomingQuery = ""; showSource = false; showSearch = true },
        onMap = { showSource = false; showPickerMap = true },
    )
    if (showSearch) OsmSearchDialog(
        onDismiss = { showSearch = false },
        onSelect = { value -> select(RouteStop(value.name, WeatherCoordinate(value.latitude, value.longitude))); showSearch = false },
        initialQuery = incomingQuery,
    )
    if (showPickerMap) FullscreenMapDialog(
        title = if (addingVia) "Ara durak seç" else if (pickerOrigin) "A · Başlangıç seç" else "B · Varış seç",
        onDismiss = { showPickerMap = false },
        bottomBar = { Text("Noktayı seçmek için haritaya uzun bas.", Modifier.padding(16.dp)) },
    ) { mapModifier ->
        WeatherRouteMap(
            route = ui.preview, assessment = null, modifier = mapModifier, expandable = false,
            stops = ui.stops, liveCoordinate = nav.fix?.coordinate ?: ui.origin?.takeIf { ui.originCurrent }?.coordinate,
            singleStopLabel = if (ui.origin == null && ui.destination != null) "B" else "A",
            onMapLongClick = { coordinate ->
                select(RouteStop("Harita noktası", WeatherCoordinate(coordinate.latitude, coordinate.longitude)))
                showPickerMap = false
            },
        )
    }
    if (showTrafficSettings) TrafficSettingsDialog(onDismiss = { showTrafficSettings = false; vm.directions.showLiveRoute() })
}

@Composable
internal fun PhoneSessionEndConfirmation(
    request: PhoneSessionEndRequest,
    current: NavigationState,
    locked: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PhoneSessionEndRequest) -> Unit,
) {
    val currentRequest = request.matches(current)
    LaunchedEffect(currentRequest) { if (!currentRequest) onDismiss() }
    if (!currentRequest) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(request.explanation) } },
        confirmButton = {
            TextButton(onClick = { if (!locked && request.matches(current)) onConfirm(request) }, enabled = !locked,
                modifier = Modifier.testTag("confirm-session-end")) { Text(request.action.label) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel-session-end")) { Text("Vazgeç") } },
    )
}
