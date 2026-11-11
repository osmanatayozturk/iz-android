package org.iz.navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.iz.navigation.IzApplication
import org.iz.navigation.data.*
import org.iz.navigation.integration.DiaryMap
import org.iz.navigation.integration.OsmPlaces
import org.iz.navigation.integration.OsmServiceSettings
import org.iz.navigation.integration.OsmSurveySnapshot
import org.iz.navigation.integration.osm.OsmAuthManager
import org.iz.navigation.integration.osm.OsmCredentials
import org.iz.navigation.ui.history.LocalUiEntryKey
import org.iz.navigation.ui.history.LocalUiHistory
import org.iz.navigation.weather.WeatherCoordinate
import java.time.LocalDate

@Composable
internal fun OsmSurveyScreen(repository: DiaryRepository, onClose: () -> Unit, onOpen: (SurveyVenue) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as IzApplication
    val auth = remember(app) { OsmAuthManager.get(app) }
    val observedAuth by auth.state.collectAsStateWithLifecycle()
    val session by auth.communitySessionSource.sessions.collectAsStateWithLifecycle()
    val accountId = observedAuth.user?.id
    val accountKey = accountId?.toString()
    val places = remember(app) { OsmPlaces(app) }
    val settings = remember(app) { OsmServiceSettings(app) }
    val reads = LocalOsmSurveyReads.current
    var endpoint by remember { mutableStateOf(settings.read().overpass) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { endpoint = settings.read().overpass }
    val receiptFlow = remember(repository) {
        repository.mapEdits.map<List<MapEditDraft>, List<MapEditDraft>?> { it }
    }
    val drafts by receiptFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val history = LocalUiHistory.current
    val entry = LocalUiEntryKey.current
    var snapshot by remember(accountId, endpoint) { mutableStateOf<OsmSurveySnapshot?>(null) }
    var error by remember(accountId, endpoint) { mutableStateOf<String?>(null) }
    var loading by remember(accountId, endpoint) { mutableStateOf(false) }
    var request by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableLongStateOf(0L) }
    var firstEntry by remember { mutableStateOf(true) }
    var permissionOwner by remember { mutableStateOf<Long?>(null) }
    var permissionSession by remember { mutableStateOf<OsmCredentials?>(null) }
    var permissionEndpoint by remember { mutableStateOf<String?>(null) }
    var permissionAccountId by remember { mutableStateOf<Long?>(null) }

    fun permitted() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun onEntry() = history == null || history.current.key == entry
    fun refresh() {
        if (loading || auth.state.value.busy || !onEntry()) return
        val requestAccountId = auth.state.value.user?.id
        val owner = auth.currentCredentials()
        val expectedEndpoint = settings.read().overpass
        val token = ++generation
        loading = true
        error = null
        request = scope.launch {
            fun current() = token == generation && onEntry() && auth.currentCredentials() === owner &&
                !auth.state.value.busy && auth.state.value.user?.id == requestAccountId &&
                settings.read().overpass == expectedEndpoint
            try {
                if (reads != null) {
                    val found = reads.nearby(requestAccountId?.toString(), ::current)
                    ensureActive()
                    if (current()) snapshot = found
                    return@launch
                }
                if (!permitted()) {
                    error = "Yakındaki yerler için konum izni gerekiyor. Son saklanan yerleri görebilirsin."
                    return@launch
                }
                val origin = app.navigation.quickOrigin()
                ensureActive()
                if (!current()) return@launch
                app.navigation.validateQuickOrigin(origin)
                val found = places.surveyNearby(origin.fix.coordinate, requestAccountId?.toString()) {
                    current() && permitted() && runCatching { app.navigation.validateQuickOrigin(origin) }.isSuccess
                }
                ensureActive()
                if (current() && permitted() && runCatching { app.navigation.validateQuickOrigin(origin) }.isSuccess)
                    snapshot = found
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current()) error = "Yakındaki yerler alınamadı. Konumu ve bağlantını kontrol edip yeniden dene."
            } finally {
                if (token == generation) { loading = false; request = null }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val expected = permissionOwner
        val owner = permissionSession
        val expectedEndpoint = permissionEndpoint
        val expectedAccountId = permissionAccountId
        permissionOwner = null
        permissionSession = null
        permissionEndpoint = null
        permissionAccountId = null
        if (expected == generation && onEntry() && auth.currentCredentials() === owner &&
            !auth.state.value.busy && auth.state.value.user?.id == expectedAccountId &&
            settings.read().overpass == expectedEndpoint) {
            if (permitted()) refresh() else error = "Konum izni verilmedi. Saklanan yerler korunuyor."
        }
    }
    LaunchedEffect(session, accountId, endpoint) {
        val token = ++generation
        request?.cancel()
        request = null
        loading = false
        permissionOwner = null
        permissionSession = null
        permissionEndpoint = null
        permissionAccountId = null
        error = null
        val owner = auth.currentCredentials()
        try {
            val cached = reads?.cached?.invoke(accountKey) ?: if (reads == null) places.cachedSurvey(accountKey) else null
            if (token == generation && onEntry() && auth.currentCredentials() === owner &&
                auth.state.value.user?.id == accountId && settings.read().overpass == endpoint) {
                snapshot = cached?.takeIf { it.endpoint == endpoint }
                if (firstEntry) { firstEntry = false; refresh() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (token == generation) error = "Saklanan yakın yerler okunamadı. Yeniden dene."
        }
    }
    DisposableEffect(Unit) { onDispose { generation++; request?.cancel() } }
    val visible = surveyVisibleSnapshot(snapshot, drafts, accountId)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Yerinde tamamla", Modifier.weight(1f).padding(vertical = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Geri") }
        }
        if (!permitted()) TextButton(onClick = {
            permissionOwner = generation
            permissionSession = auth.currentCredentials()
            permissionEndpoint = settings.read().overpass
            permissionAccountId = auth.state.value.user?.id
            permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }, enabled = !loading && permissionOwner == null && !observedAuth.busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("osm-survey-location-permission")) {
            Text("Konum izni ver")
        }
        OsmSurveyNearbyContent(visible, loading || drafts == null, error, onRefresh = ::refresh, onOpen = {
            if (onEntry() && !loading && !auth.state.value.busy && auth.state.value.user?.id == accountId &&
                settings.read().overpass == endpoint) onOpen(it)
        })
    }
}

