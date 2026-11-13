@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import org.iz.navigation.ui.history.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withResumed
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import org.iz.navigation.BuildConfig
import org.iz.navigation.data.*
import org.iz.navigation.placeinfo.*
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.navigation.navigationReplacementKey
import org.iz.navigation.weather.RoutePreferences
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.SavedWeatherPlan
import org.iz.navigation.integration.*
import org.iz.navigation.ui.backup.BackupExportPhase
import org.iz.navigation.ui.backup.BackupExportProgress
import org.iz.navigation.ui.backup.DiaryBackupExportOperations
import org.iz.navigation.ui.backup.message
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.tracking.TrackingPolicy
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.iz.navigation.integration.osm.OsmAuthManager
import org.iz.navigation.integration.osm.OsmContributionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun date(value: Long) = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(value))
private fun duration(value: Long): String { val m = value.coerceAtLeast(0) / 60_000; return if (m >= 60) "${m / 60} sa ${m % 60} dk" else "$m dk" }
private fun countdown(value: Long): String { val seconds = (value.coerceIn(0L, TrackingPolicy.AUTOMATIC_WINDOW_MS) + 999) / 1000; return String.format(Locale.forLanguageTag("tr-TR"), "%02d:%02d", seconds / 60, seconds % 60) }
private fun journeyDistance(journey: Journey, state: DiaryState): String =
    state.prepared(journey)?.stats?.distanceMeters?.let {
        String.format(Locale.forLanguageTag("tr-TR"), "%.1f km", it / 1000)
    } ?: if (journey.status == JourneyStatus.TEMPORARY) "500 metre için ölçülüyor" else state.analysisLabel
private data class PhotoTarget(val journeyId: String? = null, val visitId: String? = null)
private data class TransportAction(val journeyId: String? = null, val split: Boolean = false, val suggestion: Transport = Transport.WALK)
private data class Deletion(val text: String, val action: suspend () -> Unit)

