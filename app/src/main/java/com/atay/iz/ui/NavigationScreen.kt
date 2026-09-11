package com.atay.iz.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atay.iz.IzApplication
import com.atay.iz.car.NavigationTarget
import com.atay.iz.data.*
import com.atay.iz.integration.FullscreenMapDialog
import com.atay.iz.integration.WeatherRouteMap
import com.atay.iz.weather.*
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
    val directions = PhoneDirectionsCoordinator(viewModelScope,
        locate = { navigation.currentLocation().coordinate },
        plan = navigation::previewRoute,
        activate = { navigation.startGuidance(it) },
        activateWithRecording = { route, record -> navigation.startGuidance(route, record) })
    val ui = directions.state
    val permissionGate = DirectionsPermissionGate()
    val places = repository.places.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val commandBusy = MutableStateFlow(false)
    private val work = PhoneNavigationWork(viewModelScope, { commandBusy.value = it },
        { directions.reportMessage(it.message ?: "İşlem tamamlanamadı.") })
    val points = navigation.state.flatMapLatest { state ->
        when {
            state.simulation -> navigation.simulationPoints
            state.recording && state.journey != null -> repository.observeJourneyPoints(state.journey.id)
            else -> flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun enter(entryId: Long, initialStops: List<RouteStop>?, transport: Transport?) {
        directions.enter(entryId, initialStops, transport ?: navigation.state.value.sessionTransport ?: navigation.state.value.journey?.transport,
            locateOnOpen = !navigation.state.value.guidance)
        if (navigation.state.value.sessionId == null && ui.value.originCurrent && !ui.value.locating) directions.useCurrentOrigin()
    }
    fun target(value: NavigationTarget) {
        value.coordinate?.let { coordinate ->
            directions.setDestination(RouteStop(value.query.ifBlank { "Hedef" }, coordinate))
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
    fun stopRecording() {
        val id = navigation.state.value.journey?.id ?: return
        work.mutate { navigation.stopRecording(id) }
    }
    fun finish() {
        val id = navigation.state.value.sessionId ?: return
        finishNavigationWork(directions, work) { navigation.finishSession(id) }
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
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val nav by vm.navigation.state.collectAsStateWithLifecycle()
    val points by vm.points.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val commandBusy by vm.commandBusy.collectAsStateWithLifecycle()
    val app = context.applicationContext as IzApplication
    val groupState by app.groups.state.collectAsStateWithLifecycle()
    val weather by app.weatherManager.state.collectAsStateWithLifecycle()
    var showPlanning by remember(entryId) { mutableStateOf(initiallyPlanning) }
    var addingVia by remember(entryId) { mutableStateOf(false) }
    var recordDialog by remember { mutableStateOf(false) }
    var sessionActions by remember { mutableStateOf(false) }
    var pickerOrigin by remember(entryId) { mutableStateOf(false) }
    var showSource by remember(entryId) { mutableStateOf(false) }
    var showSearch by remember(entryId) { mutableStateOf(false) }
    var showPickerMap by remember(entryId) { mutableStateOf(false) }
    var showTrafficSettings by remember { mutableStateOf(false) }
    var incomingQuery by remember(entryId) { mutableStateOf("") }
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
    fun requestAction(action: DirectionsAction) {
        if (ui.starting || commandBusy) return
        val required = buildList {
            if ((action != DirectionsAction.PREVIEW || ui.originCurrent) && !hasFineLocation()) {
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
    fun closePlanner() { showPlanning = false; vm.directions.showLiveRoute(); onClose() }
    BackHandler(enabled = showPlanning, onBack = ::closePlanner)
    var previousSession by remember { mutableStateOf(nav.sessionId) }
    LaunchedEffect(nav.sessionId) {
        if (previousSession != null && nav.sessionId == null && ui.originCurrent) vm.directions.useCurrentOrigin()
        previousSession = nav.sessionId
    }
    val openedStartedCount = remember(entryId) { ui.startedCount }
    LaunchedEffect(ui.startedCount) { if (ui.startedCount > openedStartedCount) showPlanning = false }
    LaunchedEffect(entryId, incomingTarget) {
        vm.enter(entryId, initialStops, initialTransport)
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
        members = groupState.markers.map { com.atay.iz.integration.RouteMapMember(it.userId, it.name, it.latitude, it.longitude, it.delayed) },
        showPlanner = showPlanning,
        weatherLabel = weather.assessment?.samples?.firstOrNull()?.reading?.temperatureC?.let { "${it.toInt()}° · Hava" } ?: "Hava durumu",
        onSearch = {
            if (!showPlanning) vm.enter(System.nanoTime(), null, nav.sessionTransport ?: ui.transport)
            showPlanning = true
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
        onMute = { vm.navigation.setMuted(!nav.muted) }, onFinish = vm::finish,
        onLocate = { requestAction(DirectionsAction.CURRENT_ORIGIN) }, onPin = onPin,
    ) {
    PhoneDirectionsContent(
        ui = ui, nav = nav, points = points, commandBusy = commandBusy,
        onClose = ::closePlanner,
        chooseOrigin = { addingVia = false; pickerOrigin = true; showSource = true },
        chooseDestination = { addingVia = false; pickerOrigin = false; showSource = true },
        currentOrigin = { requestAction(DirectionsAction.CURRENT_ORIGIN) },
        transport = vm.directions::transport,
        preview = { requestAction(DirectionsAction.PREVIEW) }, start = { requestAction(DirectionsAction.START) },
        freeDrive = { requestAction(DirectionsAction.FREE_DRIVE) }, finish = vm::finish,
        mute = { vm.navigation.setMuted(!nav.muted) }, stopGuidance = vm.navigation::stopGuidance,
        showLive = vm.directions::showLiveRoute,
        trafficSettings = { showTrafficSettings = true },
        lastDestination = if (vm.navigation.lastRoute != null) vm::lastDestination else null,
        permissions = { permissions.launch(permissionNames()) },
        showMap = false, recordJourney = vm.directions::setRecordJourney, stopRecording = vm::stopRecording,
        addVia = { addingVia = true; pickerOrigin = false; showSource = true }, removeVia = vm.directions::removeVia,
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kaydı durdurmak yönlendirmeyi veya grup paylaşımını kapatmaz.")
            if (nav.recording) OutlinedButton({ sessionActions = false; vm.stopRecording() }, enabled = !commandBusy && !ui.starting) { Text("Kaydı durdur") }
            if (nav.guidance) OutlinedButton({ sessionActions = false; vm.navigation.stopGuidance() }, enabled = !commandBusy && !ui.starting) { Text("Yönlendirmeyi durdur") }
            Button({ sessionActions = false; vm.finish() }, enabled = !commandBusy && !ui.starting) { Text("Yolculuğu bitir") }
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





