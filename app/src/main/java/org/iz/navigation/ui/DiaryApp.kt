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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import org.iz.navigation.BuildConfig
import org.iz.navigation.data.*
import org.iz.navigation.integration.*
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.tracking.TrackingPolicy
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.iz.navigation.integration.osm.OsmAuthManager
import org.iz.navigation.integration.osm.OsmContributionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun date(value: Long) = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(value))
private fun duration(value: Long): String { val m = value.coerceAtLeast(0) / 60_000; return if (m >= 60) "${m / 60} sa ${m % 60} dk" else "$m dk" }
private fun countdown(value: Long): String { val seconds = (value.coerceIn(0L, TrackingPolicy.AUTOMATIC_WINDOW_MS) + 999) / 1000; return String.format(Locale.forLanguageTag("tr-TR"), "%02d:%02d", seconds / 60, seconds % 60) }
private fun distance(points: List<TrackPoint>) = String.format(Locale.forLanguageTag("tr-TR"), "%.1f", DiaryRules.distanceMeters(points) / 1000) + " km"
private fun journeyDistance(journey: Journey, points: List<TrackPoint>): String =
    TrackingPolicy.automaticCandidateProgress(journey, points)?.let { "${it.toInt()} / 500 m" } ?: distance(points)
private data class PhotoTarget(val journeyId: String? = null, val visitId: String? = null)
private data class TransportAction(val journeyId: String? = null, val split: Boolean = false, val suggestion: Transport = Transport.WALK)
private data class Deletion(val text: String, val action: suspend () -> Unit)