@Composable
fun DiaryApp(incoming: Intent?, consumeIntent: () -> Unit, vm: DiaryViewModel = viewModel(), incomingIsCold: Boolean = false) {
    val context = LocalContext.current
    val resultOwner: UiResultViewModel = viewModel()
    val featureOwners: UiEntryViewModels = viewModel()
    val savedStateHost = context.findActivity() as? androidx.savedstate.SavedStateRegistryOwner
        ?: error("Phone entry state requires its Activity saved-state owner")
    DisposableEffect(featureOwners, savedStateHost) {
        featureOwners.attachSavedStateHost(savedStateHost)
        onDispose { featureOwners.detachSavedStateHost(savedStateHost) }
    }
    val featureFactory = remember(context.applicationContext) { ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as android.app.Application) }
    val state by vm.state.collectAsStateWithLifecycle()
    val informationEdits by vm.repository.mapEdits.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val media = remember { MediaStoreHelper(context) }
    val settings = remember { TrackerSettings(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var initialIntentPending by remember { mutableStateOf(incoming != null) }
    val history = rememberUiHistory()
    val entries by history.entries.collectAsStateWithLifecycle()
    val uiEntry = entries.last()
    var restoringUiInput by remember(uiEntry.key) { mutableStateOf(history.isRestoredEntry(uiEntry.key) && uiEntry.entityId != null && uiEntry.page in setOf(UiPage.DIRECTIONS, UiPage.GPX)) }
    val exitGuard = remember { UiExitGuard() }
    val navigator = remember(history, exitGuard) { UiHistoryNavigator(history, exitGuard) }
    val browseHolder = rememberSaveableStateHolder()
    val knownBrowseKeys = remember { mutableSetOf<String>() }
    LaunchedEffect(entries.map { it.key }) {
        val retained = entries.map { it.key }.toSet()
        (knownBrowseKeys - retained).forEach { browseHolder.removeState(it); browseHolder.removeState("list/$it"); browseHolder.removeState("gpx/$it") }
        knownBrowseKeys.clear(); knownBrowseKeys.addAll(retained)
        featureOwners.retain(retained)
        resultOwner.registrations.retainEntries(retained)
        resultOwner.deliveredCommunityTargets.retainAll(retained)
    }
    var tab by navigator.tab()
    var menuOpen by rememberUiEntryState<Boolean>(history) { false }
    var groupPage by navigator.pageFlag(UiPage.GROUP)
    val groups = (context.applicationContext as org.iz.navigation.IzApplication).groups
    val groupState by groups.state.collectAsStateWithLifecycle()
    var selectedJourney by navigator.selected(UiPage.JOURNEY)
    var selectedPlace by navigator.selected(UiPage.PLACE)
    val transportState = rememberUiEntryState<TransportAction?>(history) { null }
    var transportAction by transportState
    val addPlaceState = rememberUiEntryState<Boolean>(history) { false }
    var addPlace by addPlaceState
    var targetJourney by rememberUiEntryState<String?>(history) { null }
    var pin by rememberUiEntryState<GeoCoordinate?>(history) { null }
    var osmSelection by rememberUiEntryState<SelectedOsmPlace?>(history) { null }
    var informationSelection by rememberSaveable(stateSaver = PlaceInformationSelectionSaver) { mutableStateOf<SelectedOsmPlace?>(null) }
    var informationOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val informationScroll = key(informationSelection?.osmRef, informationOwner) { rememberScrollState() }
    val photoTargetState = rememberUiEntryState<PhotoTarget?>(history) { null }
    var photoTarget by photoTargetState
    var editJourney by rememberUiEntryState<Journey?>(history) { null }
    var editVisit by rememberUiEntryState<Visit?>(history) { null }
    var editPlace by rememberUiEntryState<Place?>(history) { null }
    var editPhoto by rememberUiEntryState<Photo?>(history) { null }
    var missingPhotoMetadata by rememberUiEntryState<List<Photo>>(history) { emptyList() }
    var photosToShare by rememberUiEntryState<List<Photo>?>(history) { null }
    var searchOsm by rememberUiEntryState<Boolean>(history) { false }
    var contributionsPage by navigator.pageFlag(UiPage.CONTRIBUTIONS)
    var communityPage by navigator.pageFlag(UiPage.COMMUNITY)
    var communityMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var communityAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var contributionHub by navigator.pageFlag(UiPage.OSM_ACTIONS)
    var mapEditsPage by navigator.pageFlag(UiPage.MAP_EDITS)
    var surveyPage by navigator.pageFlag(UiPage.OSM_SURVEY)
    var correctionPicker by rememberUiEntryState<Boolean>(history) { false }
    val correctionTargetState = rememberUiEntryState<Place?>(history) { null }
    val mapEditCoordinateState = rememberUiEntryState<GeoCoordinate?>(history) { null }
    val mapEditCoordinate by mapEditCoordinateState
    val correctionTarget by correctionTargetState
    var weatherPage by navigator.pageFlag(UiPage.WEATHER)
    var navigationPage by navigator.pageFlag(UiPage.DIRECTIONS)
    val navigationEntryId = uiEntry.key.removePrefix("entry-").toLongOrNull() ?: 0L
    var continueNavigationPreparation by remember { mutableStateOf(false) }
    var navigationStops by remember { mutableStateOf<List<org.iz.navigation.weather.RouteStop>?>(null) }
    var navigationTransport by remember { mutableStateOf<Transport?>(null) }
    var navigationTarget by remember { mutableStateOf<org.iz.navigation.car.NavigationTarget?>(null) }
    var navigationShared by rememberSaveable(stateSaver = SharedMapInputSaver) {
        mutableStateOf<org.iz.navigation.car.SharedMapInput?>(null)
    }
    var navigationDashboardRequest by remember { mutableIntStateOf(0) }
    var navigationLayersRequest by remember { mutableIntStateOf(0) }
    var navigationInitialPlan by remember { mutableStateOf<SavedWeatherPlan?>(null) }
    var libraryOpen by navigator.pageFlag(UiPage.ROUTES)
    var collectionsOpen by navigator.pageFlag(UiPage.COLLECTIONS)
    var gpxPage by navigator.pageFlag(UiPage.GPX)
    var routeToSave by rememberUiEntryState<SavedRoutePlan?>(history) { null }
    var libraryBusy by resultOwner.libraryBusy
    var gpxStarting by resultOwner.gpxStarting
    var gpxPermissionPending by remember { mutableStateOf(false) }
    val gpxPermissionGate = remember { GpxStartPermissionGate() }
    val library = remember { TravelLibraryRepository(context) }
    val destinationShortcuts by library.destinationShortcuts.collectAsStateWithLifecycle(initialValue = emptyList())
    val shortcutAssignment = uiEntry.shortcutAssignmentKind()
    val shortcutScope = rememberCoroutineScope()
    var shortcutError by remember(uiEntry.key) { mutableStateOf<String?>(null) }
    var shortcutLocation by remember(uiEntry.key) { mutableStateOf<Job?>(null) }
    val gpxOwnerKey = entries.lastOrNull { it.page == UiPage.GPX }?.key ?: "map"
    val gpxImports = featureOwners.get("gpx", gpxOwnerKey, GpxImportViewModel::class.java, featureFactory)
    val importedGpx by gpxImports.state.collectAsStateWithLifecycle()
    val navigation = (context.applicationContext as org.iz.navigation.IzApplication).navigation
    val navigationState by navigation.state.collectAsStateWithLifecycle()
    var contributionPicker by rememberUiEntryState<Boolean>(history) { false }
    var contributionLocation by rememberUiEntryState<SelectedOsmPlace?>(history) { null }
    var contributionPlaceId by rememberUiEntryState<String?>(history) { null }
    var contributionEditId by rememberSaveable { mutableStateOf<String?>(null) }
    var contributionEdit by rememberUiEntryState<ContributionDraft?>(history, onDrop = { contributionEditId = null }) { null }
    var contributionBusy by resultOwner.contributionBusy
    var acknowledgedContribution by remember { mutableStateOf<ContributionDraft?>(null) }
    var gpxJourney by rememberUiEntryState<Journey?>(history) { null }
    var pendingGpxId by remember { mutableStateOf<String?>(null) }
    var preparingGpx by resultOwner.preparingGpx
    val gpxStore = remember { PendingGpxStore(File(context.noBackupFilesDir, "pending-gpx")) }
    val osmAuth = remember { OsmAuthManager.get(context) }
    val authState by osmAuth.state.collectAsStateWithLifecycle()
    val contributionAccountId = authState.user?.id?.takeUnless { authState.busy }
    val contributionController = remember { OsmContributionController(vm.repository, osmAuth) }
    var deletion by rememberUiEntryState<Deletion?>(history) { null }
    val detectionState = rememberUiEntryState<Boolean>(history) { false }
    var showDetection by detectionState
    var refresh by remember { mutableIntStateOf(0) }
    var restoreUri by rememberUiEntryState<Uri?>(history) { null }
    var backupImportBusy by resultOwner.backupBusy
    val backupExport = resultOwner.backupExport
    val backup = remember(context.applicationContext, vm.repository) {
        DiaryBackupExportOperations(context.applicationContext, DiaryBackup(context.applicationContext, vm.repository))
    }
    val backupBusy = backupImportBusy || backupExport.busy
    var showBackupOptions by rememberUiEntryState<Boolean>(history) { false }
    var includeHealthBackup by remember { mutableStateOf(false) }
    val healthManager = remember { (context.applicationContext as org.iz.navigation.IzApplication).healthManager }
    val active = state.active(now)
    fun executeEditor(message: String, action: suspend () -> Unit) {
        if (resultOwner.formBusy.value) return
        resultOwner.formBusy.value = true
        vm.execute(message) { try { action() } finally { resultOwner.formBusy.value = false } }
    }
    var pendingDiaryEnd by remember { mutableStateOf<DiarySessionEndTarget?>(null) }
    var diaryEndBusy by remember { mutableStateOf(false) }
    val diaryEndScope = rememberCoroutineScope()
    val diaryEndWork = remember(diaryEndScope, vm) { PhoneNavigationWork(diaryEndScope,
        { diaryEndBusy = it }, { vm.message(it.message ?: "İşlem tamamlanamadı. Yeniden deneyebilirsin.") }) }

    fun openDirections(stops: List<RouteStop>? = null, transport: Transport? = active?.transport,
        plan: SavedWeatherPlan? = null, sourcePlanId: String? = null) {
        continueNavigationPreparation = false
        navigationStops = stops
        navigationTransport = transport
        navigationInitialPlan = plan
        navigationTarget = null
        navigationShared = null
        history.push(UiPage.DIRECTIONS, sourcePlanId)
        groupPage = false
        weatherPage = false
        communityPage = false
        selectedJourney = null
        selectedPlace = null
        contributionsPage = false
        libraryOpen = false; collectionsOpen = false; gpxPage = false
    }

    fun openGpx(track: ImportedTrack? = null) {
        if (history.current.page == UiPage.GPX && track == null) return
        gpxPermissionGate.cancel(); gpxPermissionPending = false
        val entry = history.push(UiPage.GPX, track?.id)
        featureOwners.get("gpx", entry.key, GpxImportViewModel::class.java, featureFactory).show(track)
        groups.dismissScreenRequest()
    }
    fun prepareSaveRoute(stops: List<RouteStop>, transport: Transport, preferences: RoutePreferences,
        originCurrent: Boolean, speed: Double?) {
        if (stops.size !in 2..org.iz.navigation.weather.RouteStopLimits.MAX_PLANNED_STOPS) { vm.message("Kaydetmek için başlangıç ve varış dahil en fazla 20 durak seç."); return }
        routeToSave = SavedRoutePlan(name = "${stops.first().label} → ${stops.last().label}".take(300),
            stops = stops.toList(), transport = transport, preferences = preferences,
            originUsesCurrentLocation = originCurrent, travelSpeedKmh = speed)
    }

    fun phoneModel(entry: UiEntry): PhoneNavigationViewModel {
        val ownerKey = if (entry.page == UiPage.MAP || entry.parentKey == "map" && entry.entityId == null) "map" else entry.key
        val model = featureOwners.get("directions", ownerKey, PhoneNavigationViewModel::class.java, featureFactory)
        featureOwners.onRetain("directions", ownerKey) { keys -> model.retainUiEntries(keys.map { it.removePrefix("entry-").toLongOrNull() ?: 0L }.toSet()) }
        model.retainUiEntries(entries.map { it.key.removePrefix("entry-").toLongOrNull() ?: 0L }.toSet())
        return model
    }
    fun openAddPlace(journeyId: String? = active?.id, location: GeoCoordinate? = null) {
        targetJourney = journeyId; pin = location; osmSelection = null; addPlace = true
    }
    var trackExportOwner by remember { mutableStateOf<UiAsyncOwner?>(null) }
    fun captureResultOwner() = if (history.current.key == uiEntry.key) exitGuard.captureAsyncOwner(history.current.key) else null
    fun accepts(owner: UiAsyncOwner?) = exitGuard.acceptsAsyncOwner(owner, history.current.key)
    fun assignShortcut(kind: DestinationShortcutKind, save: suspend () -> Unit) {
        if (resultOwner.formBusy.value || history.current.shortcutAssignmentKind() != kind) return
        val owner = captureResultOwner() ?: return
        resultOwner.formBusy.value = true
        shortcutError = null
        shortcutLocation?.cancel()
        shortcutScope.launch {
            try {
                if (!accepts(owner)) return@launch
                save()
                if (accepts(owner)) {
                    addPlace = false
                    exitGuard.update(null)
                    history.toMap()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (accepts(owner)) shortcutError = (error as? IllegalArgumentException)?.message
                    ?: "Adres kaydedilemedi. Yeniden deneyebilirsin."
            } finally {
                resultOwner.formBusy.value = false
            }
        }
    }
    val launcherCapture: () -> UiAsyncOwner? = {
        val actual = history.current.key
        if (actual == uiEntry.key) exitGuard.captureAsyncOwner(actual) else null
    }
    fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun completeGpxStart() {
        val precise = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val request = try { gpxPermissionGate.consume(precise, navigation.state.value.navigationReplacementKey()) }
        catch (error: IllegalStateException) { vm.message(error.message ?: "GPX takibini yeniden onayla."); return }
        if (request == null) {
            if (!precise) vm.message("GPX takibi için hassas konum izni gerekli. İzinleri ayarlayıp yeniden dene.")
            return
        }
        gpxStarting = true
        vm.execute {
            try {
                navigation.startTrackFollow(request.track, request.selection, request.transport, request.replaceExisting, request.expectedReplacementKey)
                exitGuard.update(null); history.toMap()
            }
            finally { gpxStarting = false }
        }
    }
    suspend fun importStoredPhoto(uri: Uri, target: PhotoTarget, entry: String) {
        val photo = media.importPhoto(uri, target.journeyId, target.visitId)
        try { vm.repository.savePhoto(photo) }
        catch (e: Exception) { vm.repository.photoFile(photo.relativePath).delete(); throw e }
        if (history.current.key == entry && (photo.takenAt == null || photo.latitude == null)) missingPhotoMetadata = missingPhotoMetadata + photo
    }
    val permissions = rememberUiResultLauncher(ActivityResultContracts.RequestMultiplePermissions(), resultOwner.requests,
        "permissions", launcherCapture) { owner, _ ->
        if (accepts(owner)) { refresh++; vm.message("İzinler güncellendi. İlgili işlemi yeniden seçebilirsin.") }
    }
    val gpxPermissions = rememberUiResultLauncher(ActivityResultContracts.RequestMultiplePermissions(), resultOwner.requests,
        "gpx-permissions", launcherCapture) { owner, _ ->
        gpxPermissionPending = false
        if (accepts(owner) && gpxPage) completeGpxStart() else gpxPermissionGate.cancel()
    }
    LaunchedEffect(gpxPage) { if (!gpxPage) { gpxPermissionGate.cancel(); gpxPermissionPending = false } }
    val gallery = rememberUiResultLauncher(ActivityResultContracts.PickMultipleVisualMedia(20), resultOwner.requests,
        "gallery", launcherCapture) { owner, uris ->
        val payload = resultOwner.takePayload("gallery", owner) as? UiResultPayload.Photos
        val originalTarget = payload?.let { PhotoTarget(it.journeyId, it.visitId) }
        if (accepts(owner) && originalTarget != null && uris.isNotEmpty()) vm.execute("Fotoğraflar günlüğüne eklendi.") {
            for (uri in uris) importStoredPhoto(uri, originalTarget, owner!!.entryKey)
        }
        if (accepts(owner) && payload != null && photoTargetState.revision == payload.modalRevision && photoTarget == originalTarget) photoTarget = null
    }
    fun cleanupCamera(owner: UiAsyncOwner) {
        val payload = resultOwner.takePayload("camera", owner) as? UiResultPayload.Camera ?: return
        runCatching { media.deletePendingCapture(Uri.parse(payload.uri)) }
    }
    val camera = rememberUiResultLauncher(ActivityResultContracts.TakePicture(), resultOwner.requests,
        "camera", launcherCapture, onLaunchFailure = ::cleanupCamera) { owner, taken ->
        val payload = resultOwner.takePayload("camera", owner) as? UiResultPayload.Camera
        val accepted = accepts(owner)
        if (payload != null) vm.execute(if (taken && accepted) "Fotoğraf günlüğüne eklendi." else null) {
            val uri = Uri.parse(payload.uri)
            try { if (taken && accepted) importStoredPhoto(uri, PhotoTarget(payload.journeyId, payload.visitId), owner!!.entryKey) }
            finally { media.deletePendingCapture(uri) }
        }
    }
    val exportBackup = rememberUiResultLauncher(ActivityResultContracts.CreateDocument("application/zip"), resultOwner.requests,
        "backup-export", launcherCapture) { owner, uri ->
        backupExport.onPickerResult(owner, uri, accepts(owner))
    }
    val backupLifecycle = LocalLifecycleOwner.current.lifecycle
    val currentExportLauncher by rememberUpdatedState(exportBackup)
    LaunchedEffect(backupExport, backup) { backupExport.initialize(backup) }
    LaunchedEffect(backupExport, uiEntry.key) { backupExport.cancelIfOwnerLeft(uiEntry.key) }
    LaunchedEffect(backupExport, backupExport.phase, backupLifecycle) {
        if (backupExport.phase == BackupExportPhase.READY) {
            backupLifecycle.withResumed {
                backupExport.openPicker { owner, title -> currentExportLauncher.launchWithOwner(owner, title) }
            }
        }
    }
    LaunchedEffect(backupExport, vm) { backupExport.notices.collect { vm.message(it.message()) } }
    fun cleanupTrackExport(owner: UiAsyncOwner) {
        val payload = resultOwner.takePayload("gpx-export", owner) as? UiResultPayload.TrackExport ?: return
        vm.execute { withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { gpxStore.remove(payload.id) } }
    }
    val exportGpx = rememberUiResultLauncher(ActivityResultContracts.CreateDocument("application/gpx+xml"), resultOwner.requests,
        "gpx-export", launcherCapture, onLaunchFailure = ::cleanupTrackExport) { owner, uri ->
        val payload = resultOwner.takePayload("gpx-export", owner) as? UiResultPayload.TrackExport
        val accepted = accepts(owner)
        if (payload != null) vm.execute(if (uri != null && accepted) "GPX dosyası kaydedildi." else null) {
            try {
                if (uri != null && accepted) withContext(Dispatchers.IO) {
                    val xml = gpxStore.read(payload.id)
                    val output = context.contentResolver.openOutputStream(uri) ?: error("Dosya açılamadı.")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(xml) }
                }
            } finally { withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { gpxStore.remove(payload.id) } }
        }
    }
    val importBackup = rememberUiResultLauncher(ActivityResultContracts.OpenDocument(), resultOwner.requests,
        "backup-import", launcherCapture) { owner, uri -> if (accepts(owner)) restoreUri = uri }
    val importTrack = rememberUiResultLauncher(ActivityResultContracts.OpenDocument(), resultOwner.requests,
        "gpx-import", launcherCapture) { owner, uri ->
        if (uri != null && accepts(owner)) exitGuard.requestExit { gpxImports.read(uri) }
    }
    fun cleanupDirectionsPermission(owner: UiAsyncOwner) {
        val payload = resultOwner.takePayload("directions-permissions", owner) as? UiResultPayload.Permissions
        payload?.result?.onStale?.invoke()
    }
    val directionsPermissions = rememberUiResultLauncher(ActivityResultContracts.RequestMultiplePermissions(), resultOwner.requests,
        "directions-permissions", launcherCapture, onLaunchFailure = ::cleanupDirectionsPermission) { owner, _ ->
        val payload = resultOwner.takePayload("directions-permissions", owner) as? UiResultPayload.Permissions
        if (accepts(owner)) payload?.result?.onResult?.invoke() else payload?.result?.onStale?.invoke()
    }
    val openDirectionsPermissions: (Array<String>, UiPermissionReturn) -> Boolean = { names, result ->
        directionsPermissions.launch(names) { owner -> resultOwner.putPayload("directions-permissions", owner, UiResultPayload.Permissions(result)) }
    }
    val voiceSettings = rememberUiResultLauncher(ActivityResultContracts.StartActivityForResult(), resultOwner.requests,
        "voice-settings", launcherCapture) { owner, _ ->
        val payload = resultOwner.takePayload("voice-settings", owner) as? UiResultPayload.Voice
        val current = navigation.state.value
        if (payload?.owner?.matches(current.sessionId, current.routeRevision, current.guidance, accepts(owner)) == true)
            org.iz.navigation.navigation.NavigationSpeech.get(context).retry()
    }
    val openVoiceSettings: () -> Unit = {
        org.iz.navigation.navigation.NavigationVoiceSettings.open(context) { intent ->
            voiceSettings.launch(intent) { owner ->
                val current = navigation.state.value
                resultOwner.putPayload("voice-settings", owner, UiResultPayload.Voice(
                    PhoneVoiceSettingsOwner(owner, current.sessionId, current.routeRevision, current.guidance)))
            }
        }
        Unit
    }

    LaunchedEffect(Unit) {
        vm.events.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(contributionEditId) {
        if (contributionEdit == null) contributionEdit = contributionEditId?.let { vm.repository.getContribution(it) }
    }
    LaunchedEffect(uiEntry.key) {
        val captured = uiEntry
        val id = captured.entityId
        if (id != null && captured.page == UiPage.GPX) {
            val track = library.importedTracks.first().firstOrNull { it.id == id }
            if (history.current.key != captured.key) return@LaunchedEffect
            if (track == null) history.prune { it.key != captured.key }
            else if (gpxImports.state.value.track?.id != id) gpxImports.show(track)
        } else if (id != null && captured.page == UiPage.DIRECTIONS) {
            val plan = library.savedPlans.first().firstOrNull { it.id == id }
            if (history.current.key != captured.key) return@LaunchedEffect
            if (plan == null) history.prune { it.key != captured.key }
            else if (history.isRestoredEntry(captured.key)) {
                navigationStops = plan.stops; navigationTransport = plan.transport
                navigationInitialPlan = SavedWeatherPlan(plan.stops, System.currentTimeMillis(), plan.transport,
                    plan.preferences, plan.originUsesCurrentLocation, plan.travelSpeedKmh)
            }
        }
        restoringUiInput = false
    }
    LaunchedEffect(entries.map { it.key to it.entityId }, state.journeys.map { it.id }, state.places.map { it.id }, authState.user?.id, authState.busy) {
        val journeys = vm.repository.journeys.first().map { it.id }.toSet()
        val places = vm.repository.places.first().map { it.id }.toSet()
        val account = osmAuth.state.value
        history.prune { row -> when (row.page) {
            UiPage.JOURNEY -> row.entityId in journeys
            UiPage.PLACE -> row.entityId in places
            UiPage.COMMUNITY, UiPage.MAP_EDITS, UiPage.OSM_SURVEY ->
                account.busy || row.accountKey == null || row.accountKey == account.user?.id?.toString()
            else -> true
        } }
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++; vm.execute { vm.repository.cleanupExpired() } }
    LaunchedEffect(incoming) {
        val delivered = incoming ?: return@LaunchedEffect
        val sharedMapInput = org.iz.navigation.car.NavigationIntents.sharedInput(delivered)
        val id = delivered.getStringExtra("journeyId")
        val prompt = delivered.getStringExtra("trackingPrompt")
        val suggestion = runCatching { Transport.valueOf(delivered.getStringExtra("suggestedTransport").orEmpty()) }.getOrDefault(Transport.WALK)
        val pending = if (prompt == "confirm") id?.let { vm.repository.getJourney(it) } else null
        val activeId = if (prompt == "mode_change") vm.repository.activeJourney()?.id else null
        // Delivery is consumed once. Restoration never supplies a fresh intent from MainActivity.
        exitGuard.requestExit {
            exitGuard.update(null)
            when {
                delivered.getBooleanExtra("openGpx", false) -> {
                    if (incomingIsCold) history.toMap()
                    openGpx()
                }
                delivered.action == org.iz.navigation.osmcommunity.CommunityNotifications.ACTION_OPEN_MESSAGE -> {
                    communityMessageId = delivered.getLongExtra(org.iz.navigation.osmcommunity.CommunityNotifications.EXTRA_MESSAGE_ID, -1L)
                    communityAccountId = delivered.getLongExtra(org.iz.navigation.osmcommunity.CommunityNotifications.EXTRA_ACCOUNT_ID, -1L)
                    history.openExternal(UiPage.COMMUNITY, communityMessageId?.toString(), communityAccountId?.toString(), incomingIsCold)
                }
                sharedMapInput != null -> {
                    navigationStops = null; navigationTransport = null; navigationInitialPlan = null; navigationTarget = null
                    navigationShared = sharedMapInput
                    history.openExternal(UiPage.DIRECTIONS, cold = incomingIsCold)
                }
                delivered.getBooleanExtra("openNavigation", false) -> {
                    navigationStops = null; navigationTransport = null; navigationInitialPlan = null
                    navigationTarget = null; navigationShared = null
                    history.openExternal(UiPage.DIRECTIONS, cold = incomingIsCold)
                }
                delivered.getBooleanExtra("openWeather", false) -> history.openExternal(UiPage.WEATHER, cold = incomingIsCold)
                prompt == "journey" || prompt == "stop" -> if (id != null) history.openExternal(UiPage.JOURNEY, id, cold = incomingIsCold)
                prompt == "confirm" -> {
                    if (pending?.status == JourneyStatus.TEMPORARY && !DiaryRules.isExpired(pending, System.currentTimeMillis())) {
                        history.openExternal(UiPage.JOURNEY, pending.id, cold = incomingIsCold)
                        transportState.setForEntry(history.current.key, TransportAction(pending.id, suggestion = suggestion))
                    } else vm.message("Bu geçici kayıt artık bulunmuyor veya onay süresi dolmuş.")
                }
                prompt == "mode_change" -> if (activeId == id) transportAction = TransportAction(split = true, suggestion = suggestion)
                    else vm.message("Bu yolculuk artık devam etmiyor.")
                prompt == "permission" -> { history.openExternal(UiPage.SETTINGS, cold = incomingIsCold); detectionState.setForEntry(history.current.key, true) }
            }
        }
        consumeIntent(); initialIntentPending = false
    }

    fun returnToMap() { navigator.toMap() }
    fun returnToParent() { navigator.back() }
    fun openMapNote(location: SelectedOsmPlace? = null, placeId: String? = null) {
        contributionLocation = location
        contributionPlaceId = placeId
        contributionPicker = true
    }
    fun openPlaceNote(place: Place) {
        val location = if (place.latitude != null && place.longitude != null) {
            val ref = if (place.osmType != null && place.osmId != null) OsmRef(place.osmType, place.osmId) else null
            SelectedOsmPlace(place.name, place.latitude, place.longitude, ref)
        } else null
        openMapNote(location, place.id.takeIf { id -> state.places.any { it.id == id } })
    }
    fun openCorrection(place: Place, survey: Boolean = false) {
        val placeId = place.id.takeIf { id -> state.places.any { it.id == id } }
        exitGuard.requestExit {
            exitGuard.update(null)
            val target = if (survey) placeId?.let { "survey-place:$it" } ?: "survey" else placeId?.let { "place:$it" }
            val entry = history.push(UiPage.MAP_EDITS, target)
            if (placeId == null) correctionTargetState.setForEntry(entry.key, place)
        }
    }
    fun openOsmCorrection(place: SelectedOsmPlace) {
        val ref = place.osmRef ?: return
        openCorrection(Place(name = place.name, latitude = place.latitude, longitude = place.longitude,
            osmType = ref.type, osmId = ref.id, source = PlaceSource.OSM))
    }
    fun checkMapNote(draft: ContributionDraft) {
        if (contributionBusy) return
        contributionBusy = true
        vm.execute {
            try {
                vm.osmReady.await()
                val result = if (draft.status == ContributionStatus.UNKNOWN) contributionController.reconcile(draft.id)
                    else contributionController.refreshRemoteStatus(draft.id)
                vm.message(result.message)
            } finally { contributionBusy = false }
        }
    }
    fun openMapEditDraft(draft: MapEditDraft) {
        navigator.open(UiPage.MAP_EDITS, "draft:${draft.id}", draft.submittedBy?.toString())
    }
    LaunchedEffect(contributionEdit?.id, authState.busy, contributionAccountId) {
        val owner = contributionEdit?.submittedBy
        if (!authState.busy && owner != null && contributionAccountId != owner) {
            contributionEdit = null
            contributionEditId = null
        }
    }
    LaunchedEffect(entries.map { it.key }, uiEntry.page) {
        if (entries.none { it.key == informationOwner } ||
            (uiEntry.key != informationOwner && uiEntry.page != UiPage.MAP_EDITS)) {
            informationSelection = null; informationOwner = null
        }
    }
    val informationSavedPlace = entries.lastOrNull { it.page == UiPage.PLACE }?.entityId?.let { id -> state.places.firstOrNull { it.id == id } }
    val informationMapPlace = informationSelection?.takeIf { selected -> selected.osmRef != null &&
        entries.any { it.key == informationOwner } && (uiEntry.key == informationOwner || mapEditsPage) }
    val informationRef = informationMapPlace?.osmRef ?: informationSavedPlace?.let { saved ->
        saved.osmType?.let { type -> saved.osmId?.takeIf { it > 0 }?.let { OsmRef(type, it) } }
    }
    val informationKey = informationRef?.let { PlaceInfoKey(PlaceInfoServices.DEFAULT_ENDPOINT, it) }
    var informationActionError by remember(informationKey) { mutableStateOf<String?>(null) }
    val informationVisible = informationKey != null && !mapEditsPage && !contributionPicker && contributionEdit == null && !addPlace &&
        (uiEntry.page == UiPage.PLACE || (informationMapPlace != null && uiEntry.key == informationOwner))
    val informationSession = rememberPlaceInformationSession(informationKey, informationVisible,
        placeInformationContributionRevision(informationKey, informationEdits.orEmpty()), contributionReady = informationEdits != null,
        knownContributionVersion = placeInformationContributionVersion(informationKey, informationEdits.orEmpty()))
    val currentInformationKey by rememberUpdatedState(informationKey)
    val currentInformationVisible by rememberUpdatedState(informationVisible)
    fun informationIsCurrent(key: PlaceInfoKey) = currentInformationKey == key && currentInformationVisible && history.current.key == uiEntry.key
    fun informationRoute(name: String, latitude: Double?, longitude: Double?, key: PlaceInfoKey) {
        if (!informationIsCurrent(key) || latitude == null || longitude == null ||
            !latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0) return
        openDirections()
        navigationTarget = org.iz.navigation.car.NavigationTarget(org.iz.navigation.weather.WeatherCoordinate(latitude, longitude), name)
    }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    fun backFromTop() {
        if (imeVisible) keyboard?.hide() else if (backDispatcher != null) backDispatcher.onBackPressed() else returnToParent()
    }
    val homeVisible = uiEntry.page == UiPage.MAP || uiEntry.page == UiPage.DIRECTIONS
    BackHandler(enabled = entries.size > 1, onBack = ::returnToParent)
    LaunchedEffect(groupState.screenRequested) { if (groupState.screenRequested) groupPage = true }

    CompositionLocalProvider(LocalUiOwnerRegistrations provides resultOwner.registrations, LocalUiExitGuard provides exitGuard, LocalUiEntryKey provides uiEntry.key, LocalUiHistory provides history, LocalUiVoiceSettingsAction provides openVoiceSettings, LocalUiDirectionsPermissions provides openDirectionsPermissions,
        LocalUiOperationBusy provides { resultOwner.backupBusy.value || backupExport.busy || resultOwner.libraryBusy.value || resultOwner.gpxStarting.value || resultOwner.preparingGpx.value || resultOwner.formBusy.value || resultOwner.contributionBusy.value }) {
    UiEditOwner(UiEditState(uiEntry.key + "/ui", null, 0, 0, false, false))
    if (!libraryOpen && !collectionsOpen && !mapEditsPage) Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!homeVisible) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::backFromTop, modifier = Modifier.testTag("return-home-map")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Geri")
                }
                Text("iz", fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("home-menu")) {
                    Icon(Icons.Outlined.Menu, null); Spacer(Modifier.width(6.dp)); Text("Menü")
                }
            }
            val journey = state.journeys.firstOrNull { it.id == selectedJourney }
            val place = state.places.firstOrNull { it.id == selectedPlace }
            if (homeVisible && navigationState.trackFollow != null) {
                TextButton(onClick = { openGpx() }, modifier = Modifier.fillMaxWidth().testTag("home-active-gpx")) {
                    Text("GPX takibine dön")
                }
            }
            when {
                initialIntentPending -> Text("Açılıyor…", Modifier.padding(24.dp))
                restoringUiInput -> Text("Kayıt açılıyor…", Modifier.padding(24.dp))
                gpxPage -> GpxTrackScreen(track = importedGpx.track,
                    browse = rememberUiGpxBrowse(browseHolder, uiEntry.key, (importedGpx.track ?: navigationState.trackFollow?.track)?.id,
                        navigationState.trackFollow?.takeIf { it.track.id == (importedGpx.track ?: navigationState.trackFollow?.track)?.id }?.selection ?: org.iz.navigation.gpx.TrackFollowSelection()), active = navigationState.trackFollow,
                    fix = navigationState.fix, gpsStale = navigationState.gpsStale,
                    currentTransport = navigationState.sessionTransport.takeIf {
                        navigationState.sessionId != null && navigationState.journey?.status != JourneyStatus.TEMPORARY },
                    existingNavigation = navigationState.route != null,
                    interrupted = navigationState.interruptedTrackFollow,
                    busy = importedGpx.loading || gpxStarting || gpxPermissionPending || libraryBusy,
                    message = importedGpx.message ?: navigationState.message,
                    onImport = { importTrack.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")) },
                    onSave = { track ->
                        if (!libraryBusy) {
                            libraryBusy = true
                            vm.execute("GPX izi Rotalarım'a kaydedildi.") {
                                try { library.saveTrack(track); gpxImports.show(track) }
                                finally { libraryBusy = false }
                            }
                        }
                    },
                    onStart = { track, selection, transport, replace, expectedKey ->
                        if (!gpxStarting && !gpxPermissionPending) {
                            val prompt = gpxPermissionGate.request(GpxStartRequest(track, selection, transport, replace, expectedKey),
                                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
                                Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                                Build.VERSION.SDK_INT >= 33)
                            if (prompt != null) {
                                if (prompt.needed) {
                                    gpxPermissionPending = true
                                    val required = buildList {
                                        if (prompt.location) {
                                            add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION)
                                        }
                                        if (prompt.notifications) add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    val launched = runCatching { gpxPermissions.launch(required.toTypedArray()) }.getOrElse {
                                        vm.message(it.message ?: "İzin ekranı açılamadı."); false
                                    }
                                    if (!launched) { gpxPermissionPending = false; gpxPermissionGate.cancel() }
                                } else completeGpxStart()
                            }
                        }
                    },
                    onStop = { vm.execute { navigation.stopTrackFollow() } },
                    onDismiss = ::returnToParent,
                    onDismissInterrupted = navigation::dismissInterruptedTrackFollow,
                    replacementKey = navigationState.navigationReplacementKey())
                groupPage -> GroupScreen(groups, onClose = { groupPage = false; groups.dismissScreenRequest() },
                    onOpenRoute = { stops, transport -> groups.dismissScreenRequest(); openDirections(stops, transport) })
                communityPage -> OsmCommunityScreen(vm = featureOwners.getSavedState("community", uiEntry.key, OsmCommunityViewModel::class.java, context.applicationContext as android.app.Application, savedStateHost), onClose = { communityPage = false; communityMessageId = null; communityAccountId = null },
                    initialMessageId = remember(uiEntry.key) { uiEntry.entityId?.toLongOrNull()?.takeIf { !history.isRestoredEntry(uiEntry.key) && resultOwner.deliveredCommunityTargets.add(uiEntry.key) } }, initialAccountId = uiEntry.accountKey?.toLongOrNull(),
                    initialGpsJourneyId = osmGpsJourneyFromEntry(uiEntry, entries.getOrNull(entries.lastIndex - 1)), gpsJourneys = state.journeys,
                    initialGpsPage = when (uiEntry.entityId) {
                        "gps-share" -> OsmGpsPage.AVAILABLE
                        "gps-history" -> OsmGpsPage.HISTORY
                        else -> null
                    },
                    onOpenSettings = { tab = 4 })
                contributionHub -> OsmContributionHub(
                    repository = vm.repository, onClose = ::returnToParent,
                    onNewNote = { openMapNote() },
                    onNewPlace = { navigator.open(UiPage.MAP_EDITS, "new") },
                    onPickExistingPlace = { correctionPicker = true },
                    onGps = { navigator.open(UiPage.COMMUNITY, "gps-share") },
                    onResumeNote = { contributionEdit = it; contributionEditId = it.id },
                    onResumePlace = ::openMapEditDraft,
                    onNoteResult = ::checkMapNote,
                    onPlaceResult = ::openMapEditDraft,
                    onGpsHistory = { navigator.open(UiPage.COMMUNITY, "gps-history") },
                    onManageNotes = { contributionsPage = true },
                    onSurvey = { navigator.open(UiPage.OSM_SURVEY) },
                )
                surveyPage -> OsmSurveyScreen(vm.repository, onClose = ::returnToParent,
                    onOpen = { venue ->
                        val ref = venue.ref
                        openCorrection(Place(name = venue.place.name, latitude = venue.latitude, longitude = venue.longitude,
                            osmType = ref.type, osmId = ref.id, source = PlaceSource.OSM), survey = true)
                    })
                weatherPage -> WeatherPlannerScreen(onClose = { weatherPage = false },
                    vm = featureOwners.get("weather", uiEntry.key, WeatherPlannerViewModel::class.java, featureFactory), initialTransport = active?.transport,
                    onDirections = { stops, transport -> openDirections(stops, transport) },
                    onDirectionsWithPlan = { plan -> openDirections(plan.stops, plan.transport, plan) },
                    onSaveRoute = ::prepareSaveRoute,
                    onNavigationStarted = { exitGuard.update(null); history.toMap() })
                contributionsPage -> ContributionsScreen(state.contributions.filter {
                    it.submittedBy == null || it.submittedBy == contributionAccountId
                }, contributionBusy,
                    onNew = { openMapNote() }, onEdit = { contributionEdit = it; contributionEditId = it.id },
                    onCheck = ::checkMapNote,
                    onDelete = { draft -> deletion = Deletion("Bu kayıt yalnızca uygulamadaki katkı listenden silinecek. OSM’de yayımlanmış bildirim silinmez.") { vm.repository.deleteContribution(draft.id) } },
                )
                journey != null -> JourneyDetail(journey, state, now,
                    listState = rememberUiBrowseListState(browseHolder, uiEntry.key),
                    onEdit = { if (journey.status == JourneyStatus.TEMPORARY) transportAction = TransportAction(journey.id) else editJourney = journey }, onConfirm = { transportAction = TransportAction(journey.id) },
                    onFinish = {
                        if (!diaryEndBusy && !backupBusy) {
                            val target = diarySessionEndTarget(journey, navigation.state.value)
                            if (target == null) vm.message("Yolculuk veya oturum değişti. Yeniden kontrol et.")
                            else pendingDiaryEnd = target
                        }
                    },
                    onSwitch = { transportAction = TransportAction(split = true) },
                    onAddPlace = { openAddPlace(journey.id) }, onPhoto = { photoTarget = PhotoTarget(journeyId = journey.id) },
                    onVisit = { selectedPlace = it.placeId; selectedJourney = null },
                    onDelete = { deletion = Deletion("Bu yolculuğun rotası ve yalnızca bu yolculuğa bağlı fotoğraflar silinecek. Kaydedilmiş yer ziyaretleri korunacak.") { if (active?.id == journey.id) vm.tracker.finish(journey.id); vm.repository.deleteJourney(journey.id); selectedJourney = null } },
                    onDeletePhoto = { photo -> deletion = Deletion("Fotoğraf uygulamadaki günlükten silinecek.") { vm.repository.deletePhoto(photo.id) } },
                    onEditPhoto = { editPhoto = it },
                    onExportGpx = { gpxJourney = journey },
                    onOpenGpsTraces = { navigator.open(UiPage.COMMUNITY, OSM_GPS_JOURNEY_ENTRY) },
                    onSharePhotos = { photosToShare = state.photos.filter { it.journeyId == journey.id } },
                )
                place != null -> PlaceDetail(place, state,
                    publicInformation = informationKey?.let { key -> @Composable {
                        PlaceInformationContent(informationSession.state, key, "OSM yer bilgisi", informationSession.instant,
                            informationSession.deviceZone, informationSession.overrideZoneId, informationSession.changeZone,
                            informationSession.refresh,
                            onDirections = if (place.latitude != null && place.longitude != null) ({ informationRoute(place.name, place.latitude, place.longitude, key) }) else null,
                            onSurvey = if (place.latitude != null && place.longitude != null) ({ if (informationIsCurrent(key)) openCorrection(place, survey = true) }) else null,
                            onCorrect = if (place.latitude != null && place.longitude != null) ({ if (informationIsCurrent(key)) openCorrection(place) }) else null,
                            onNote = { if (informationIsCurrent(key)) openPlaceNote(place) },
                            onExternal = { action, phone -> if (informationIsCurrent(key)) informationActionError = openPlaceInformationAction(context, action, phone) },
                            externalMessage = informationActionError)
                    } },
                    listState = rememberUiBrowseListState(browseHolder, uiEntry.key),
                    onEdit = { osmSelection = null; editPlace = place }, onMaps = { vm.execute { MediaShare.openPlace(context, place) } },
                    onContribute = { openPlaceNote(place) },
                    onCorrect = if (place.osmType != null && place.osmId != null && place.latitude != null &&
                        place.longitude != null) ({ openCorrection(place) }) else null,
                    onSurvey = if (place.osmType != null && place.osmId != null && place.latitude != null &&
                        place.longitude != null) ({ openCorrection(place, survey = true) }) else null,
                    onAddVisit = { vm.execute("Yeni ziyaret eklendi.") { vm.repository.saveVisit(Visit(placeId = place.id, journeyId = active?.id, visitedAt = now)) } },
                    onEditVisit = { editVisit = it }, onShare = { visit -> photosToShare = state.photos.filter { it.visitId == visit.id } },
                    onPhoto = { photoTarget = PhotoTarget(it.journeyId, it.id) },
                    onDelete = { deletion = Deletion("Bu yer, ziyaretleri, bağlı fotoğrafları silinecek.") { vm.repository.deletePlace(place.id); selectedPlace = null } },
                    onDeleteVisit = { visit -> deletion = Deletion("Bu ziyaret, bağlı fotoğrafları silinecek.") { vm.repository.deleteVisit(visit.id) } },
                    onDeletePhoto = { photo -> deletion = Deletion("Fotoğraf günlükten silinecek.") { vm.repository.deletePhoto(photo.id) } },
                    onEditPhoto = { editPhoto = it },
                )
                uiEntry.page == UiPage.JOURNEY || uiEntry.page == UiPage.PLACE -> Text("Kayıt açılıyor…", Modifier.padding(24.dp))
                uiEntry.page == UiPage.MAP || navigationPage -> NavigationScreen(onClose = { navigationPage = false; navigationTarget = null; navigationShared = null },
                    vm = phoneModel(uiEntry),
                    incomingTarget = navigationTarget, initialStops = navigationStops, initialTransport = navigationTransport,
                    incomingShared = navigationShared.takeIf { navigationPage },
                    dashboardRequest = navigationDashboardRequest, layersRequest = navigationLayersRequest,
                    onSharedMap = { input -> exitGuard.requestExit {
                        navigationShared = input; navigationTarget = null; navigationInitialPlan = null
                        navigationStops = null; navigationTransport = null
                        history.push(UiPage.DIRECTIONS)
                    } },
                    entryId = navigationEntryId, initiallyPlanning = navigationPage,
                    initialPlan = if (navigationPage) navigationInitialPlan else null, onSaveRoute = ::prepareSaveRoute,
                    onStarted = { exitGuard.update(null); history.toMap() }, restoringEntry = history.isRestoredEntry(uiEntry.key),
                    continuePreparation = continueNavigationPreparation, onMap = ::returnToMap,
                    onPlannerOpened = { if (history.current.page != UiPage.DIRECTIONS) { continueNavigationPreparation = true; navigationStops = null; navigationInitialPlan = null; navigationTarget = null; history.push(UiPage.DIRECTIONS) } },
                    onMenu = { menuOpen = true }, onGroup = { groupPage = true }, onWeather = { weatherPage = true },
                    onOsmPlaceClick = if (informationVisible || addPlace || contributionPicker) null else { selected ->
                        if (history.current.key == uiEntry.key && selected.osmRef?.id?.let { it > 0 } == true) {
                            informationOwner = uiEntry.key; informationSelection = selected
                        }
                    },
                    onAssignShortcut = { kind ->
                        if (history.current.key == uiEntry.key && uiEntry.page == UiPage.MAP) {
                            navigator.open(UiPage.PLACES, shortcutAssignmentId(kind))
                        }
                    },
                    onPin = { coordinate ->
                        if (history.current.key == uiEntry.key && uiEntry.page == UiPage.MAP) exitGuard.requestExit {
                            exitGuard.update(null)
                            val entry = history.push(UiPage.MAP_EDITS, "new-coordinate")
                            mapEditCoordinateState.setForEntry(entry.key, coordinate)
                        }
                    })
                tab == 1 -> browseHolder.SaveableStateProvider(uiEntry.key) { JourneysScreen(state, now, onJourney = { selectedJourney = it.id }, onStart = { transportAction = TransportAction() }, onConfirm = { transportAction = TransportAction(it.id) }, onReject = { j -> deletion = Deletion("Bu geçici rota silinecek ve açıksa takip duracak.") { vm.tracker.reject(j.id) } }) }
                shortcutAssignment != null -> DestinationShortcutAssignmentScreen(shortcutAssignment, state.places,
                    busy = resultOwner.formBusy.value, error = shortcutError,
                    onPlace = { place -> assignShortcut(shortcutAssignment) {
                        library.setDestinationShortcut(shortcutAssignment, place.id)
                    } },
                    onNewPlace = {
                        if (history.current.key == uiEntry.key && !resultOwner.formBusy.value) {
                            shortcutError = null
                            openAddPlace(journeyId = null)
                        }
                    },
                    onCancel = ::returnToParent)
                tab == 2 -> browseHolder.SaveableStateProvider(uiEntry.key) { PlacesScreen(state, onPlace = { selectedPlace = it.id }, onAdd = { openAddPlace() }, onReorder = vm.repository::reorderPlaces, shortcuts = destinationShortcuts,
                    onShortcut = { kind, target -> vm.execute { library.setDestinationShortcut(kind, target?.id) } }) }
                tab == 3 -> browseHolder.SaveableStateProvider(uiEntry.key) { HeatmapScreen(state) }
                tab == 5 -> browseHolder.SaveableStateProvider(uiEntry.key) { DiaryStatisticsScreen(state, now, onJourney = { selectedJourney = it.id }) }
                else -> SettingsScreen(settings, refresh, backupBusy,
                    listState = rememberUiBrowseListState(browseHolder, uiEntry.key),
                    onDetection = { if (settings.enabled) { vm.tracker.disableDetection(); refresh++ } else showDetection = true },
                    onRepairDetection = { vm.tracker.enableDetection { error -> refresh++; error?.let(vm::message) } },
                    onStopMinutes = { settings.stopMinutes = it; refresh++ },
                    onPermissions = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                    onExport = {
                        if (!backupImportBusy && !backupExport.busy) {
                            includeHealthBackup = false
                            showBackupOptions = true
                        }
                    },
                    onImport = {
                        if (!backupImportBusy && !backupExport.busy) {
                            importBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                        }
                    },
                    onContributions = { contributionHub = true }, onMessage = vm::message,
                    onCommunity = { communityMessageId = null; communityAccountId = null; navigator.open(UiPage.COMMUNITY) },
                )
            }
        }
    }

    if (menuOpen) AlertDialog(onDismissRequest = { menuOpen = false }, title = { Text("Menü") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            fun open(action: () -> Unit) { menuOpen = false; action() }
            TextButton({ open { tab = 1 } }, Modifier.fillMaxWidth().testTag("menu-journeys")) { Text("Yolculuklar") }
            TextButton({ open { openDirections() } }, Modifier.fillMaxWidth().testTag("menu-route-plan")) { Text("Rota planla") }
            if (uiEntry.page in setOf(UiPage.MAP, UiPage.DIRECTIONS)) {
                TextButton({ open { navigationDashboardRequest++ } }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("menu-navigation-dashboard")) { Text("Yolculuk göstergesi") }
                TextButton({ open { navigationLayersRequest++ } }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("menu-map-layers")) { Text("Harita katmanları") }
            }
            TextButton({ open { libraryOpen = true } }, Modifier.fillMaxWidth().testTag("menu-route-library")) { Text("Rotalarım") }
            TextButton({ open { collectionsOpen = true } }, Modifier.fillMaxWidth().testTag("menu-collections")) { Text("Geziler") }
            TextButton({ open { openGpx() } }, Modifier.fillMaxWidth().testTag("menu-gpx-follow")) { Text("GPX izi takip et") }
            TextButton({ open { tab = 5 } }, Modifier.fillMaxWidth().testTag("menu-statistics")) { Text("İstatistikler") }
            TextButton({ open { tab = 2 } }, Modifier.fillMaxWidth().testTag("menu-places")) { Text("Yerler") }
            TextButton({ open { tab = 3 } }, Modifier.fillMaxWidth().testTag("menu-heatmap")) { Text("Yoğunluk haritası") }
            TextButton({ open { openAddPlace() } }, Modifier.fillMaxWidth().testTag("menu-add-place")) { Text("Bir yer kaydet") }
            TextButton({ open { navigator.open(UiPage.COMMUNITY) } }, Modifier.fillMaxWidth().testTag("menu-community")) { Text("OSM Topluluğu") }
            TextButton({ open { contributionHub = true } }, Modifier.fillMaxWidth().testTag("menu-osm")) { Text("OSM'ye katkı") }
            TextButton({ open { weatherPage = true } }, Modifier.fillMaxWidth().testTag("menu-weather")) { Text("Hava durumu") }
            TextButton({ open { tab = 4 } }, Modifier.fillMaxWidth().testTag("menu-settings")) { Text("Ayarlar") }
        }
    }, confirmButton = { TextButton({ menuOpen = false; returnToMap() }) { Text("Haritaya dön") } })
    pendingDiaryEnd?.let { target ->
        DiarySessionEndActions(target, state.journeys.firstOrNull { it.id == target.journeyId }, navigationState,
            diaryEndBusy || backupBusy, onDismiss = { pendingDiaryEnd = null }, onConfirm = { captured, request ->
                if (!diaryEndBusy && !backupBusy) {
                    pendingDiaryEnd = null
                    diaryEndWork.mutate {
                        val currentJourney = vm.repository.getJourney(captured.journeyId)
                        val accepted = dispatchDiarySessionEnd(captured, request, currentJourney, navigation.state.value,
                            backupBusy, stopGuidance = { navigation.stopGuidance(it) },
                            stopRecording = { navigation.stopRecording(it) }, finish = { navigation.finishSession(it) },
                            finishUnattached = { navigation.finishUnattachedJourney(it) })
                        if (!accepted) vm.message("Yolculuk veya oturum değişti. Yeniden kontrol et.")
                        else vm.message(when (request?.action) {
                            PhoneSessionEnd.GUIDANCE -> "Yol tarifi bitirildi."
                            PhoneSessionEnd.ALL -> "Yolculuk oturumu bitirildi."
                            else -> "Yolculuk kaydı bitirildi."
                        })
                    }
                }
            })
    }
    if (libraryOpen) browseHolder.SaveableStateProvider(uiEntry.key) { TravelLibraryScreen(repository = library, onDismiss = { libraryOpen = false },
        onOpenPlan = { plan ->
            openDirections(plan.stops, plan.transport, SavedWeatherPlan(plan.stops, System.currentTimeMillis(),
                plan.transport, plan.preferences, plan.originUsesCurrentLocation, plan.travelSpeedKmh), sourcePlanId = plan.id)
        }, onOpenTrack = ::openGpx) }
    if (collectionsOpen) browseHolder.SaveableStateProvider(uiEntry.key) { JourneyCollectionsScreen(repository = library,
        snapshot = DiarySnapshot(journeys = state.journeys), analysis = state.analysis,
        onDismiss = { collectionsOpen = false }, onOpenJourney = {
            selectedJourney = it.id
        }) }
    routeToSave?.let { plan -> SaveRoutePlanDialog(plan, libraryBusy, onDismiss = { routeToSave = null },
        onSave = { named ->
            libraryBusy = true
            vm.execute("Rota Rotalarım'a kaydedildi.") {
                try { library.savePlan(named); routeToSave = null }
                finally { libraryBusy = false }
            }
        }) }
    if (correctionPicker) OsmSearchDialog(onDismiss = { correctionPicker = false }, onSelect = { selected ->
        if (selected.osmRef == null) vm.message("Düzenlemek için OSM kimliği olan bir mekân seç.")
        else {
            correctionPicker = false
            openOsmCorrection(selected)
        }
    })
    informationMapPlace?.takeIf { informationVisible && uiEntry.key == informationOwner }?.let { selected ->
        informationKey?.let { key ->
            PlaceInformationMapDialog(onDismiss = { informationSelection = null; informationOwner = null }) {
                    PlaceInformationContent(informationSession.state, key, selected.name, informationSession.instant,
                        informationSession.deviceZone, informationSession.overrideZoneId, informationSession.changeZone, informationSession.refresh,
                        onDirections = { informationRoute(selected.name, selected.latitude, selected.longitude, key) },
                        onSurvey = { if (informationIsCurrent(key)) {
                            openCorrection(Place(name = selected.name, latitude = selected.latitude, longitude = selected.longitude,
                                osmType = key.ref.type, osmId = key.ref.id, source = PlaceSource.OSM), survey = true)
                        } },
                        onCorrect = { if (informationIsCurrent(key)) openOsmCorrection(selected) },
                        onNote = { if (informationIsCurrent(key)) openMapNote(selected) },
                        onExternal = { action, phone -> if (informationIsCurrent(key)) informationActionError = openPlaceInformationAction(context, action, phone) },
                        onClose = { informationSelection = null; informationOwner = null },
                        modifier = Modifier.fillMaxSize().verticalScroll(informationScroll).padding(20.dp),
                        externalMessage = informationActionError,
                        onSave = { if (informationIsCurrent(key)) {
                            osmSelection = selected; pin = GeoCoordinate(selected.latitude, selected.longitude); addPlace = true
                        } })
            }
        }
    }
    if (mapEditsPage && (uiEntry.accountKey == null || uiEntry.accountKey == contributionAccountId?.toString()))
        Dialog(onDismissRequest = { mapEditsPage = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val targetId = uiEntry.entityId?.let {
            when {
                it.startsWith("survey-place:") -> it.removePrefix("survey-place:")
                it.startsWith("place:") -> it.removePrefix("place:")
                else -> null
            }
        }
        Surface(Modifier.fillMaxSize()) {
            MapEditPage(onClose = { mapEditsPage = false },
                initialCorrection = correctionTarget ?: state.places.firstOrNull { it.id == targetId },
                initialDraftId = uiEntry.entityId?.takeIf { it.startsWith("draft:") }?.removePrefix("draft:"),
                startNew = uiEntry.entityId == "new",
                initialCoordinate = mapEditCoordinate.takeIf { uiEntry.entityId == "new-coordinate" },
                startSurvey = uiEntry.entityId == "survey" || uiEntry.entityId?.startsWith("survey-place:") == true,
                onNoteFallback = ::openPlaceNote)
        }
    }

    transportAction?.let { action ->
        val existing = state.journeys.firstOrNull { it.id == action.journeyId }
        TransportDialog(existing?.transport ?: action.suggestion, if (action.split) "Ulaşım türünü değiştir" else if (action.journeyId != null) "Yolculuğunu sakla" else "Yeni bir yolculuk", onDismiss = { transportAction = null },
            onReject = if (existing?.status == JourneyStatus.TEMPORARY) ({ transportAction = null; vm.execute("Geçici kayıt silindi.") { vm.tracker.reject(existing.id) } }) else null,
        ) { transport ->
            if (action.journeyId == null && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)); return@TransportDialog
            }
            if (action.journeyId == null && Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)); return@TransportDialog
            }
            if (transport.supportsSteps && !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                permissions.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
            }
            vm.execute {
                when { action.split -> selectedJourney = vm.tracker.switchTransport(transport)
                    action.journeyId != null -> { check(vm.repository.getJourney(action.journeyId) != null) { "Bu kayıt artık bulunmuyor." }; vm.tracker.confirm(action.journeyId, transport); selectedJourney = action.journeyId }
                    else -> {
                        val navigation = (context.applicationContext as org.iz.navigation.IzApplication).navigation
                        navigation.startFreeDrive(transport, recordJourney = true)
                        exitGuard.update(null); history.toMap()
                    } }
                transportAction = null
            }
        }
    }

    if (addPlace) PlaceEditor(pin, osmSelection,
        if (shortcutAssignment == null) state.places else state.places.filter(Place::isValidDestinationShortcut),
        assignmentKind = shortcutAssignment, saveError = shortcutError,
        onMapNote = pin?.takeIf { shortcutAssignment == null }?.let { coordinate ->
            {
                addPlace = false
                openMapNote(osmSelection ?: SelectedOsmPlace("Seçilen konum", coordinate.latitude, coordinate.longitude))
            }
        },
        onOsmCorrection = osmSelection?.takeIf { shortcutAssignment == null && it.osmRef != null }?.let { selected ->
            { openOsmCorrection(selected) }
        },
        onOsmSurvey = osmSelection?.takeIf { shortcutAssignment == null && it.osmRef != null }?.let { selected ->
            { val ref = requireNotNull(selected.osmRef)
                openCorrection(Place(name = selected.name, latitude = selected.latitude, longitude = selected.longitude,
                    osmType = ref.type, osmId = ref.id, source = PlaceSource.OSM), survey = true) }
        },
        onDismiss = {
            if (shortcutAssignment != null) {
                shortcutLocation?.cancel()
                shortcutError = null
                searchOsm = false
            }
            addPlace = false
        }, onSearch = { searchOsm = true }, onLocate = {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else if (shortcutAssignment == null) vm.execute {
                @Suppress("MissingPermission") val location = LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                if (location == null) vm.message("Konum alınamadı. Haritaya uzun basarak yer seçebilirsin.") else { osmSelection = null; pin = GeoCoordinate(location.latitude, location.longitude) }
            } else if (shortcutLocation?.isActive != true && !resultOwner.formBusy.value) {
                val owner = captureResultOwner()
                val revision = addPlaceState.revision
                if (owner != null) shortcutLocation = shortcutScope.launch {
                    fun editorIsCurrent() = addPlace && addPlaceState.revision == revision && accepts(owner)
                    try {
                        @Suppress("MissingPermission")
                        val location = LocationServices.getFusedLocationProviderClient(context)
                            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                        if (editorIsCurrent()) {
                            shortcutError = if (location == null) "Konum alınamadı. Yeniden dene." else null
                            if (location != null) {
                                osmSelection = null
                                pin = GeoCoordinate(location.latitude, location.longitude)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (editorIsCurrent()) shortcutError = "Konum alınamadı. Yeniden dene."
                    }
                }
            }
        }, onSave = { name, note, existingPlace ->
            fun newPlace() = Place(name = name.trim(), latitude = pin?.latitude, longitude = pin?.longitude,
                osmType = osmSelection?.osmRef?.type, osmId = osmSelection?.osmRef?.id,
                source = if (osmSelection?.osmRef != null) PlaceSource.OSM else PlaceSource.USER)
            if (shortcutAssignment != null) {
                val draft = if (existingPlace == null) newPlace() else null
                assignShortcut(shortcutAssignment) {
                    if (draft != null) vm.repository.savePlaceAndAssignShortcut(shortcutAssignment, draft)
                    else library.setDestinationShortcut(shortcutAssignment, checkNotNull(existingPlace).id)
                }
            } else executeEditor("Yer ziyaretin kaydedildi.") {
                val place = existingPlace ?: newPlace().also { vm.repository.savePlace(it) }
                vm.repository.saveVisit(Visit(placeId = place.id, journeyId = targetJourney, visitedAt = now, note = note.trim()))
                addPlace = false; selectedJourney = null; selectedPlace = place.id
            }
        },
    )
    if (searchOsm) {
        val editorRevision = addPlaceState.revision
        fun searchIsCurrent() = shortcutAssignment == null ||
            (searchOsm && addPlace && addPlaceState.revision == editorRevision)
        OsmSearchDialog(onDismiss = { if (searchIsCurrent()) searchOsm = false }, onSelect = {
            if (searchIsCurrent()) {
                shortcutLocation?.cancel()
                shortcutError = null
                osmSelection = it
                pin = GeoCoordinate(it.latitude, it.longitude)
                searchOsm = false
            }
        })
    }
    photoTarget?.let { target ->
        AlertDialog(onDismissRequest = { photoTarget = null }, title = { Text("Bir anı ekle") }, text = { Text("Seçtiğin fotoğrafların bir kopyası yalnızca bu uygulamada saklanır.") },
            confirmButton = { TextButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) { owner -> resultOwner.putPayload("gallery", owner, UiResultPayload.Photos(target.journeyId, target.visitId, photoTargetState.revision)) } }) { Text("Galeriden seç") } },
            dismissButton = { TextButton(onClick = {
                runCatching { camera.launchPrepared { owner -> val uri = media.createCaptureUri(); resultOwner.putPayload("camera", owner, UiResultPayload.Camera(uri.toString(), target.journeyId, target.visitId)); photoTarget = null; uri } }.onFailure { vm.message("Kamera açılamadı: ${it.message}") }
            }) { Text("Fotoğraf çek") } },
        )
    }
    editJourney?.let { j -> JourneyEditor(j, { editJourney = null }) { updated -> executeEditor("Yolculuk kaydı güncellendi.") {
        if (updated.transport.supportsSteps && updated.endedAt == null && !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            permissions.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
        }
        check(vm.repository.updateJourneyDetails(updated.id, updated.title, updated.note, updated.transport)) { "Bu yolculuk artık bulunmuyor." }
        org.iz.navigation.tracking.TrackingService.refresh(context)
        editJourney = null
    } } }
    editPlace?.let { p -> PlaceDetailsEditor(p, osmSelection,
        onSearch = { searchOsm = true },
        onDismiss = { editPlace = null },
        onSave = { updated -> executeEditor("Yer bilgileri kaydedildi.") { vm.repository.savePlace(updated); editPlace = null } },
    ) }
    editVisit?.let { v -> VisitEditor(v, { editVisit = null }) { note -> executeEditor("Ziyaret güncellendi.") { vm.repository.saveVisit(v.copy(note = note)); editVisit = null } } }
    val photoEditor = editPhoto ?: missingPhotoMetadata.firstOrNull()
    photoEditor?.let { photo ->
        fun closePhoto() { if (editPhoto != null) editPhoto = null else missingPhotoMetadata = missingPhotoMetadata.drop(1) }
        PhotoEditor(photo, onDismiss = { closePhoto() }) { updated -> executeEditor("Fotoğraf bilgileri kaydedildi.") { vm.repository.savePhoto(updated); closePhoto() } }
    }
    photosToShare?.let { photos -> PhotoShareSheet(photos, onDismiss = { photosToShare = null },
        onShare = { selected -> vm.execute { MediaShare.sharePhotos(context, selected) } },
        onGallery = { selected -> vm.execute { val count = MediaShare.savePhotosToGallery(context, selected); vm.message("$count fotoğraf, dosyaya ekli konum ve çekim bilgileri kaldırılarak galeriye kaydedildi.") } }) }
    if (contributionPicker) {
        ContributionLocationPicker(contributionLocation, contributionPlaceId,
            onDismiss = { contributionPicker = false }, onChoose = { draft -> vm.execute {
                vm.osmReady.await(); vm.repository.saveContribution(draft)
                contributionPicker = false; contributionEdit = draft; contributionEditId = draft.id
            } })
    }
    contributionEdit?.takeIf { it.submittedBy == null || it.submittedBy == contributionAccountId }?.let { draft ->
        ContributionEditor(draft, authState.user != null, contributionBusy || authState.busy,
        onDismiss = { contributionEdit = null; contributionEditId = null }, onSave = { updated -> contributionBusy = true; vm.execute("Gözlem taslağı saklandı.") {
            try { vm.osmReady.await(); vm.repository.saveContribution(updated); acknowledgedContribution = updated; contributionEdit = null; contributionEditId = null }
            finally { contributionBusy = false }
        } },
        onPublish = { updated -> contributionBusy = true; vm.execute {
            try { vm.osmReady.await(); vm.repository.saveContribution(updated); acknowledgedContribution = updated; val result = contributionController.send(updated.id); contributionEdit = null; contributionEditId = null; vm.message(result.message) }
            finally { contributionBusy = false }
        } },
        onLogin = { updated -> contributionBusy = true; vm.execute {
            try {
                vm.osmReady.await(); vm.repository.saveContribution(updated)
                acknowledgedContribution = updated; contributionEdit = updated; contributionEditId = updated.id
                osmAuth.startLogin(context.findActivity() ?: error("Giriş ekranı açılamadı."))
            } finally { contributionBusy = false }
        } },
        authError = authState.error,
        liveBaseline = { contributionEdit?.takeIf { it.id == draft.id } ?: acknowledgedContribution?.takeIf { it.id == draft.id } ?: draft },
        liveBusy = { contributionBusy || osmAuth.state.value.busy },
    ) }
    gpxJourney?.let { journey -> key(journey.id) {
        val loaded by produceState<Result<List<TrackPoint>>?>(null, journey.id) {
            value = try { Result.success(withContext(Dispatchers.IO) { vm.repository.journeyPoints(journey.id) }) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
        }
        val points = loaded?.getOrNull()
        if (points == null) AlertDialog(onDismissRequest = { gpxJourney = null },
            title = { Text("GPX dışa aktarımı") },
            text = { Text(if (loaded == null) "Yolculuğun konum kayıtları okunuyor…" else "Konum kayıtları okunamadı. Yeniden deneyebilirsin.") },
            confirmButton = { TextButton(onClick = { gpxJourney = null }) { Text("Kapat") } })
        else GpxExportSheet(journey, points, { gpxJourney = null }) { xml ->
            if (!preparingGpx) {
                trackExportOwner = captureResultOwner()
                if (trackExportOwner == null) return@GpxExportSheet
                preparingGpx = true
                vm.execute {
                    try {
                        pendingGpxId = withContext(Dispatchers.IO) { gpxStore.prepare(xml) }
                        gpxJourney = null
                        val exportId = pendingGpxId ?: error("GPX önizlemesi bulunamadı.")
                        pendingGpxId = null
                        val captured = trackExportOwner
                        val launched = captured != null && exportGpx.launchWithOwner(captured, "iz-rota-${journey.startedAt}.gpx") { owner ->
                            resultOwner.putPayload("gpx-export", owner, UiResultPayload.TrackExport(exportId))
                        }
                        if (!launched) withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { gpxStore.remove(exportId) }
                    } finally { preparingGpx = false }
                }
            }
        }
    } }
    deletion?.let { d -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("Kaydı sil") }, text = { Text(d.text) }, confirmButton = { TextButton(onClick = { deletion = null; vm.execute("Kayıt silindi.", d.action) }) { Text("Sil", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text("Vazgeç") } }) }
    if (showDetection) AlertDialog(onDismissRequest = { showDetection = false }, title = { Text("Hareketle kayıt önerisi") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Hareket algılandığında mesafe ölçülür. 15 dakika içinde toplam 500 metreye ulaşınca başlangıç rotası otomatik kaydedilir. Ulaşılamazsa ölçüm sıfırlanır. Bu süre içindeki dur-kalklar ayrı kayıt açmaz. Konum, uygulama kapalıyken de kullanılır; takip sürekli bildirimle gösterilir.")
            Text("Önce hareket, hassas konum ve bildirim izinlerini ver. Ardından Android ayarlarından konum erişimini ‘Her zaman izin ver’ olarak seç.")
            OutlinedButton(onClick = {
                val required = mutableListOf(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
                permissions.launch(required.toTypedArray())
            }) { Text("1. Gerekli izinleri ver") }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("2. Arka plan konum izni") }
            Text("İzinleri vermeden de yolculukları elle başlatabilirsin.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { vm.tracker.enableDetection { error -> refresh++; if (error == null) { showDetection = false; vm.message("Hareket algılama açıldı.") } else vm.message(error) } }) { Text("Algılamayı aç") } }, dismissButton = { TextButton(onClick = { showDetection = false }) { Text("Şimdi değil") } })
    restoreUri?.let { uri -> AlertDialog(onDismissRequest = { restoreUri = null }, title = { Text("Yedeği geri yükle") }, text = { Text("Dosya doğrulanıp günlük başarıyla değiştirildiğinde aktif yolculuk kaydı duracak. Mevcut kayıtların için önce yedek alabilirsin.") }, confirmButton = { TextButton(enabled = !backupBusy, onClick = {
        if (!backupImportBusy && !backupExport.busy) {
            restoreUri = null
            backupImportBusy = true
            vm.execute("Yedek geri yüklendi.") {
                val wasEnabled = settings.enabled
                // Keep the existing subscription, but ignore new detection events during replacement.
                settings.enabled = false
                try {
                    healthManager.withDiaryReplacement { DiaryBackup(context, vm.repository).importFrom(uri) { vm.tracker.stopAfterRestore() } }
                    selectedJourney = null; selectedPlace = null
                }
                finally { settings.enabled = wasEnabled; backupImportBusy = false; refresh++ }
            }
        }
    }) { Text("Geri yükle") } }, dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Vazgeç") } }) }
    if (showBackupOptions) AlertDialog(onDismissRequest = { showBackupOptions = false },
        title = { Text("Yedek içeriği") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Yolculuklar, konum kayıtları, yerler, özel notlar ve fotoğraflar yedeklenir.")
            Text("Varsa deneysel yatış verileri, ham sensör kayıtları ve bunlara bağlı konum, zaman, hız ve kalibrasyon bilgileri de yedeklenir.")
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = includeHealthBackup,
                    role = Role.Checkbox,
                    onValueChange = { includeHealthBackup = it },
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = includeHealthBackup, onCheckedChange = null)
                Text("Sağlık ölçümlerini de ekle")
            }
            Text("Sağlık ölçümleri varsayılan olarak eklenmez. Bu seçim yatış ve sensör kayıtlarını etkilemez.")
            Text("Yedek hazır olduğunda kaydedileceği konumu seçeceksin. Dosya adını değiştirmeden yeni bir dosyaya kaydet.")
        } },
        confirmButton = { TextButton(onClick = {
            if (!backupImportBusy && !backupExport.busy) {
                val owner = captureResultOwner()
                if (owner != null && backupExport.start(backup, owner, includeHealthBackup)) {
                    showBackupOptions = false
                    includeHealthBackup = false
                }
            }
        }) { Text("Yedeği hazırla") } },
        dismissButton = { TextButton(onClick = { showBackupOptions = false }) { Text("Vazgeç") } })
    if (backupImportBusy) AlertDialog(onDismissRequest = {}, title = { Text("Arşivin hazırlanıyor") }, text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { CircularProgressIndicator(Modifier.size(28.dp)); Text("İşlem tamamlanana kadar lütfen bekle.") } }, confirmButton = {})
    BackupExportProgress(backupExport.phase, backupExport::cancel)
    UiExitConfirmation(exitGuard)
    }
}