/** An uncollected Room flow is unknown, not an empty receipt store. */
internal fun surveyVisibleSnapshot(snapshot: OsmSurveySnapshot?, receipts: List<MapEditDraft>?,
    accountId: Long?, today: LocalDate = LocalDate.now()): OsmSurveySnapshot? {
    if (receipts == null) return null
    return snapshot?.copy(venues = snapshot.venues.map { applySurveyReceipts(it, receipts, accountId) }
        .filter { it.tasks(today).isNotEmpty() })
}

@Composable
internal fun OsmSurveyNearbyContent(
    snapshot: OsmSurveySnapshot?, loading: Boolean, error: String?,
    onRefresh: () -> Unit, onOpen: (SurveyVenue) -> Unit, today: LocalDate = LocalDate.now(),
    mapContent: @Composable (List<SurveyVenue>, (SurveyVenue) -> Unit) -> Unit = { venues, open ->
        val points = venues.map { venue ->
            Place(id = "${venue.ref.type}:${venue.ref.id}", name = venue.place.name, latitude = venue.latitude,
                longitude = venue.longitude, osmType = venue.ref.type, osmId = venue.ref.id, source = PlaceSource.OSM)
        }
        DiaryMap(emptyList(), points, Modifier.fillMaxWidth().height(180.dp).testTag("osm-survey-native-map"), expandable = false,
            onPlaceClick = { place ->
                venues.firstOrNull { it.ref.type == place.osmType && it.ref.id == place.osmId }?.let(open)
            })
    },
) {
    val venues = snapshot?.venues.orEmpty().filter { it.tasks(today).isNotEmpty() }
    Column(Modifier.fillMaxSize().testTag("osm-survey-nearby")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("1 km içindeki yerler", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)
                .testTag(if (error == null) "osm-survey-refresh" else "osm-survey-retry")) {
                Text(if (error == null) "Yenile" else "Yeniden dene")
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        snapshot?.let {
            Text("Son yenileme: ${osmGpsDate(it.refreshedAt)}", Modifier.padding(horizontal = 16.dp)
                .testTag("osm-survey-cached-at"), style = MaterialTheme.typography.bodySmall)
            if (it.capped) Text("Bazı yakın yerler gösterilemiyor", Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        mapContent(venues, onOpen)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("osm-survey-venues"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (venues.isEmpty()) item { Text(if (snapshot == null) "Konum alıp yakındaki yerleri yenileyebilirsin." else "Yakında kontrol bekleyen yer bulunamadı.") }
            items(venues, key = { "${it.ref.type}:${it.ref.id}" }) { venue ->
                OutlinedCard(onClick = { onOpen(venue) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("osm-survey-venue-${venue.ref.type}-${venue.ref.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(venue.place.name.ifBlank { "OSM mekânı" }, style = MaterialTheme.typography.titleMedium)
                        snapshot?.origin?.let {
                            Text(osmGpsDistance(org.iz.navigation.weather.WeatherEngine.distanceMeters(
                                it, org.iz.navigation.weather.WeatherCoordinate(venue.latitude, venue.longitude))),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        for (task in venue.tasks(today)) {
                            Text("${surveyFieldLabel(task.field.key)} · ${surveyStatusLabel(task.status)}", style = MaterialTheme.typography.bodyMedium)
                            Text("Mevcut: ${task.value ?: "Eksik"}", style = MaterialTheme.typography.bodySmall)
                            Text("Son kontrol: ${task.checkedOn ?: "Bilinmiyor"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
