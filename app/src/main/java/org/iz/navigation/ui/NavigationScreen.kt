package org.iz.navigation.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
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
import org.iz.navigation.navigation.NavigationStopSnapshot
import org.iz.navigation.navigation.PlannedBreakState
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

    val preparedRoutes get() = getApplication<IzApplication>().preparedRoutes
    private fun preparedSnapshot() = preparedRoutes.state.value.copy(pack = preparedRoutes.maps.state.value)
    private fun ownsPrepared(entryId: Long, token: PreparedRouteUiToken): Boolean =
        currentUiEntry == entryId && token == preparedRouteToken(entryId, directions.revisionToken,
            ui.value, navigation.state.value, preparedSnapshot())
    fun canPreparedAction(entryId: Long, token: PreparedRouteUiToken, action: PreparedRouteUiAction): Boolean =
        !commandBusy.value && ownsPrepared(entryId, token) && action in preparedRouteData(token, ui.value,
            navigation.state.value, preparedSnapshot(), false, preparedRoutes.mapReady()).allowedActions

    /** Commands share the existing VM lock and outlive closing the planner. No permission callback owns a stale start. */
    fun preparedAction(entryId: Long, token: PreparedRouteUiToken, action: PreparedRouteUiAction,
        selection: PreparedCorridorUiSelection? = null) {
        if (!canPreparedAction(entryId, token, action)) return
        if (action == PreparedRouteUiAction.SET_CORRIDOR && (selection == null ||
                selection.halfWidthMeters !in 50.0..1000.0)) {
            directions.reportMessage("Koridor genişliği yolun her yanında 50–1000 m olmalı.")
            return
        }
        val candidate = preparedRouteCandidate(ui.value, navigation.state.value)
        val inputRevision = directions.revisionToken
        val recordJourney = ui.value.recordJourney
        work.mutate {
            if (!ownsPrepared(entryId, token) || action !in preparedRouteData(token, ui.value,
                    navigation.state.value, preparedSnapshot(), false, preparedRoutes.mapReady()).allowedActions) return@mutate
            when (action) {
                PreparedRouteUiAction.PREPARE_ROUTE -> {
                    if (candidate == null || candidate != preparedRouteCandidate(ui.value, navigation.state.value)) return@mutate
                    preparedRoutes.prepareRoute(candidate, token.generation)
                }
                PreparedRouteUiAction.SET_CORRIDOR -> {
                    val corridor = selection ?: return@mutate
                    preparedRoutes.selectCorridor(org.iz.navigation.navigation.PreparedCorridorSelection(
                        corridor.fromMeters, corridor.toMeters, corridor.halfWidthMeters), token.generation)
                }
                PreparedRouteUiAction.USE_PREPARED -> {
                    preparedRoutes.start(token.generation, recordJourney) { ownsPrepared(entryId, token) && !ui.value.busy }
                    if (currentUiEntry == entryId && directions.revisionToken == inputRevision) directions.sessionStarted()
                }
                else -> {
                    val command = when (action) {
                        PreparedRouteUiAction.DOWNLOAD_MAP -> "download"
                        PreparedRouteUiAction.PAUSE_DOWNLOAD -> "pause"
                        PreparedRouteUiAction.RESUME_DOWNLOAD -> "resume"
                        PreparedRouteUiAction.CANCEL_DOWNLOAD -> "cancel"
                        PreparedRouteUiAction.DELETE_MAP -> "delete"
                        else -> return@mutate
                    }
                    check(preparedRoutes.mapCommand(token.generation, command)) {
                        "İşlem bu durumda başlatılamadı. Güncel harita durumunu kontrol et."
                    }
                }
            }
        }
    }

    val routeOperations get() = getApplication<IzApplication>().routeOperations
    private fun currentRouteOperationData(entryId: Long, locked: Boolean = commandBusy.value): RouteOperationsPanelData {
        val nav = navigation.state.value
        val owner = org.iz.navigation.navigation.RouteEditOwner.from(nav)
        return routeOperationsData(entryId, nav, navigation.edits.value, routeOperations.lookup.value,
            routeOperations.online.value, locked, owner?.let(navigation::remainingStopIndices).orEmpty())
    }
    private fun ownsRouteOperation(entryId: Long, token: RouteOperationsUiToken): Boolean =
        currentUiEntry == entryId && token == routeOperationsToken(entryId, navigation.state.value,
            navigation.edits.value, routeOperations.lookup.value)

    /** Explicit requests use cancellable read work; only confirm/pause/resume change navigation. */
    fun routeOperation(entryId: Long, token: RouteOperationsUiToken, action: RouteOperationUiAction,
        targetId: String? = null, requestId: String? = null, previewRouteId: String? = null) {
        if (!ownsRouteOperation(entryId, token) || action !in currentRouteOperationData(entryId).allowedActions) return
        suspend fun execute() {
            if (!ownsRouteOperation(entryId, token)) return
            val data = currentRouteOperationData(entryId, locked = false)
            if (action !in data.allowedActions) return
            val owner = org.iz.navigation.navigation.RouteEditOwner.from(navigation.state.value) ?: return
            when (action) {
                RouteOperationUiAction.AVOID -> {
                    val index = targetId?.toIntOrNull() ?: return
                    if (data.maneuvers.none { it.id == targetId }) return
                    navigation.previewEdit(RouteEditAction.AvoidManeuver(index), owner)
                }
                RouteOperationUiAction.SKIP -> {
                    val index = targetId?.toIntOrNull() ?: return
                    if (data.intermediateStops.none { it.id == targetId }) return
                    navigation.previewEdit(RouteEditAction.SkipStop(index), owner)
                }
                RouteOperationUiAction.LOOKUP_FUEL, RouteOperationUiAction.LOOKUP_REST ->
                    routeOperations.lookup(owner, if (action == RouteOperationUiAction.LOOKUP_FUEL)
                        org.iz.navigation.navigation.RouteStopLookupKind.FUEL else org.iz.navigation.navigation.RouteStopLookupKind.REST)
                RouteOperationUiAction.SELECT_POI -> {
                    val id = targetId ?: return
                    val request = requestId ?: return
                    if (data.poi?.let { it.requestId == request && it.results.any { result -> result.id == id } } != true) return
                    routeOperations.select(owner, request, id)
                }
                RouteOperationUiAction.CONFIRM -> {
                    val preview = navigation.edits.value.preview ?: return
                    if (preview.id != requestId || preview.route.id != previewRouteId || preview.owner != owner) return
                    check(navigation.commitEdit(owner, preview.id)) { "Rota durumu değişti. İşlemi yeniden seç." }
                }
                RouteOperationUiAction.CANCEL -> {
                    val id = requestId ?: return
                    if (data.pendingRequestId != id) return
                    val edit = navigation.edits.value
                    if (id == (edit.preview?.id ?: edit.requestId)) {
                        check(navigation.cancelEdit(owner, id)) { "Rota isteği değişti. Güncel durumu kontrol et." }
                    } else {
                        val lookup = routeOperations.lookup.value
                        if (lookup.owner == owner && lookup.requestId == id) routeOperations.cancelLookup()
                    }
                }
                RouteOperationUiAction.PAUSE, RouteOperationUiAction.RESUME -> {
                    val snapshot = NavigationStopSnapshot.from(navigation.state.value) ?: return
                    check(navigation.setPaused(snapshot, action == RouteOperationUiAction.PAUSE)) {
                        "Duraklama durumu değişti. Güncel durumu kontrol et."
                    }
                }
            }
        }
        if (action in setOf(RouteOperationUiAction.CANCEL, RouteOperationUiAction.PAUSE, RouteOperationUiAction.RESUME)) {
            work.newTarget()
            work.mutate { execute() }
        } else if (action == RouteOperationUiAction.CONFIRM) work.mutate { execute() }
        else work.read { current -> if (current()) execute() }
    }
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


    private var currentUiEntry: Long? = null
    private val inputs = linkedMapOf<Long, PhoneDirectionsInput>()
    private var retainedUiKeys: Set<Long>? = null
    private val initialPreviews = mutableSetOf<Long>()
    private val initialTargets = mutableSetOf<Long>()
    fun retainUiEntries(keys: Set<Long>) {
        retainedUiKeys = keys
        inputs.keys.retainAll(keys); initialPreviews.retainAll(keys); initialTargets.retainAll(keys)
    }
    fun takeInitialPreview(entryId: Long) = initialPreviews.add(entryId)
    fun takeInitialTarget(entryId: Long) = initialTargets.add(entryId)
    fun enter(entryId: Long, initialStops: List<RouteStop>?, transport: Transport?, initialPlan: SavedWeatherPlan? = null,
        restoring: Boolean = false, continuePreparation: Boolean = false) {
        if (currentUiEntry == entryId) return
        currentUiEntry?.takeIf { retainedUiKeys?.contains(it) != false }?.let { inputs[it] = directions.workingInput() }
        val retained = inputs[entryId]
        if (continuePreparation) directions.adoptUiEntry(entryId)
        else if (retained != null) { directions.adoptUiEntry(entryId); directions.restoreUiInput(retained) }
        else directions.enter(entryId, initialStops,
            transport ?: navigation.state.value.sessionTransport ?: navigation.state.value.journey?.transport,
            locateOnOpen = !restoring && !navigation.state.value.guidance, initialPlan = initialPlan)
        currentUiEntry = entryId
        while (inputs.size > 24) inputs.remove(inputs.keys.first())
        if (!restoring && retained == null && !continuePreparation && initialPlan == null &&
            navigation.state.value.sessionId == null && ui.value.originCurrent && !ui.value.locating) directions.useCurrentOrigin()
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

    fun resumePlannedBreak(entryId: Long, expected: NavigationStopSnapshot, expectedBreak: PlannedBreakState,
        stillCurrent: () -> Boolean) {
        fun ownsRequest(): Boolean {
            if (currentUiEntry != entryId || !stillCurrent()) return false
            val current = navigation.state.value
            val active = current.plannedBreak ?: return false
            return NavigationStopSnapshot.from(current) == expected &&
                active.stopIndex == expectedBreak.stopIndex && active.startedAt == expectedBreak.startedAt
        }
        if (commandBusy.value || ui.value.starting || !ownsRequest()) return
        work.mutate {
            if (ownsRequest()) navigation.resumePlannedBreak(expected)
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
    onStarted: () -> Unit = {},
    restoringEntry: Boolean = false,
    continuePreparation: Boolean = false,
    onPlannerOpened: () -> Unit = {},
    onMap: () -> Unit = onClose,
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.directions.refreshCredentials() }
    val nav by vm.navigation.state.collectAsStateWithLifecycle()
    val routeEdits by vm.navigation.edits.collectAsStateWithLifecycle()
    // Live recording and simulation intentionally publish position only, never a colored trail.
    val points = emptyList<TrackPoint>()
    val places by vm.places.collectAsStateWithLifecycle()
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val commandBusy by vm.commandBusy.collectAsStateWithLifecycle()
    val commandErrorVersion by vm.commandErrorVersion.collectAsStateWithLifecycle()
    val speech = remember(context) { org.iz.navigation.navigation.NavigationSpeech.get(context) }
    val speechStatus by speech.status.collectAsStateWithLifecycle()
    val openVoiceSettings = org.iz.navigation.ui.history.LocalUiVoiceSettingsAction.current
    var voiceNotice by remember(entryId) { mutableStateOf<String?>(null) }
    LaunchedEffect(speechStatus.readiness, speechStatus.requestFailure) {
        if (speechStatus.readiness == org.iz.navigation.navigation.EngineReadiness.READY && speechStatus.requestFailure == null) voiceNotice = null
    }
    val voiceControls: @Composable () -> Unit = {
        PhoneVoiceControls(speechStatus, nav.muted, voiceNotice,
            onTest = { voiceNotice = when (speech.testVoice()) {
                org.iz.navigation.navigation.SpeechTestResult.ACCEPTED -> "Ses denemesi istendi. Sesi duyduğunu kendin kontrol et."
                org.iz.navigation.navigation.SpeechTestResult.BUSY -> "Dönüş anonsu sürdüğü için bu ses denemesi başlatılmadı."
                org.iz.navigation.navigation.SpeechTestResult.UNAVAILABLE -> "Ses motoru hazır olmadığı için bu deneme başlatılmadı."
            } },
            onRetry = { voiceNotice = null; speech.retry() },
            onSettings = { voiceNotice = null; openVoiceSettings?.invoke() })
    }
    val uiHistory = org.iz.navigation.ui.history.LocalUiHistory.current
    org.iz.navigation.ui.history.UiEditOwner(org.iz.navigation.ui.history.UiEditState(
        org.iz.navigation.ui.history.LocalUiEntryKey.current + "/navigation-work", null, 0, 0, false, ui.starting || commandBusy),
        currentState = { org.iz.navigation.ui.history.UiEditState((uiHistory?.current?.key ?: "standalone") + "/navigation-work",
            null, 0, 0, false, vm.ui.value.starting || vm.commandBusy.value) }, discardUnsaved = {})
    val app = context.applicationContext as IzApplication
    val groupState by app.groups.state.collectAsStateWithLifecycle()
    val weather by app.weatherManager.state.collectAsStateWithLifecycle()
    var showPlanning by androidx.compose.runtime.saveable.rememberSaveable(entryId) { mutableStateOf(initiallyPlanning) }
    val dashboardEntry = org.iz.navigation.ui.history.LocalUiEntryKey.current
    val activeBreak = nav.plannedBreak
    val breakOwner = NavigationStopSnapshot.from(nav)
    val resumePlannedBreak: () -> Unit = {
        if (activeBreak != null && breakOwner != null) {
            vm.resumePlannedBreak(entryId, breakOwner, activeBreak) {
                (uiHistory?.current?.key ?: "standalone") == dashboardEntry
            }
        }
    }
    var showDashboard by remember(dashboardEntry, nav.sessionId) { mutableStateOf(false) }
    val dashboardOwner = DashboardOwner(dashboardEntry, nav.sessionId)
    val dashboardRouteRevision = nav.routeRevision
    val dashboardStillCurrent: () -> Boolean = {
        val currentEntry = uiHistory?.current?.key ?: "standalone"
        val currentNavigation = vm.navigation.state.value
        dashboardOwnerCurrent(dashboardOwner, DashboardOwner(currentEntry, currentNavigation.sessionId)) &&
            currentNavigation.routeRevision == dashboardRouteRevision && !vm.commandBusy.value && !vm.ui.value.starting
    }
    val dashboardAnalysis by remember(app) { org.iz.navigation.power.PostJourneyAnalysis.get(app).state }.collectAsStateWithLifecycle()
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
    var trackReplacementDialog by remember(entryId, nav.trackReplacementKey(), vm.directions.revisionToken) {
        mutableStateOf<String?>(null)
    }
    fun hasFineLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val openPermissions = org.iz.navigation.ui.history.LocalUiDirectionsPermissions.current
    fun requestPermissions(names: Array<String>, forAction: Boolean): Boolean {
        val requestedRevision = vm.directions.revisionToken
        fun rejectOwnRequest() {
            if (forAction && vm.permissionGate.isPendingFor(entryId, requestedRevision))
                vm.permissionGate.consume(entryId, requestedRevision, false)
        }
        val callback = org.iz.navigation.ui.history.UiPermissionReturn(onResult = {
            val precise = ContextCompat.checkSelfPermission(vm.getApplication<Application>(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!precise && forAction && vm.permissionGate.isPendingFor(entryId, requestedRevision))
                vm.directions.reportMessage("Güncel konum için hassas konum izni gerekli. İzinleri ayarlayıp yeniden dene.")
            if (forAction && vm.permissionGate.isPendingFor(entryId, requestedRevision))
                vm.permissionGate.consume(entryId, vm.directions.revisionToken, precise)?.let(vm::perform)
        }, onStale = ::rejectOwnRequest)
        val launched = try { openPermissions?.invoke(names, callback) == true }
        catch (error: Exception) { vm.directions.reportMessage(error.message ?: "İzin ekranı açılamadı."); false }
        if (!launched) rejectOwnRequest()
        return launched
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
        else if (vm.permissionGate.request(action, entryId, vm.directions.revisionToken)) requestPermissions(required.toTypedArray(), forAction = true)
    }

    fun requestPreparedStart(token: PreparedRouteUiToken) {
        if (!vm.canPreparedAction(entryId, token, PreparedRouteUiAction.USE_PREPARED)) return
        val route = vm.preparedRoutes.state.value.route ?: return
        val required = buildList {
            if (!hasFineLocation()) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (vm.ui.value.recordJourney && route.transport.supportsSteps &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (required.isEmpty()) {
            vm.preparedAction(entryId, token, PreparedRouteUiAction.USE_PREPARED)
            return
        }
        val callback = org.iz.navigation.ui.history.UiPermissionReturn(onResult = {
            if (vm.canPreparedAction(entryId, token, PreparedRouteUiAction.USE_PREPARED)) {
                val precise = ContextCompat.checkSelfPermission(vm.getApplication<Application>(),
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (precise) vm.preparedAction(entryId, token, PreparedRouteUiAction.USE_PREPARED)
                else vm.directions.reportMessage("Hazır rotayı başlatmak için hassas konum izni gerekli. İzinleri ayarlayıp yeniden dene.")
            }
        }, onStale = {})
        try {
            if (openPermissions?.invoke(required.toTypedArray(), callback) != true)
                vm.directions.reportMessage("İzin ekranı açılamadı. Hazır rota başlatılmadı.")
        } catch (error: Exception) {
            vm.directions.reportMessage(error.message ?: "İzin ekranı açılamadı. Hazır rota başlatılmadı.")
        }
    }
    fun select(stop: RouteStop) {
        if (addingVia) { vm.directions.addVia(stop); addingVia = false }
        else if (pickerOrigin) vm.directions.setOrigin(stop) else vm.directions.setDestination(stop)
    }
    val uiExit = org.iz.navigation.ui.history.LocalUiExitGuard.current
    fun closePlanner() { val exit = { showPlanning = false; onClose() }; if (uiExit == null) exit() else uiExit.requestExit(exit) }
    fun goToMap() { val exit = { showPlanning = false; onMap() }; if (uiExit == null) exit() else uiExit.requestExit(exit) }
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
    BackHandler(enabled = showDashboard) { showDashboard = false }
    var previousSession by remember { mutableStateOf(nav.sessionId) }
    LaunchedEffect(nav.sessionId) {
        if (previousSession != null && nav.sessionId == null && ui.originCurrent) vm.directions.useCurrentOrigin()
        previousSession = nav.sessionId
    }
    val openedStartedCount = remember(entryId) { ui.startedCount }
    LaunchedEffect(ui.startedCount) { if (ui.startedCount > openedStartedCount) { showPlanning = false; onStarted() } }
    LaunchedEffect(entryId, incomingTarget, initialPlan) {
        vm.enter(entryId, initialStops, initialTransport, initialPlan, restoringEntry, continuePreparation)
        val firstPreview = vm.takeInitialPreview(entryId)
        if (!restoringEntry && initialPlan != null && firstPreview) {
            requestAction(DirectionsAction.PREVIEW)
        }
        val firstTarget = vm.takeInitialTarget(entryId)
        incomingTarget?.takeIf { !restoringEntry && firstTarget }?.let { target ->
            vm.target(target)
            if (target.coordinate == null && target.query.isNotBlank()) {
                incomingQuery = target.query
                pickerOrigin = false
                showSearch = true
            }
        }
    }

    val dashboardSpeed = org.iz.navigation.speed.roadSpeedPresentation(nav.roadSpeed)
    val expectedDashboardWeatherOwner = dashboardWeatherOwner(nav.sessionId, nav.journey?.id, nav.recording, nav.simulation)
    val dashboardWeatherMatches = expectedDashboardWeatherOwner != null && weather.journeyId == expectedDashboardWeatherOwner &&
        nav.route?.id == weather.route?.id
    val dashboardProgress = dashboardRemaining(nav.guidance, nav.route != null, nav.gpsStale,
        nav.progress?.remainingMeters, nav.progress?.remainingSeconds)
    // Only this new presenter withholds unverified progress; legacy instruction callers stay unchanged.
    val dashboardManeuver = nav.route?.let { route ->
        nav.progress?.takeIf {
            dashboardProgress != null && !nav.arrived && !it.arrived && !it.offRoute &&
                it.elapsedSeconds.isFinite() && it.elapsedSeconds >= 0.0 &&
                it.distanceFromRouteMeters.isFinite() && it.distanceFromRouteMeters >= 0.0 &&
                it.nextManeuverDistanceMeters.isFinite() && it.nextManeuverDistanceMeters >= 0.0
        }?.let { route.maneuvers.getOrNull(it.maneuverIndex)?.instruction?.takeIf(String::isNotBlank) }
    }
    val dashboardWarnings = buildList {
        if (ui.busy || ui.locating || nav.loading || commandBusy) add("İşlem sürüyor; mevcut durum korunuyor.")
        if (nav.gpsStale) add("GPS konumu bekleniyor; son konum güncel değil.")
        if (!nav.locationActive && !nav.simulation && (nav.sessionId != null || nav.guidance || nav.recording)) add("Konum bağlantısı kuruluyor.")
        phoneVoiceFault(speechStatus, nav.guidance, nav.muted)?.let(::add)
        nav.message?.let(::add)
        ui.message?.let(::add)
        if (showPlanning && ui.preview != null) add("Haritadaki rota önizlemedir; canlı rota ilerlemesi değildir.")
    }.distinct()
    val dashboardTemperature = weather.assessment?.samples?.firstOrNull()?.reading?.temperatureC?.takeIf { it.isFinite() }
    val dashboardWeatherText = when {
        !dashboardWeatherMatches -> if (expectedDashboardWeatherOwner == null) "Etkin oturuma bağlı hava verisi yok." else "Bu oturumun hava verisi bekleniyor."
        weather.status == RideWeatherStatus.OFF -> "Hava takibi kapalı."
        weather.status == RideWeatherStatus.LOADING -> "Tahmin hazırlanıyor."
        weather.status == RideWeatherStatus.ERROR -> "Hava verisi alınamadı."
        weather.refreshFailed -> "Önbellekteki tahmin; son güncelleme başarısız."
        weather.gpsStale -> "Son tahmin; konum güncel değil."
        else -> dashboardTemperature?.let { "${it.toInt()} °C · Hazır tahmin" } ?: "Hazır tahminde sıcaklık bilgisi yok."
    }
    val dashboardGroupText = when {
        !groupState.configured -> "Grup hizmeti yapılandırılmadı."
        groupState.groupId == null -> "Etkin grup yok."
        !groupState.connected -> "Grup bağlantısı yok."
        groupState.sharing -> "Grup bağlı · Konum paylaşımı açık."
        else -> "Grup bağlı · Konum paylaşımı kapalı."
    }
    val dashboardGroupDetail = listOfNotNull(
        if (groupState.configured && groupState.groupId != null) "${groupState.members.size} üye · ${groupState.markers.count { it.delayed }} gecikmiş konum" else null,
        groupState.message).joinToString("\n").ifBlank { null }
    val dashboardViewData = NavigationDashboardViewData(
        speed = if (nav.roadSpeed.visible && !nav.gpsStale && !nav.simulation) dashboardSpeed.ownText else "—",
        speedLimit = if (nav.roadSpeed.visible && !nav.gpsStale && !nav.simulation) dashboardSpeed.limitText else "—",
        limitSource = if (nav.roadSpeed.visible && !nav.gpsStale && !nav.simulation) dashboardSpeed.sourceText else "Güncel sınır bilgisi yok",
        status = (if (nav.arrived) {
            if (nav.recording) "Varış noktasına ulaştın. Kayıt devam ediyor. · " else "Varış noktasına ulaştın. · "
        } else "") + when {
            nav.simulation -> "Simülasyon · Günlüğe kaydedilmez"
            nav.guidance && nav.recording -> "Yol tarifi açık · Kayıt açık"
            nav.guidance -> "Yol tarifi açık · Kayıt kapalı"
            nav.recording -> "Yolculuk kaydı açık · Yönlendirme kapalı"
            else -> "Yolculuk kaydı ve yönlendirme kapalı"
        },
        maneuver = dashboardManeuver,
        remaining = dashboardProgress?.let { String.format(java.util.Locale.forLanguageTag("tr-TR"),
            "Kalan %.1f km · %.0f dk", it.meters / 1000.0, kotlin.math.ceil(it.seconds / 60.0)) } ?: if (nav.guidance) "Güncel kalan yol ve süre bilgisi yok." else null,
        warnings = dashboardWarnings,
        weather = dashboardWeatherText,
        weatherDetail = if (dashboardWeatherMatches) listOfNotNull(
            if (weather.gpsStale) "Konum güncel değil; tahmin mevcut konumu doğrulamıyor." else null,
            if (weather.refreshFailed) "Son hazır tahmin korunuyor." else null,
            weather.message).joinToString("\n").ifBlank { null } else null,
        group = dashboardGroupText,
        groupDetail = dashboardGroupDetail,
        analysis = dashboardAnalysis.label,
        guidance = nav.guidance,
        muted = nav.muted,
        sessionActive = nav.sessionId != null || nav.guidance || nav.recording || nav.locationActive,
    )
    val dashboardContent: @Composable () -> Unit = {
        NavigationDashboardPanel(dashboardViewData, commandBusy || ui.starting,
            onClose = { showDashboard = false },
            onMute = {
                if (dashboardStillCurrent() && vm.navigation.state.value.guidance) vm.navigation.setMuted(!vm.navigation.state.value.muted,
                    stillCurrent = { dashboardStillCurrent() && vm.navigation.state.value.guidance })
            },
            onManage = {
                if (dashboardStillCurrent()) sessionActions = true
            })
    }
    NavigationHomeContent(ui = ui, nav = nav, points = points,
        shortcuts = shortcuts, onShortcut = { kind -> showPlanning = true; onPlannerOpened(); vm.shortcut(kind) },
        members = groupState.markers.map { org.iz.navigation.integration.RouteMapMember(it.userId, it.name, it.latitude, it.longitude, it.delayed) },
        showPlanner = showPlanning, voiceFault = phoneVoiceFault(speechStatus, nav.guidance, nav.muted),
        editPreview = routeEdits.preview?.takeIf { it.owner.matches(nav) },
        showDashboard = showDashboard, onDashboard = { if (dashboardStillCurrent()) showDashboard = true }, dashboard = dashboardContent,
        weatherLabel = weather.assessment?.samples?.firstOrNull()?.reading?.temperatureC?.let { "${it.toInt()}° · Hava" } ?: "Hava durumu",
        onSearch = {
            val current = vm.navigation.state.value
            openPhoneDirectionsPanel(current, showPlanning,
                { vm.enter(System.nanoTime(), null, current.sessionTransport ?: vm.ui.value.transport) }, { showPlanning = true; onPlannerOpened() })
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
        onMute = { vm.navigation.setMuted(!nav.muted) }, onManageSession = { showPlanning = true; onPlannerOpened() },
        onLocate = { requestAction(DirectionsAction.CURRENT_ORIGIN) }, onPin = onPin,
        onResumePlannedBreak = resumePlannedBreak,
    ) {
    PhoneDirectionsContent(
        ui = ui, nav = nav, points = points, commandBusy = commandBusy, commandErrorVersion = commandErrorVersion, voiceControls = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                voiceControls()
                PreparedRouteControls(vm, entryId, ui, nav, commandBusy, speechStatus, ::requestPreparedStart)
                RouteOperationsControls(vm, entryId, nav, commandBusy, weather)
            }
        },
        onClose = ::closePlanner, onMap = ::goToMap,
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
        permissions = { requestPermissions(permissionNames(), forAction = false) },
        showMap = false, recordJourney = vm.directions::setRecordJourney, stopRecording = { requestEnd(PhoneSessionEnd.RECORDING) },
        addVia = { addingVia = true; pickerOrigin = false; showSource = true }, removeVia = vm.directions::removeVia,
        onPlannedBreakMinutesChanged = vm.directions::setPlannedBreakMinutes,
        onResumePlannedBreak = resumePlannedBreak,
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
            voiceControls()
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
    PhoneEndConfirmation(request.title, request.explanation, request.action.label, locked, onDismiss,
        onConfirm = { if (request.matches(current)) onConfirm(request) })
}

/** Common consequence/cancel UI; callers retain their own fresh identity check before dispatch. */
@Composable
internal fun PhoneEndConfirmation(title: String, explanation: String, confirmLabel: String, locked: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(explanation) } },
        confirmButton = {
            TextButton(onClick = { if (!locked) onConfirm() }, enabled = !locked,
                modifier = Modifier.testTag("confirm-session-end")) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel-session-end")) { Text("Vazgeç") } },
    )
}