@Composable
private fun HomeScreen(state: DiaryState, active: Journey?, now: Long, onStart: () -> Unit, onWeather: () -> Unit, onDirections: () -> Unit, onActive: () -> Unit, onAddPlace: () -> Unit, onPlace: (Place) -> Unit, onPin: (GeoCoordinate) -> Unit, onOsmPlace: (SelectedOsmPlace) -> Unit, onContribute: () -> Unit, onCommunity: () -> Unit) {
    var followRoute by rememberSaveable(active?.id) { mutableStateOf(true) }
    var showMapMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("Gittiğin yollar,\nbiriken hikâyeler.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(10.dp))
            Text("${state.journeys.count { it.status == JourneyStatus.CONFIRMED }} yolculuk  ·  ${state.places.size} yer  ·  ${state.photos.size} fotoğraf", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp))) {
            DiaryMap(points = emptyList(), places = state.places, modifier = Modifier.fillMaxSize(), onPlaceClick = onPlace, onMapLongClick = onPin, focusCurrentLocation = true, onOsmPlaceClick = onOsmPlace, followRecordedLocation = active != null && followRoute,
                fullscreenActions = {
                    if (active != null) IconButton(onClick = { followRoute = !followRoute }) {
                        Icon(if (followRoute) Icons.Outlined.GpsFixed else Icons.Outlined.GpsNotFixed,
                            if (followRoute) "Canlı takibi kapat" else "Rotayı takip et")
                    }
                    IconButton(onClick = { showMapMenu = true }) { Icon(Icons.Outlined.MoreVert, "Harita işlemleri") }
                    if (showMapMenu) HomeMapMenu(active, onDismiss = { showMapMenu = false },
                        onJourney = if (active == null) onStart else onActive,
                        onWeather = onWeather, onDirections = onDirections, onAddPlace = onAddPlace, onContribute = onContribute)
                })
            if (active != null) FilterChip(followRoute, { followRoute = !followRoute }, { Text(if (followRoute) "Canlı takip açık" else "Rotayı takip et") }, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 28.dp), colors = FilterChipDefaults.filterChipColors(containerColor = Paper, selectedContainerColor = Leaf))
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active != null) Surface(onClick = onActive, color = Leaf, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(active.transport.icon(), null, tint = active.transport.accentColor(), modifier = Modifier.size(30.dp)); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (active.status == JourneyStatus.TEMPORARY) "500 metre için mesafe ölçülüyor" else "Yolculuğun devam ediyor", style = MaterialTheme.typography.titleMedium)
                        Text("${journeyDistance(active, state)} · ${duration(now - active.startedAt)}", color = Muted)
                        TrackingPolicy.automaticCandidateDeadline(active)?.let { deadline -> Text("Sıfırlanmaya ${countdown(deadline - now)}", style = MaterialTheme.typography.bodySmall, color = Muted) }
                    }
                    Icon(Icons.Outlined.ChevronRight, "Yolculuğu aç")
                }
            } else Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Yolculuk başlat") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onWeather, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("home-weather"), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Cloud, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Hava durumu")
                }
                OutlinedButton(onClick = onDirections, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("home-directions"), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Route, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Yol tarifi")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onAddPlace, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) { Text("Bir yer kaydet") }; OutlinedButton(onClick = onContribute, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) { Text("OSM'ye katkı") } }
            Surface(onClick = onCommunity, shape = RoundedCornerShape(16.dp), color = Leaf) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Forum, null, tint = Forest)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("OSM Topluluğu", style = MaterialTheme.typography.titleMedium)
                        Text("Mesajlar, kişiler ve hesabın", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
private fun HomeMapMenu(active: Journey?, onDismiss: () -> Unit, onJourney: () -> Unit,
    onWeather: () -> Unit, onDirections: () -> Unit, onAddPlace: () -> Unit, onContribute: () -> Unit,
) {
    fun choose(action: () -> Unit) { onDismiss(); action() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Harita işlemleri") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bir mekâna dokun veya nokta seçmek için haritaya uzun bas.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { choose(onJourney) }, Modifier.fillMaxWidth()) {
                Text(if (active == null) "Yolculuk başlat" else "Aktif yolculuğu aç")
            }
            OutlinedButton(onClick = { choose(onWeather) }, Modifier.fillMaxWidth()) { Text("Yolculuk havası") }
            OutlinedButton(onClick = { choose(onDirections) }, Modifier.fillMaxWidth()) { Text("Yol tarifi") }
            OutlinedButton(onClick = { choose(onAddPlace) }, Modifier.fillMaxWidth()) { Text("Bir yer kaydet") }
            OutlinedButton(onClick = { choose(onContribute) }, Modifier.fillMaxWidth()) { Text("OSM’ye katkı") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Haritaya dön") } })
}

@Composable
private fun JourneysScreen(state: DiaryState, now: Long, onJourney: (Journey) -> Unit, onStart: () -> Unit, onConfirm: (Journey) -> Unit, onReject: (Journey) -> Unit) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var dateFilter by rememberSaveable { mutableStateOf("Tümü") }
    val cutoff = when (dateFilter) { "7 gün" -> now - 7 * 86_400_000L; "30 gün" -> now - 30 * 86_400_000L; else -> 0L }
    val visible = state.journeys.filter { (it.expiresAt == null || it.expiresAt > now) && (filter == null || it.transport.name == filter || (filter == "OWN_DRIVING" && it.transport in listOf(Transport.CAR, Transport.MOTORCYCLE))) && it.startedAt >= cutoff }.sortedByDescending { it.startedAt }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("Yolculukların", "Her rotanın bir hikâyesi var.") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(filter == null, { filter = null }, { Text("Tümü") }); FilterChip(filter == "OWN_DRIVING", { filter = "OWN_DRIVING" }, { Text("Sürüşlerim") }); transportDisplayOrder.forEach { t -> FilterChip(filter == t.name, { filter = t.name }, { Text(t.label()) }) } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Tümü", "7 gün", "30 gün").forEach { f -> FilterChip(dateFilter == f, { dateFilter = f }, { Text(f) }) } } }
        if (visible.isEmpty()) item { EmptyCard(Icons.Outlined.Route, "Yeni bir rota seni bekliyor", "Başlattığın yolculuklar ve hareketle önerilen geçici kayıtlar burada birikir.") }
        items(visible, key = { it.id }) { journey ->
            Surface(shape = RoundedCornerShape(22.dp), color = if (journey.status == JourneyStatus.TEMPORARY) Leaf else Color.White, onClick = { onJourney(journey) }) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { ModeBadge(journey.transport); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(journey.title.ifBlank { journey.transport.label() }, style = MaterialTheme.typography.titleMedium); Text(date(journey.startedAt), style = MaterialTheme.typography.bodySmall, color = Muted) }; Icon(Icons.Outlined.ChevronRight, null) }
                    Text("${journeyDistance(journey, state)}  ·  ${duration((journey.endedAt ?: now) - journey.startedAt)}${if (journey.interrupted) "  ·  Kesinti var" else ""}", color = Muted)
                    if (journey.status == JourneyStatus.TEMPORARY) {
                        val deadline = TrackingPolicy.automaticCandidateDeadline(journey)
                        Text(if (deadline != null) "500 metre bekleniyor · Sıfırlanmaya ${countdown(deadline - now)}" else "Geçici kayıt · ${duration((journey.expiresAt ?: now) - now)} sonra silinir", style = MaterialTheme.typography.bodySmall)
                        Row { TextButton(onClick = { onConfirm(journey) }) { Text("Sakla") }; TextButton(onClick = { onReject(journey) }) { Text("Sil") } }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("Yeni yolculuk") } }
    }
}