@Composable
fun DiaryApp(incoming: Intent?, consumeIntent: () -> Unit, vm: DiaryViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val media = remember { MediaStoreHelper(context) }
    val settings = remember { TrackerSettings(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var groupPage by rememberSaveable { mutableStateOf(false) }
    val groups = (context.applicationContext as org.iz.navigation.IzApplication).groups
    val groupState by groups.state.collectAsStateWithLifecycle()
    var selectedJourney by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPlace by rememberSaveable { mutableStateOf<String?>(null) }
    var transportAction by remember { mutableStateOf<TransportAction?>(null) }
    var addPlace by remember { mutableStateOf(false) }
    var targetJourney by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf<GeoCoordinate?>(null) }
    var osmSelection by remember { mutableStateOf<SelectedOsmPlace?>(null) }
    var photoTarget by remember { mutableStateOf<PhotoTarget?>(null) }
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    var captureJourney by rememberSaveable { mutableStateOf<String?>(null) }
    var captureVisit by rememberSaveable { mutableStateOf<String?>(null) }
    var editJourney by remember { mutableStateOf<Journey?>(null) }
    var editVisit by remember { mutableStateOf<Visit?>(null) }
    var editPlace by remember { mutableStateOf<Place?>(null) }
    var editPhoto by remember { mutableStateOf<Photo?>(null) }
    var missingPhotoMetadata by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var photosToShare by remember { mutableStateOf<List<Photo>?>(null) }
    var searchOsm by remember { mutableStateOf(false) }
    var contributionsPage by rememberSaveable { mutableStateOf(false) }
    var communityPage by rememberSaveable { mutableStateOf(false) }
    var communityMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var communityAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var osmActions by rememberSaveable { mutableStateOf(false) }
    var mapEditsPage by rememberSaveable { mutableStateOf(false) }
    var weatherPage by rememberSaveable { mutableStateOf(false) }
    var navigationPage by rememberSaveable { mutableStateOf(false) }
    var navigationEntryId by rememberSaveable { mutableLongStateOf(0L) }
    var navigationStops by remember { mutableStateOf<List<org.iz.navigation.weather.RouteStop>?>(null) }
    var navigationTransport by remember { mutableStateOf<Transport?>(null) }
    var navigationTarget by remember { mutableStateOf<org.iz.navigation.car.NavigationTarget?>(null) }
    var contributionPicker by remember { mutableStateOf(false) }
    var contributionPlace by remember { mutableStateOf<Place?>(null) }
    var contributionEdit by remember { mutableStateOf<ContributionDraft?>(null) }
    var contributionEditId by rememberSaveable { mutableStateOf<String?>(null) }
    var contributionBusy by remember { mutableStateOf(false) }
    var gpxJourney by remember { mutableStateOf<Journey?>(null) }
    var pendingGpxId by rememberSaveable { mutableStateOf<String?>(null) }
    var preparingGpx by remember { mutableStateOf(false) }
    val gpxStore = remember { PendingGpxStore(File(context.noBackupFilesDir, "pending-gpx")) }
    val osmAuth = remember { OsmAuthManager.get(context) }
    val authState by osmAuth.state.collectAsStateWithLifecycle()
    val contributionController = remember { OsmContributionController(vm.repository, osmAuth) }
    var deletion by remember { mutableStateOf<Deletion?>(null) }
    var showDetection by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var showBackupOptions by rememberSaveable { mutableStateOf(false) }
    var includeHealthBackup by rememberSaveable { mutableStateOf(false) }
    val healthManager = remember { (context.applicationContext as org.iz.navigation.IzApplication).healthManager }
    val active = state.active(now)

    fun openDirections(stops: List<org.iz.navigation.weather.RouteStop>? = null, transport: Transport? = active?.transport) {
        navigationStops = stops
        navigationTransport = transport
        navigationTarget = null
        navigationEntryId++
        navigationPage = true
        groupPage = false
        weatherPage = false
        communityPage = false
        selectedJourney = null
        selectedPlace = null
        contributionsPage = false
    }

    fun openAddPlace(journeyId: String? = active?.id, location: GeoCoordinate? = null) {
        targetJourney = journeyId; pin = location; osmSelection = null; addPlace = true
    }
    fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    suspend fun importStoredPhoto(uri: Uri, target: PhotoTarget) {
        val photo = media.importPhoto(uri, target.journeyId, target.visitId)
        try { vm.repository.savePhoto(photo) }
        catch (e: Exception) { vm.repository.photoFile(photo.relativePath).delete(); throw e }
        if (photo.takenAt == null || photo.latitude == null) missingPhotoMetadata = missingPhotoMetadata + photo
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++; vm.message("İzinler güncellendi. İlgili işlemi yeniden seçebilirsin.")
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        val target = photoTarget
        if (target != null && uris.isNotEmpty()) vm.execute("Fotoğraflar günlüğüne eklendi.") {
            for (uri in uris) importStoredPhoto(uri, target)
        }
        photoTarget = null
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = captureUri?.let(Uri::parse)
        val journeyId = captureJourney; val visitId = captureVisit
        captureUri = null
        if (uri != null) vm.execute(if (taken) "Fotoğraf günlüğüne eklendi." else null) {
            try { if (taken) importStoredPhoto(uri, PhotoTarget(journeyId, visitId)) }
            finally { media.deletePendingCapture(uri) }
        }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val withHealth = includeHealthBackup
        includeHealthBackup = false
        if (uri != null) vm.execute("Yedek dosyası kaydedildi.") {
            backupBusy = true
            try { DiaryBackup(context, vm.repository).exportTo(uri, includeHealth = withHealth) } finally { backupBusy = false }
        }
    }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val exportId = pendingGpxId; pendingGpxId = null
        if (exportId != null) vm.execute(if (uri != null) "GPX dosyası kaydedildi." else null) {
            try {
                if (uri != null) withContext(Dispatchers.IO) {
                    val xml = gpxStore.read(exportId)
                    val output = context.contentResolver.openOutputStream(uri) ?: error("Dosya açılamadı.")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(xml) }
                }
            } finally {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { gpxStore.remove(exportId) }
            }
        } else if (uri != null) vm.message("Önizleme kapandı. Rotayı yeniden dışa aktarabilirsin.")
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    LaunchedEffect(Unit) {
        vm.events.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(contributionEditId) {
        if (contributionEdit == null) contributionEdit = contributionEditId?.let { vm.repository.getContribution(it) }
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++; vm.execute { vm.repository.cleanupExpired() } }
    LaunchedEffect(incoming) {
        incoming ?: return@LaunchedEffect
        if (incoming.action == org.iz.navigation.osmcommunity.CommunityNotifications.ACTION_OPEN_MESSAGE) {
            communityMessageId = incoming.getLongExtra(org.iz.navigation.osmcommunity.CommunityNotifications.EXTRA_MESSAGE_ID, -1L)
            communityAccountId = incoming.getLongExtra(org.iz.navigation.osmcommunity.CommunityNotifications.EXTRA_ACCOUNT_ID, -1L)
            communityPage = true
            navigationPage = false
            weatherPage = false
            selectedJourney = null
            selectedPlace = null
            contributionsPage = false
            consumeIntent()
            return@LaunchedEffect
        }
        communityPage = false
        val target = org.iz.navigation.car.NavigationIntents.parse(incoming)
        if (incoming.getBooleanExtra("openNavigation", false) || target != null) {
            groupPage = false
            groups.dismissScreenRequest()
            navigationStops = null
            navigationTransport = null
            navigationEntryId++
            navigationTarget = target
            navigationPage = true
            weatherPage = false
            selectedJourney = null
            selectedPlace = null
            contributionsPage = false
            consumeIntent()
            return@LaunchedEffect
        }
        navigationPage = false
        if (incoming.getBooleanExtra("openWeather", false)) {
            weatherPage = true
            selectedJourney = null
            selectedPlace = null
            contributionsPage = false
            consumeIntent()
            return@LaunchedEffect
        }
        weatherPage = false
        val id = incoming.getStringExtra("journeyId")
        val suggestion = runCatching { Transport.valueOf(incoming.getStringExtra("suggestedTransport").orEmpty()) }.getOrDefault(Transport.WALK)
        when (incoming.getStringExtra("trackingPrompt")) {
            "journey" -> { selectedJourney = id; tab = 1 }
            "confirm" -> {
                val pending = id?.let { vm.repository.getJourney(it) }
                if (pending?.status == JourneyStatus.TEMPORARY && !DiaryRules.isExpired(pending, System.currentTimeMillis())) { selectedJourney = id; tab = 1; transportAction = TransportAction(id, suggestion = suggestion) }
                else vm.message("Bu geçici kayıt artık bulunmuyor veya onay süresi dolmuş.")
            }
            "mode_change" -> {
                if (vm.repository.activeJourney()?.id == id) transportAction = TransportAction(split = true, suggestion = suggestion)
                else vm.message("Bu yolculuk artık devam etmiyor.")
            }
            "stop" -> { selectedJourney = id; tab = 1 }
            "permission" -> { tab = 4; showDetection = true }
        }
        consumeIntent()
    }

    fun returnToMap() {
        tab = 0; selectedJourney = null; selectedPlace = null; contributionsPage = false
        weatherPage = false; navigationPage = false; navigationTarget = null; communityPage = false
        communityMessageId = null; communityAccountId = null; groupPage = false
        groups.dismissScreenRequest()
    }
    val homeVisible = (tab == 0 || navigationPage) && selectedJourney == null && selectedPlace == null &&
        !contributionsPage && !weatherPage && !communityPage && !groupPage
    BackHandler(enabled = !homeVisible, onBack = ::returnToMap)
    LaunchedEffect(groupState.screenRequested) { if (groupState.screenRequested) groupPage = true }

    Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!homeVisible) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::returnToMap, modifier = Modifier.testTag("return-home-map")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Haritaya dön")
                }
                Text("iz", fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("home-menu")) {
                    Icon(Icons.Outlined.Menu, null); Spacer(Modifier.width(6.dp)); Text("Menü")
                }
            }
            val journey = state.journeys.firstOrNull { it.id == selectedJourney }
            val place = state.places.firstOrNull { it.id == selectedPlace }
            when {
                groupPage -> GroupScreen(groups, onClose = { groupPage = false; groups.dismissScreenRequest() },
                    onOpenRoute = { stops, transport -> groups.dismissScreenRequest(); openDirections(stops, transport) })
                communityPage -> OsmCommunityScreen(onClose = { communityPage = false; communityMessageId = null; communityAccountId = null },
                    initialMessageId = communityMessageId, initialAccountId = communityAccountId,
                    onOpenSettings = { communityPage = false; communityMessageId = null; communityAccountId = null; tab = 4 })
                weatherPage -> WeatherPlannerScreen(onClose = { weatherPage = false }, initialTransport = active?.transport,
                    onDirections = { stops, transport -> openDirections(stops, transport) },
                    onNavigationStarted = { openDirections() })
                contributionsPage -> ContributionsScreen(state.contributions, contributionBusy,
                    onNew = { contributionPlace = null; contributionPicker = true }, onEdit = { contributionEdit = it; contributionEditId = it.id },
                    onCheck = { draft -> vm.execute {
                        contributionBusy = true
                        try { vm.osmReady.await(); val result = if (draft.status == ContributionStatus.UNKNOWN) contributionController.reconcile(draft.id) else contributionController.refreshRemoteStatus(draft.id); vm.message(result.message) }
                        finally { contributionBusy = false }
                    } },
                    onDelete = { draft -> deletion = Deletion("Bu kayıt yalnızca uygulamadaki katkı listenden silinecek. OSM’de yayımlanmış bildirim silinmez.") { vm.repository.deleteContribution(draft.id) } },
                )
                journey != null -> JourneyDetail(journey, state, now,
                    onEdit = { if (journey.status == JourneyStatus.TEMPORARY) transportAction = TransportAction(journey.id) else editJourney = journey }, onConfirm = { transportAction = TransportAction(journey.id) },
                    onFinish = { vm.execute("Yolculuk tamamlandı.") {
                        val navigation = (context.applicationContext as org.iz.navigation.IzApplication).navigation
                        val session = navigation.state.value
                        if (session.journey?.id == journey.id && session.sessionId != null) navigation.finishSession(session.sessionId)
                        else vm.tracker.finish(journey.id)
                    } },
                    onSwitch = { transportAction = TransportAction(split = true) },
                    onAddPlace = { openAddPlace(journey.id) }, onPhoto = { photoTarget = PhotoTarget(journeyId = journey.id) },
                    onVisit = { selectedPlace = it.placeId; selectedJourney = null },
                    onDelete = { deletion = Deletion("Bu yolculuğun rotası ve yalnızca bu yolculuğa bağlı fotoğraflar silinecek. Kaydedilmiş yer ziyaretleri korunacak.") { if (active?.id == journey.id) vm.tracker.finish(journey.id); vm.repository.deleteJourney(journey.id); selectedJourney = null } },
                    onDeletePhoto = { photo -> deletion = Deletion("Fotoğraf uygulamadaki günlükten silinecek.") { vm.repository.deletePhoto(photo.id) } },
                    onEditPhoto = { editPhoto = it },
                    onExportGpx = { gpxJourney = journey },
                    onSharePhotos = { photosToShare = state.photos.filter { it.journeyId == journey.id } },
                )
                place != null -> PlaceDetail(place, state,
                    onEdit = { osmSelection = null; editPlace = place }, onMaps = { vm.execute { MediaShare.openPlace(context, place) } },
                    onContribute = { contributionPlace = place; contributionPicker = true },
                    onAddVisit = { vm.execute("Yeni ziyaret eklendi.") { vm.repository.saveVisit(Visit(placeId = place.id, journeyId = active?.id, visitedAt = now)) } },
                    onEditVisit = { editVisit = it }, onShare = { visit -> photosToShare = state.photos.filter { it.visitId == visit.id } },
                    onPhoto = { photoTarget = PhotoTarget(it.journeyId, it.id) },
                    onDelete = { deletion = Deletion("Bu yer, ziyaretleri, bağlı fotoğrafları silinecek.") { vm.repository.deletePlace(place.id); selectedPlace = null } },
                    onDeleteVisit = { visit -> deletion = Deletion("Bu ziyaret, bağlı fotoğrafları silinecek.") { vm.repository.deleteVisit(visit.id) } },
                    onDeletePhoto = { photo -> deletion = Deletion("Fotoğraf günlükten silinecek.") { vm.repository.deletePhoto(photo.id) } },
                    onEditPhoto = { editPhoto = it },
                )
                tab == 0 || navigationPage -> NavigationScreen(onClose = { navigationPage = false; navigationTarget = null },
                    incomingTarget = navigationTarget, initialStops = navigationStops, initialTransport = navigationTransport,
                    entryId = navigationEntryId, initiallyPlanning = navigationPage,
                    onMenu = { menuOpen = true }, onGroup = { groupPage = true }, onWeather = { weatherPage = true },
                    onPin = { openAddPlace(location = it) })
                tab == 1 -> JourneysScreen(state, now, onJourney = { selectedJourney = it.id }, onStart = { transportAction = TransportAction() }, onConfirm = { transportAction = TransportAction(it.id) }, onReject = { j -> deletion = Deletion("Bu geçici rota silinecek ve açıksa takip duracak.") { vm.tracker.reject(j.id) } })
                tab == 2 -> PlacesScreen(state, onPlace = { selectedPlace = it.id }, onAdd = { openAddPlace() }, onReorder = vm.repository::reorderPlaces)
                tab == 3 -> HeatmapScreen(state)
                tab == 5 -> DiaryStatisticsScreen(state, now, onJourney = { selectedJourney = it.id })
                else -> SettingsScreen(settings, refresh, backupBusy,
                    onDetection = { if (settings.enabled) { vm.tracker.disableDetection(); refresh++ } else showDetection = true },
                    onRepairDetection = { vm.tracker.enableDetection { error -> refresh++; error?.let(vm::message) } },
                    onStopMinutes = { settings.stopMinutes = it; refresh++ },
                    onPermissions = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                    onExport = { includeHealthBackup = false; showBackupOptions = true },
                    onImport = { importBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onContributions = { osmActions = true }, onMessage = vm::message,
                    onCommunity = { communityMessageId = null; communityAccountId = null; communityPage = true },
                )
            }
        }
    }

    if (menuOpen) AlertDialog(onDismissRequest = { menuOpen = false }, title = { Text("Menü") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            fun open(action: () -> Unit) { menuOpen = false; returnToMap(); action() }
            TextButton({ open { tab = 1 } }, Modifier.fillMaxWidth().testTag("menu-journeys")) { Text("Yolculuklar") }
            TextButton({ open { tab = 5 } }, Modifier.fillMaxWidth().testTag("menu-statistics")) { Text("İstatistikler") }
            TextButton({ open { tab = 2 } }, Modifier.fillMaxWidth().testTag("menu-places")) { Text("Yerler") }
            TextButton({ open { tab = 3 } }, Modifier.fillMaxWidth().testTag("menu-heatmap")) { Text("Yoğunluk haritası") }
            TextButton({ open { openAddPlace() } }, Modifier.fillMaxWidth().testTag("menu-add-place")) { Text("Bir yer kaydet") }
            TextButton({ open { communityPage = true } }, Modifier.fillMaxWidth().testTag("menu-community")) { Text("OSM Topluluğu") }
            TextButton({ open { osmActions = true } }, Modifier.fillMaxWidth().testTag("menu-osm")) { Text("OSM'ye katkı") }
            TextButton({ open { weatherPage = true } }, Modifier.fillMaxWidth().testTag("menu-weather")) { Text("Hava durumu") }
            TextButton({ open { tab = 4 } }, Modifier.fillMaxWidth().testTag("menu-settings")) { Text("Ayarlar") }
        }
    }, confirmButton = { TextButton({ menuOpen = false }) { Text("Haritaya dön") } })
    if (osmActions) AlertDialog(onDismissRequest = { osmActions = false }, title = { Text("OpenStreetMap’e katkı") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { osmActions = false; mapEditsPage = true }, Modifier.fillMaxWidth()) { Text("Doğrudan mekân ekle") }
            OutlinedButton(onClick = { osmActions = false; contributionsPage = true }, Modifier.fillMaxWidth()) { Text("Harita notları ve bildirimler") }
        }
    }, confirmButton = { TextButton(onClick = { osmActions = false }) { Text("Kapat") } })
    if (mapEditsPage) Dialog(onDismissRequest = { mapEditsPage = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { MapEditPage(onClose = { mapEditsPage = false }) }
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
                        selectedJourney = navigation.state.value.journey?.id
                    } }
                transportAction = null; tab = 1
            }
        }
    }

    if (addPlace) PlaceEditor(pin, osmSelection, state.places,
        onDismiss = { addPlace = false }, onSearch = { searchOsm = true }, onLocate = {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else vm.execute {
                @Suppress("MissingPermission") val location = LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                if (location == null) vm.message("Konum alınamadı. Haritaya uzun basarak yer seçebilirsin.") else { osmSelection = null; pin = GeoCoordinate(location.latitude, location.longitude) }
            }
        }, onSave = { name, note, existingPlace ->
            vm.execute("Yer ziyaretin kaydedildi.") {
                val place = existingPlace ?: Place(name = name.trim(), latitude = pin?.latitude, longitude = pin?.longitude,
                    osmType = osmSelection?.osmRef?.type, osmId = osmSelection?.osmRef?.id,
                    source = if (osmSelection?.osmRef != null) PlaceSource.OSM else PlaceSource.USER).also { vm.repository.savePlace(it) }
                vm.repository.saveVisit(Visit(placeId = place.id, journeyId = targetJourney, visitedAt = now, note = note.trim()))
                addPlace = false; selectedJourney = null; selectedPlace = place.id; tab = 2
            }
        },
    )
    if (searchOsm) OsmSearchDialog(onDismiss = { searchOsm = false }, onSelect = { osmSelection = it; pin = GeoCoordinate(it.latitude, it.longitude); searchOsm = false })
    photoTarget?.let { target ->
        AlertDialog(onDismissRequest = { photoTarget = null }, title = { Text("Bir anı ekle") }, text = { Text("Seçtiğin fotoğrafların bir kopyası yalnızca bu uygulamada saklanır.") },
            confirmButton = { TextButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Galeriden seç") } },
            dismissButton = { TextButton(onClick = {
                runCatching { val uri = media.createCaptureUri(); captureUri = uri.toString(); captureJourney = target.journeyId; captureVisit = target.visitId; photoTarget = null; camera.launch(uri) }.onFailure { vm.message("Kamera açılamadı: ${it.message}") }
            }) { Text("Fotoğraf çek") } },
        )
    }
    editJourney?.let { j -> JourneyEditor(j, { editJourney = null }) { updated -> vm.execute {
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
        onSave = { updated -> vm.execute { vm.repository.savePlace(updated); editPlace = null } },
    ) }
    editVisit?.let { v -> VisitEditor(v, { editVisit = null }) { note -> vm.execute { vm.repository.saveVisit(v.copy(note = note)); editVisit = null } } }
    val photoEditor = editPhoto ?: missingPhotoMetadata.firstOrNull()
    photoEditor?.let { photo ->
        fun closePhoto() { if (editPhoto != null) editPhoto = null else missingPhotoMetadata = missingPhotoMetadata.drop(1) }
        PhotoEditor(photo, onDismiss = { closePhoto() }) { updated -> vm.execute("Fotoğraf bilgileri kaydedildi.") { vm.repository.savePhoto(updated); closePhoto() } }
    }
    photosToShare?.let { photos -> PhotoShareSheet(photos, onDismiss = { photosToShare = null },
        onShare = { selected -> vm.execute { MediaShare.sharePhotos(context, selected) } },
        onGallery = { selected -> vm.execute { val count = MediaShare.savePhotosToGallery(context, selected); vm.message("$count fotoğraf, dosyaya ekli konum ve çekim bilgileri kaldırılarak galeriye kaydedildi.") } }) }
    if (contributionPicker) {
        val source = contributionPlace?.takeIf { it.source == PlaceSource.OSM && it.osmType != null && it.osmId != null && it.latitude != null && it.longitude != null }
        ContributionLocationPicker(source?.let { SelectedOsmPlace(it.name, it.latitude!!, it.longitude!!, OsmRef(it.osmType!!, it.osmId!!)) }, contributionPlace?.id,
            onDismiss = { contributionPicker = false }, onChoose = { draft -> vm.execute {
                vm.osmReady.await(); vm.repository.saveContribution(draft)
                contributionPicker = false; contributionEdit = draft; contributionEditId = draft.id
            } })
    }
    contributionEdit?.let { draft -> ContributionEditor(draft, authState.user != null, contributionBusy || authState.busy,
        onDismiss = { contributionEdit = null; contributionEditId = null }, onSave = { updated -> vm.execute("Gözlem taslağı saklandı.") { vm.osmReady.await(); vm.repository.saveContribution(updated); contributionEdit = null; contributionEditId = null; contributionsPage = true } },
        onPublish = { updated -> vm.execute {
            contributionBusy = true
            try { vm.osmReady.await(); vm.repository.saveContribution(updated); val result = contributionController.send(updated.id); contributionEdit = null; contributionEditId = null; contributionsPage = true; vm.message(result.message) }
            finally { contributionBusy = false }
        } },
        onLogin = { updated -> vm.execute {
            vm.osmReady.await(); vm.repository.saveContribution(updated)
            contributionEdit = updated; contributionEditId = updated.id
            osmAuth.startLogin(context.findActivity() ?: error("Giriş ekranı açılamadı."))
        } },
        authError = authState.error,
    ) }
    gpxJourney?.let { journey -> GpxExportSheet(journey, state.points.filter { it.journeyId == journey.id }, { gpxJourney = null }) { xml ->
        if (!preparingGpx) {
            preparingGpx = true
            vm.execute {
                try {
                    pendingGpxId = withContext(Dispatchers.IO) { gpxStore.prepare(xml) }
                    gpxJourney = null
                    exportGpx.launch("iz-rota-${journey.startedAt}.gpx")
                } finally { preparingGpx = false }
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
        restoreUri = null
        vm.execute("Yedek geri yüklendi.") {
            backupBusy = true
            val wasEnabled = settings.enabled
            // Keep the existing subscription, but ignore new detection events during replacement.
            settings.enabled = false
            try {
                healthManager.withDiaryReplacement { DiaryBackup(context, vm.repository).importFrom(uri) { vm.tracker.stopAfterRestore() } }
                selectedJourney = null; selectedPlace = null
            }
            finally { settings.enabled = wasEnabled; backupBusy = false; refresh++ }
        }
    }) { Text("Geri yükle") } }, dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Vazgeç") } }) }
    if (showBackupOptions) AlertDialog(onDismissRequest = { showBackupOptions = false },
        title = { Text("Yedek içeriği") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Yolculuklar, yerler, özel notlar ve fotoğraflar yedeklenir.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(includeHealthBackup, { includeHealthBackup = it })
                Text("Sağlık ölçümlerini de ekle")
            }
            Text("Sağlık ölçümleri varsayılan olarak yedeğe eklenmez.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = {
            showBackupOptions = false
            exportBackup.launch("iz-yedek-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".zip")
        }) { Text("Dosyaya kaydet") } },
        dismissButton = { TextButton(onClick = { showBackupOptions = false }) { Text("Vazgeç") } })
    if (backupBusy) AlertDialog(onDismissRequest = {}, title = { Text("Arşivin hazırlanıyor") }, text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { CircularProgressIndicator(Modifier.size(28.dp)); Text("İşlem tamamlanana kadar lütfen bekle.") } }, confirmButton = {})
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
            DiaryMap(points = state.currentRoute(now), places = state.places, modifier = Modifier.fillMaxSize(), onPlaceClick = onPlace, onMapLongClick = onPin, focusCurrentLocation = true, onOsmPlaceClick = onOsmPlace, followRecordedLocation = active != null && followRoute,
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
                        Text("${journeyDistance(active, state.points.filter { it.journeyId == active.id })} · ${duration(now - active.startedAt)}", color = Muted)
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
                    Text("${journeyDistance(journey, state.points.filter { it.journeyId == journey.id })}  ·  ${duration((journey.endedAt ?: now) - journey.startedAt)}${if (journey.interrupted) "  ·  Kesinti var" else ""}", color = Muted)
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
private fun JourneyDetail(journey: Journey, state: DiaryState, now: Long, onEdit: () -> Unit, onConfirm: () -> Unit, onFinish: () -> Unit, onSwitch: () -> Unit, onAddPlace: () -> Unit, onPhoto: () -> Unit, onVisit: (Visit) -> Unit, onDelete: () -> Unit, onDeletePhoto: (Photo) -> Unit, onEditPhoto: (Photo) -> Unit, onExportGpx: () -> Unit, onSharePhotos: () -> Unit) {
    val points = state.points.filter { it.journeyId == journey.id }
    val visits = state.visits.filter { it.journeyId == journey.id }.sortedBy { it.visitedAt }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading(journey.title.ifBlank { journey.transport.label() }, date(journey.startedAt)) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { ModeBadge(journey.transport); Spacer(Modifier.width(12.dp)); Text(journey.transport.label(), Modifier.weight(1f)); IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Yolculuğu düzenle") } } }
        if (journey.interrupted) item { Notice("Konum kaydında kesinti var. Eksik bölüm rotaya eklenmedi.") }
        item { DiaryMap(points, state.places.filter { p -> visits.any { it.placeId == p.id } }, Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(24.dp))) }
        if (journey.status == JourneyStatus.TEMPORARY) item { Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Bu yolculuğu sakla") } }
        if (journey.endedAt == null) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onSwitch, Modifier.weight(1f)) { Text("Tür değiştir") }; Button(onClick = onFinish, Modifier.weight(1f)) { Icon(Icons.Outlined.Stop, null); Text("Bitir") } } }
        item { JourneyStatsPanel(journey, points, now) }
        TrackingPolicy.automaticCandidateProgress(journey, points)?.let { progress ->
            item { Notice("Otomatik kayıt için ${progress.toInt()} / 500 m · Sıfırlanmaya ${countdown(requireNotNull(TrackingPolicy.automaticCandidateDeadline(journey)) - now)}") }
        }
        item { JourneyHealthPanel(journey, state, now) }
        if (journey.endedAt != null && journey.status == JourneyStatus.CONFIRMED) item { OutlinedButton(onClick = onExportGpx, modifier = Modifier.fillMaxWidth()) { Text("GPX rotasını dışa aktar") } }
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
private fun PlaceDetail(place: Place, state: DiaryState, onEdit: () -> Unit, onMaps: () -> Unit, onContribute: () -> Unit, onAddVisit: () -> Unit, onEditVisit: (Visit) -> Unit, onShare: (Visit) -> Unit, onPhoto: (Visit) -> Unit, onDelete: () -> Unit, onDeleteVisit: (Visit) -> Unit, onDeletePhoto: (Photo) -> Unit, onEditPhoto: (Photo) -> Unit) {
    val visits = state.visits.filter { it.placeId == place.id }.sortedByDescending { it.visitedAt }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(verticalAlignment = Alignment.Top) { Column(Modifier.weight(1f)) { PageHeading(place.name, "${visits.size} ziyaret · Kişisel yer günlüğü") }; IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Yer adını düzenle") } } }
        if (place.latitude != null && place.longitude != null) item { DiaryMap(emptyList(), listOf(place), Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp))) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onMaps) { Text("Başka haritada aç") }; OutlinedButton(onClick = onContribute) { Text("Düzeltme bildir") }; Button(onClick = onAddVisit) { Text("Yeni ziyaret") } } }
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
private fun SettingsScreen(settings: TrackerSettings, refresh: Int, busy: Boolean, onDetection: () -> Unit, onRepairDetection: () -> Unit, onStopMinutes: (Int) -> Unit, onPermissions: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, onContributions: () -> Unit, onMessage: (String) -> Unit, onCommunity: () -> Unit) {
    val context = LocalContext.current
    val photoSize = remember(refresh) { File(context.filesDir, "photos").walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024) }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
        item { OsmSettingsPanel(onContributions, onMessage) }
        item { OutlinedButton(onClick = onCommunity, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Forum, null); Spacer(Modifier.width(8.dp)); Text("OSM Topluluğu") } }
        item { Text("İz · ${BuildConfig.VERSION_NAME}\nKüçük yollar, güzel anılar.", style = MaterialTheme.typography.bodySmall, color = Muted) }
    }
}

@Composable
private fun TextEditor(title: String, initial: String, initialNote: String?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initial) }; var note by remember { mutableStateOf(initialNote.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Ad") }); if (initialNote != null) OutlinedTextField(note, { note = it }, label = { Text("Özel not") }, minLines = 3) } }, confirmButton = { TextButton(onClick = { onSave(name.trim(), note.trim()) }, enabled = name.isNotBlank()) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
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