@Composable
private fun JourneyDetail(journey: Journey, state: DiaryState, now: Long, onEdit: () -> Unit, onConfirm: () -> Unit, onFinish: () -> Unit, onSwitch: () -> Unit, onAddPlace: () -> Unit, onPhoto: () -> Unit, onVisit: (Visit) -> Unit, onDelete: () -> Unit, onDeletePhoto: (Photo) -> Unit, onEditPhoto: (Photo) -> Unit, onExportGpx: () -> Unit, onOpenGpsTraces: () -> Unit, onSharePhotos: () -> Unit, listState: LazyListState = rememberLazyListState()) {
    val prepared = state.prepared(journey)
    val visits = state.visits.filter { it.journeyId == journey.id }.sortedBy { it.visitedAt }
    LazyColumn(state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading(journey.title.ifBlank { journey.transport.label() }, date(journey.startedAt)) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { ModeBadge(journey.transport); Spacer(Modifier.width(12.dp)); Text(journey.transport.label(), Modifier.weight(1f)); IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Yolculuğu düzenle") } } }
        if (journey.interrupted) item { Notice("Konum kaydında kesinti var. Eksik bölüm rotaya eklenmedi.") }
        item { DiaryMap(emptyList(), state.places.filter { p -> visits.any { it.placeId == p.id } }, Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(24.dp)), journeyId = journey.id, analysis = if (prepared == null) state.analysis.copy(pending = true) else state.analysis) }
        if (journey.status == JourneyStatus.TEMPORARY) item { Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Bu yolculuğu sakla") } }
        if (journey.endedAt == null) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onSwitch, Modifier.weight(1f)) { Text("Tür değiştir") }; Button(onClick = onFinish, Modifier.weight(1f)) { Icon(Icons.Outlined.Stop, null); Text("Bitir") } } }
        item { JourneyStatsPanel(journey, prepared?.stats, state.analysisLabel) }
        TrackingPolicy.automaticCandidateDeadline(journey)?.let { deadline ->
            item { Notice("Otomatik kayıt için 500 metre bekleniyor · Sıfırlanmaya ${countdown(deadline - now)}") }
        }
        item { JourneyHealthPanel(journey, state, now) }
        item { RecordedCurvePanel(journey, state) }
        item { LeanJourneyReportPanel(journey) }
        if (journey.endedAt != null && journey.status == JourneyStatus.CONFIRMED) item { OutlinedButton(onClick = onExportGpx, modifier = Modifier.fillMaxWidth()) { Text("GPX rotasını dışa aktar") } }
        if (osmGpsEligibleJourney(journey)) item {
            OutlinedButton(onClick = onOpenGpsTraces, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("journey-osm-gps-traces")) {
                Text("OSM'ye GPS izi gönder")
            }
        }
        if (journey.note.isNotBlank()) item { Text(journey.note) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onAddPlace, Modifier.weight(1f)) { Icon(Icons.Outlined.AddLocationAlt, null); Text("Yer ekle") }; OutlinedButton(onClick = onPhoto, Modifier.weight(1f)) { Icon(Icons.Outlined.AddAPhoto, null); Text("Fotoğraf") } } }
        item { Text("Yol üzerindeki anlar", style = MaterialTheme.typography.titleLarge) }
        if (visits.isEmpty()) item { Text("Bu yolculuğa henüz bir durak eklemedin.", color = Muted) }
        items(visits, key = { it.id }) { visit -> Surface(onClick = { onVisit(visit) }, shape = RoundedCornerShape(18.dp), color = Color.White) { Row(Modifier.fillMaxWidth().padding(16.dp)) { Icon(Icons.Outlined.Place, null, tint = Forest); Spacer(Modifier.width(12.dp)); Column { Text(state.places.firstOrNull { it.id == visit.placeId }?.name ?: "Yer", style = MaterialTheme.typography.titleMedium); Text(date(visit.visitedAt), style = MaterialTheme.typography.bodySmall, color = Muted) } } } }
        item { PhotoGrid(state.photos.filter { it.journeyId == journey.id }, onDeletePhoto, onEditPhoto) }
        if (state.photos.any { it.journeyId == journey.id }) item { TextButton(onClick = onSharePhotos) { Text("Fotoğrafları paylaş") } }
        item { TextButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, null); Text("Yolculuğu sil") } }
    }
}

@Composable
internal fun PlaceDetail(place: Place, state: DiaryState, onEdit: () -> Unit, onMaps: () -> Unit, onContribute: () -> Unit,
    onCorrect: (() -> Unit)?, onAddVisit: () -> Unit, onEditVisit: (Visit) -> Unit, onShare: (Visit) -> Unit, onPhoto: (Visit) -> Unit,
    onDelete: () -> Unit, onDeleteVisit: (Visit) -> Unit, onDeletePhoto: (Photo) -> Unit, onEditPhoto: (Photo) -> Unit,
    onSurvey: (() -> Unit)? = null,
    publicInformation: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState()) {
    val visits = state.visits.filter { it.placeId == place.id }.sortedByDescending { it.visitedAt }
    LazyColumn(state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        publicInformation?.let { content -> item(key = "public-osm-information") { content() } }
        item { HorizontalDivider(); Text("Kişisel yer günlüğü", style = MaterialTheme.typography.titleLarge); Text("Bu yere verdiğin ad", style = MaterialTheme.typography.bodySmall) }
        item { Row(verticalAlignment = Alignment.Top) { Column(Modifier.weight(1f)) { PageHeading(place.name, "${visits.size} ziyaret · Kişisel yer günlüğü") }; IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Yer adını düzenle") } } }
        if (place.latitude != null && place.longitude != null) item { DiaryMap(emptyList(), listOf(place), Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp))) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMaps) { Text("Başka haritada aç") }
                OutlinedButton(onClick = onContribute) { Text("Harita notu yaz") }
                onCorrect?.takeIf { publicInformation == null }?.let { OutlinedButton(onClick = it) { Text("OSM mekânını düzelt") } }
                onSurvey?.takeIf { publicInformation == null }?.let { OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("osm-place-survey")) { Text("Yerinde tamamla") } }
                Button(onClick = onAddVisit) { Text("Yeni ziyaret") }
            }
        }
        items(visits, key = { it.id }) { visit ->
            Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(date(visit.visitedAt), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); IconButton(onClick = { onEditVisit(visit) }) { Icon(Icons.Outlined.EditNote, "Notu düzenle") } }
                Text(visit.note.ifBlank { "Bu ziyaret için bir not ekleyebilirsin." }, color = if (visit.note.isBlank()) Muted else Ink)
                PhotoGrid(state.photos.filter { it.visitId == visit.id }, onDeletePhoto, onEditPhoto)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { onPhoto(visit) }) { Icon(Icons.Outlined.AddAPhoto, null); Text("Fotoğraf") }; TextButton(onClick = { onShare(visit) }, enabled = state.photos.any { it.visitId == visit.id }) { Icon(Icons.Outlined.IosShare, null); Text("Fotoğrafları paylaş") }; IconButton(onClick = { onDeleteVisit(visit) }) { Icon(Icons.Outlined.DeleteOutline, "Ziyareti sil") } }
            } }
        }
        item { TextButton(onClick = onDelete) { Text("Yeri ve ziyaretlerini sil", color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun SettingsScreen(settings: TrackerSettings, refresh: Int, busy: Boolean, onDetection: () -> Unit, onRepairDetection: () -> Unit, onStopMinutes: (Int) -> Unit, onPermissions: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, onContributions: () -> Unit, onMessage: (String) -> Unit, onCommunity: () -> Unit, listState: LazyListState = rememberLazyListState()) {
    val context = LocalContext.current
    val photoSize = remember(refresh) { File(context.filesDir, "photos").walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024) }
    LazyColumn(state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageHeading("Senin günlüğün,\nsenin kontrolün.", "Kayıtlar yalnızca bu telefonda saklanır.") }
        item { Surface(shape = RoundedCornerShape(22.dp), color = Color.White) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Hareketi fark et", style = MaterialTheme.typography.titleMedium); Text("15 dakikada 500 metreyi tamamlayınca kaydet", style = MaterialTheme.typography.bodySmall, color = Muted) }; Switch(settings.enabled, { onDetection() }) }
            Text("15 dakika içinde 500 metre dolmazsa geçici ölçüm sıfırlanır. Bu süre içindeki dur-kalklar mesafeyi sıfırlamaz. Kayda dönüşen yolculuklar ve elle başlattığın kayıtlar bu sınırdan etkilenmez. Araba, motosiklet ve yolcu ayrımını sen seçersin.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            TrackingDiagnosticsPanel(settings, refresh, onRepairDetection)
            settings.lastActivityEvent?.let { Text("Son algılama: $it\n${date(settings.lastActivityEventAt)}", style = MaterialTheme.typography.bodySmall, color = Muted) }
            settings.lastDetectionDecision?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
            TextButton(onClick = onPermissions) { Text("Android izinlerini yönet") }
        } } }
        item { Text("Durak önerisi", style = MaterialTheme.typography.titleMedium); Text("Hareketsizlikten sonra hatırlat. Yolculuk kendiliğinden bitmez.", color = Muted); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(5, 10, 15).forEach { minutes -> FilterChip(settings.stopMinutes == minutes, { onStopMinutes(minutes) }, { Text("$minutes dk") }) } } }
        item { HorizontalDivider() }
        item { Text("Arşivin", style = MaterialTheme.typography.titleLarge); Text("Fotoğraflar: $photoSize MB · Otomatik bulut yedeği kapalı", color = Muted) }
        item { Text("Telefon değiştirirken veya uygulamayı kaldırmadan önce dosyaya yedek al. Yedek, özel konumlarını ve fotoğraflarını içerir.", style = MaterialTheme.typography.bodyMedium) }
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null); Text("Yedek dosyası oluştur") }; OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileUpload, null); Text("Yedekten geri yükle") }; if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) } }
        item { HorizontalDivider() }
        item { HealthSettingsPanel(onMessage) }
        item { LeanSetupPanel(onMessage) }
        item { OsmSettingsPanel(onContributions, onMessage) }
        item { OutlinedButton(onClick = onCommunity, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Forum, null); Spacer(Modifier.width(8.dp)); Text("OSM Topluluğu") } }
        item { Text("İz · ${BuildConfig.VERSION_NAME}\nKüçük yollar, güzel anılar.", style = MaterialTheme.typography.bodySmall, color = Muted) }
    }
}

@Composable
private fun TextEditor(title: String, initial: String, initialNote: String?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initial) }; var note by remember { mutableStateOf(initialNote.orEmpty()) }
    val working = { listOf(name, note) }
    val baseline = listOf(initial, initialNote.orEmpty())
    val back = rememberUiEditExit("text-editor", working(), baseline, currentWorking = working,
        discardUnsaved = { name = initial; note = initialNote.orEmpty() }, exit = onDismiss)
    AlertDialog(onDismissRequest = back, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Ad") }); if (initialNote != null) OutlinedTextField(note, { note = it }, label = { Text("Özel not") }, minLines = 3) } }, confirmButton = { TextButton(onClick = { onSave(name.trim(), note.trim()) }, enabled = name.isNotBlank()) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
private fun PhotoGrid(photos: List<Photo>, onDelete: (Photo) -> Unit, onEdit: (Photo) -> Unit) {
    if (photos.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEach { photo -> Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).clickable { onEdit(photo) }) { AsyncImage(File(LocalContext.current.filesDir, photo.relativePath), photo.caption.ifBlank { "Günlük fotoğrafı" }, Modifier.fillMaxSize(), contentScale = ContentScale.Crop); IconButton(onClick = { onDelete(photo) }, Modifier.align(Alignment.TopEnd).size(30.dp).background(Color.White.copy(alpha = .9f), CircleShape)) { Icon(Icons.Outlined.Close, "Fotoğrafı sil", Modifier.size(16.dp)) } } }
    }
}
@Composable private fun PageHeading(title: String, subtitle: String) { Text(title, style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(8.dp)); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium) }
@Composable private fun Stat(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = 1.sp); Spacer(Modifier.height(6.dp)); Text(value, style = MaterialTheme.typography.titleLarge) } }
@Composable private fun ModeBadge(transport: Transport) { Icon(transport.icon(), null, tint = transport.accentColor(), modifier = Modifier.size(44.dp).background(transport.badgeColor(), RoundedCornerShape(14.dp)).padding(10.dp)) }
@Composable private fun Notice(text: String) { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) { Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall) } }
@Composable private fun EmptyCard(icon: ImageVector, title: String, body: String) { Surface(shape = RoundedCornerShape(24.dp), color = Color.White) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, Modifier.size(42.dp), tint = Forest); Text(title, style = MaterialTheme.typography.titleLarge); Text(body, color = Muted) } } }
