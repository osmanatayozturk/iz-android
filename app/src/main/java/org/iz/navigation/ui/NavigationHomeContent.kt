@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.iz.navigation.R
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.RouteMapMember
import org.iz.navigation.integration.WeatherRouteMap
import org.iz.navigation.navigation.NavigationState
import kotlin.math.roundToInt

/** Keep original positional callers and trailing planner lambdas source-compatible. */
@Composable
internal fun NavigationHomeContent(ui: PhoneDirectionsState, nav: NavigationState, points: List<TrackPoint>,
    members: List<RouteMapMember>, showPlanner: Boolean, weatherLabel: String, onSearch: () -> Unit,
    onRecord: () -> Unit, onGroup: () -> Unit, onMenu: () -> Unit, onWeather: () -> Unit, onMute: () -> Unit,
    onManageSession: () -> Unit, onLocate: () -> Unit, onPin: (GeoCoordinate) -> Unit,
    planner: @Composable () -> Unit) {
    NavigationHomeContent(ui, nav, points, members, showPlanner, weatherLabel, onSearch, onRecord, onGroup,
        onMenu, onWeather, onMute, onManageSession, onLocate, onPin, emptyList(), {}, planner = planner)
}
/** Only the visible cards accept gestures. The map has no scroll or sheet parent. */
@Composable
internal fun NavigationHomeContent(
    ui: PhoneDirectionsState,
    nav: NavigationState,
    points: List<TrackPoint>,
    members: List<RouteMapMember>,
    showPlanner: Boolean,
    weatherLabel: String,
    onSearch: () -> Unit,
    onRecord: () -> Unit,
    onGroup: () -> Unit,
    onMenu: () -> Unit,
    onWeather: () -> Unit,
    onMute: () -> Unit,
    onManageSession: () -> Unit,
    onLocate: () -> Unit,
    onPin: (GeoCoordinate) -> Unit,
    shortcuts: List<org.iz.navigation.data.DestinationShortcut>,
    onShortcut: (org.iz.navigation.data.DestinationShortcutKind) -> Unit,
    voiceFault: String? = null,
    showDashboard: Boolean = false,
    onDashboard: (() -> Unit)? = null,
    dashboard: (@Composable () -> Unit)? = null,
    editPreview: org.iz.navigation.navigation.RouteEditPreview? = null,
    onResumePlannedBreak: () -> Unit = {},
    plannedBreakResumeEnabled: Boolean = false,
    layersRequest: Int = 0,
    onRecoverRecording: (() -> Unit)? = null,
    onOsmPlaceClick: ((org.iz.navigation.data.SelectedOsmPlace) -> Unit)? = null,
    placeInformationIdentity: Any? = null,
    planner: @Composable () -> Unit,
) {
    val plannerPresentation = navigationMapPresentation(ui, nav, points, showPlanner, editPreview)
    val liveRouteBehindPlanner = nav.route?.takeIf { showPlanner && nav.guidance && plannerPresentation.route == null }
    val presentation = if (liveRouteBehindPlanner != null) plannerPresentation.copy(
        route = liveRouteBehindPlanner, stops = liveRouteBehindPlanner.stops, cameraIdentity = "session:${nav.sessionId}",
    ) else plannerPresentation
    val live = presentation.sessionActive
    val route = presentation.route
    val current = presentation.coordinate.takeUnless { nav.approximateOrigin != null && nav.gpsStale }
    val preciseLocationAllowed = if (nav.recordingPending) rememberPreciseLocationPermission() else false
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val dashboardVisible = showDashboard && dashboard != null
    val baseShowBrand = !live && !showPlanner && !dashboardVisible
    val showShortcuts = !nav.guidance && !showPlanner && !dashboardVisible && (!live || shortcuts.isNotEmpty())
    val showDestinationHeader = !nav.guidance && !dashboardVisible
    val externalHeader = !showPlanner && !dashboardVisible && nav.guidance
    var shortcutMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var headerMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var panelsMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var dashboardMeasurement by remember { mutableStateOf<Pair<List<Any?>, IntSize>?>(null) }
    var dashboardButtonMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    val dashboardEpoch = remember(dashboardVisible) { Any() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("navigation-home")) {
        val shortIdle = baseShowBrand && (maxWidth > maxHeight || maxHeight < 400.dp)
        val dashboardAtSide = dashboardVisible && maxWidth > maxHeight
        val dashboardKey = listOf(maxWidth, maxHeight, density.density, density.fontScale, dashboardAtSide, dashboardEpoch)
        val measuredDashboard = dashboardMeasurement?.takeIf { it.first == dashboardKey }?.second
        val desiredSideWidth = (maxWidth * .44f).coerceAtMost(400.dp).coerceAtLeast(minOf(240.dp, maxWidth))
        val desiredBottomHeight = (maxHeight * .52f).coerceAtMost((maxHeight - 100.dp).coerceAtLeast(0.dp))
        val dashboardSideWidth = if (dashboardAtSide) measuredDashboard?.let { with(density) { it.width.toDp() } } ?: desiredSideWidth else 0.dp
        val dashboardBottomHeight = if (dashboardVisible && !dashboardAtSide) measuredDashboard?.let { with(density) { it.height.toDp() } } ?: desiredBottomHeight else 0.dp
        val dashboardButtonVisible = false
        val dashboardButtonInMap = false
        val dashboardButtonKey = listOf(maxWidth, maxHeight, density.density, density.fontScale, dashboardEpoch)
        val dashboardButtonHeight = if (dashboardButtonInMap) dashboardButtonMeasurement?.takeIf { it.first == dashboardButtonKey }?.second ?: 48.dp else 0.dp
        val shortLandscape = maxWidth > maxHeight && maxHeight < 480.dp
        // At 320dp, leave 192dp for the native map. Its existing local top/bottom margins then leave 56dp.
        val headerBudget = if (shortLandscape) minOf(maxHeight * .60f, (maxHeight - 192.dp).coerceAtLeast(maxHeight * .40f))
            else maxHeight * .60f
        // Bounds from an earlier header/panel/viewport must never drive this frame's camera.
        // GPS coordinates alone do not invalidate geometry or remount the native map.
        val layoutKey = listOf(maxWidth, maxHeight, density.density, density.fontScale, showPlanner, dashboardVisible, dashboardAtSide, dashboardSideWidth, dashboardBottomHeight, dashboardButtonHeight,
            showDestinationHeader, live, nav.guidance, nav.recording, nav.recordingPending, nav.simulation, nav.gpsStale,
            nav.approximateOrigin != null, preciseLocationAllowed,
            nav.locationActive, nav.sessionTransport, nav.message, ui.message, weatherLabel, current == null, ui.locating, voiceFault, presentation.editPreviewLabel,
            showShortcuts)
        // Slot labels are single-line and keep the same row dimensions when assignments change.
        val shortcutKey = listOf(maxWidth, maxHeight, density.density, density.fontScale)
        val headerKey = shortcutKey + listOf(baseShowBrand, shortIdle, shortIdle && dashboardButtonVisible)
        val shortcutHeight = shortcutMeasurement?.takeIf { it.first == shortcutKey }?.second ?: 0.dp
        val shortcutOffset = if (showShortcuts) shortcutHeight + 8.dp else 0.dp
        val headerHeight = if (!showDestinationHeader) 0.dp else
            headerMeasurement?.takeIf { it.first == headerKey }?.second ?: if (baseShowBrand) 144.dp else 80.dp

        val compact = maxHeight - shortcutOffset < 640.dp
        val controlsTopInset = headerHeight + shortcutOffset + if (shortIdle) 20.dp else 24.dp
        val horizontalMapControls = baseShowBrand && (shortIdle || controlsTopInset + 104.dp > maxHeight - 92.dp)
        // Compact branches depend on measured header/shortcut extents as well as input data.
        val panelsKey = layoutKey + listOf(compact, shortIdle, headerHeight, shortcutOffset)
        val panelsHeight = panelsMeasurement?.takeIf { it.first == panelsKey }?.second ?: 0.dp
        // Never draw controls with a provisional offset when their own row/header changed.
        val controlsReady = (!showDestinationHeader || headerMeasurement?.first == headerKey) &&
            (!showShortcuts || shortcutMeasurement?.first == shortcutKey) &&
            (presentation.editPreviewLabel == null || panelsMeasurement?.first == panelsKey)
        val dashboardReady = !dashboardVisible || measuredDashboard != null
        val dashboardButtonReady = !dashboardButtonInMap || dashboardButtonMeasurement?.first == dashboardButtonKey
        val viewportReady = (externalHeader || panelsMeasurement?.first == panelsKey && controlsReady) &&
            dashboardReady && dashboardButtonReady
        val attributionWidth = with(density) {
            textMeasurer.measure("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall).size.width.toDp()
        } + 20.dp
        val compactWeatherWidth = (maxWidth - 24.dp - attributionWidth - 8.dp).coerceAtLeast(48.dp)
        val entryPanels: @Composable () -> Unit = {
            presentation.editPreviewLabel?.let { label ->
                Surface(Modifier.fillMaxWidth().testTag("navigation-edit-preview-label"), shape = MaterialTheme.shapes.medium) {
                    Text(label, Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (showDestinationHeader) Column(Modifier.fillMaxWidth().onSizeChanged { headerMeasurement = headerKey to with(density) { it.height.toDp() } },
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shortIdle) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeBrand(compact = true, modifier = Modifier.weight(1.2f))
                        if (current == null) HomeLocateChip(ui.locating, onLocate, Modifier.weight(1f, fill = false))
                        HomeWeatherChip(weatherLabel, onWeather, Modifier.weight(1f, fill = false))
                        if (dashboardButtonVisible && onDashboard != null) HomeDashboardButton(onDashboard)
                    }
                } else if (baseShowBrand) HomeBrand()
                Surface(onClick = onSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open-navigation"),
                    shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = if (shortIdle) 4.dp else 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Search, null, tint = Forest)
                        Column(Modifier.weight(1f)) {
                            Text("Nereye?", style = if (shortIdle) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                        }
                        Icon(Icons.Outlined.Route, "Yol tarifi", tint = Forest)
                    }
                }
            }
            if (showShortcuts) Box(Modifier.fillMaxWidth().onSizeChanged { shortcutMeasurement = shortcutKey to with(density) { it.height.toDp() } }) {
                ShortcutDestinationsRow(shortcuts, enabled = !ui.busy, showUnassigned = !live, onShortcut = onShortcut)
            }
        }
        val statusMessage: @Composable () -> Unit = {
            ui.message?.let { Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Text(it, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            } }
        }
        val navigationHeader: @Composable (@Composable () -> Unit, @Composable () -> Unit) -> Unit = { controls, preparedNotice ->
            CompactDrivingHud(compactHudData(nav, weatherLabel, ui.message, voiceFault), maxWidth > maxHeight,
                onMute, onManageSession, onWeather,
                onRecoverRecording = onRecoverRecording?.takeIf { nav.recordingPending && !preciseLocationAllowed },
                onResumeBreak = onResumePlannedBreak.takeIf { nav.plannedBreak != null },
                resumeEnabled = plannedBreakResumeEnabled, disclosure = preparedNotice, controls = controls)
        }
        WeatherRouteMap(route = route, assessment = null, modifier = Modifier.fillMaxSize().padding(end = dashboardSideWidth),
            expandable = false, stops = presentation.stops, liveCoordinate = current,
            recordedPoints = presentation.trail, gpsStale = live && nav.gpsStale,
            cameraIdentity = presentation.cameraIdentity,
            preparedRoutes = if (preparedNavigationMapApplies(presentation, nav))
                (androidx.compose.ui.platform.LocalContext.current.applicationContext as? org.iz.navigation.IzApplication)?.preparedRoutes else null,
            drivingCamera = phoneDriveCameraInput(nav, showPlanner, android.os.SystemClock.elapsedRealtime()),
            cameraViewportReady = viewportReady, navigationControlsReady = controlsReady,
            navigationLayout = true, members = members, onLocate = onLocate, onMapLongClick = onPin,
            navigationControlsTopInset = if (presentation.editPreviewLabel != null)
                panelsHeight + 24.dp else controlsTopInset,
            navigationControlsHorizontal = horizontalMapControls,
            attributionBottomInset = if (dashboardBottomHeight > 0.dp) dashboardBottomHeight + 92.dp else if (showPlanner && !dashboardVisible) maxHeight * .64f + 4.dp else 92.dp,
            cameraViewportInsets = PaddingValues(start = 28.dp,
                top = if (externalHeader) 24.dp else if (shortIdle) controlsTopInset else panelsHeight + 24.dp,
                end = if (externalHeader) 28.dp else if (horizontalMapControls) 124.dp else 84.dp,
                bottom = if (dashboardBottomHeight > 0.dp) dashboardBottomHeight + 112.dp else if (showPlanner && !dashboardVisible) maxHeight * .64f + 24.dp else 112.dp + dashboardButtonHeight),
            navigationHeader = if (externalHeader) navigationHeader else null, layersRequest = layersRequest,
            onOsmPlaceClick = onOsmPlaceClick, placeInformationIdentity = placeInformationIdentity)
        if (!externalHeader) Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(end = dashboardSideWidth).padding(12.dp)
            .onSizeChanged { panelsMeasurement = panelsKey to with(density) { it.height.toDp() } }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entryPanels()
            if (!showPlanner && !dashboardVisible && live) {
                HomeSessionCard(nav, onMute, onManageSession, Modifier.padding(end = 60.dp),
                    plannedBreakResumeEnabled = plannedBreakResumeEnabled, onResumePlannedBreak = onResumePlannedBreak) {
                    HomeSessionWarnings(nav, voiceFault, preciseLocationAllowed)
                }
                if (nav.roadSpeed.visible) {
                    val speed = compactRoadSpeedReadout(nav.roadSpeed)
                    Text(if (nav.gpsStale || nav.paused) "—/—" else speed.value,
                        Modifier.padding(end = 60.dp).testTag("road-speed-panel").semantics {
                            contentDescription = if (nav.gpsStale || nav.paused) "Güncel hız ve hız sınırı bilinmiyor" else speed.description
                        }, style = MaterialTheme.typography.titleSmall)
                }
            }
            if (!shortIdle && !showPlanner && !dashboardVisible && !live && current == null) HomeLocateChip(ui.locating, onLocate)
            if (!showPlanner && !dashboardVisible) statusMessage()
            if (!showPlanner && !dashboardVisible && !shortIdle) HomeWeatherChip(weatherLabel, onWeather,
                if (compact && live) Modifier.align(Alignment.End).widthIn(max = compactWeatherWidth) else Modifier)
        }
        if (showPlanner && !dashboardVisible) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.64f).padding(bottom = 88.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface) { planner() }
        if (dashboardVisible) {
            val panelModifier = if (dashboardAtSide) Modifier.align(Alignment.TopEnd).padding(top = 12.dp, bottom = 12.dp)
                .width(desiredSideWidth).fillMaxHeight()
            else Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp).fillMaxWidth().height(desiredBottomHeight)
            Box(panelModifier.onSizeChanged { dashboardMeasurement = dashboardKey to it }) { dashboard?.invoke() }
        }
        if (dashboardButtonInMap && onDashboard != null) Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 92.dp)) {
            HomeDashboardButton(onDashboard, Modifier.onSizeChanged {
                dashboardButtonMeasurement = dashboardButtonKey to with(density) { it.height.toDp() }
            })
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(end = dashboardSideWidth).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onRecord, Modifier.weight(1f).height(56.dp).testTag("home-record"),
                    enabled = !nav.recordingPending, contentPadding = PaddingValues(6.dp)) {
                    Icon(if (nav.recording) Icons.Outlined.StopCircle else Icons.Outlined.FiberManualRecord, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp)); Text(when {
                        nav.recording -> "Kayıt açık"
                        nav.recordingPending -> "Kayıt bekliyor"
                        else -> "Kaydet"
                    }, maxLines = 1)
                }
                TextButton(onGroup, Modifier.weight(1f).height(56.dp).testTag("home-group"), contentPadding = PaddingValues(6.dp)) {
                    Icon(Icons.Outlined.Groups, null, Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text("Grup")
                }
                TextButton(onMenu, Modifier.weight(1f).height(56.dp).testTag("home-menu"), contentPadding = PaddingValues(6.dp)) {
                    Icon(Icons.Outlined.Menu, null, Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text("Menü")
                }
            }
        }
    }
}

@Composable
private fun HomeLocateChip(locating: Boolean, onLocate: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(onClick = onLocate, modifier = modifier,
        label = { Text(if (locating) "Konum alınıyor…" else "Konumumu göster", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.GpsFixed, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface))
}

@Composable
private fun HomeWeatherChip(label: String, onWeather: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(onClick = onWeather, modifier = modifier.testTag("home-weather"),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface))
}

@Composable
private fun HomeBrand(compact: Boolean = false, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag("home-brand").semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(24.dp), color = Paper, shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            Image(painterResource(R.drawable.ic_launcher), contentDescription = null,
                modifier = Modifier.size(if (compact) 28.dp else 36.dp).clip(RoundedCornerShape(10.dp)).testTag("home-brand-logo"))
            Column(Modifier.weight(1f)) {
                Text("İz", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium, color = Forest)
                if (!compact) Text("Küçük yollar, güzel anılar.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
private fun HomeDashboardButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("open-navigation-dashboard"),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Icon(Icons.Outlined.Dashboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Gösterge")
    }
}

@Composable
private fun HomeSessionCard(nav: NavigationState, onMute: () -> Unit, onManageSession: () -> Unit,
    modifier: Modifier = Modifier, body: (@Composable ColumnScope.() -> Unit)? = null,
    plannedBreakResumeEnabled: Boolean = false, onResumePlannedBreak: () -> Unit = {}, details: @Composable () -> Unit = {}) {
    Surface(onClick = onManageSession, shape = RoundedCornerShape(20.dp), shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .98f),
        modifier = modifier.fillMaxWidth().testTag("live-guidance-card")
            .semantics { onClick(label = "Yolculuk işlemlerini aç", action = null) }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (body == null) {
                nav.plannedBreak?.let { PlannedBreakDetails(it, nav.tripTiming) } ?: HomeSessionText(nav)
            } else body()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    val activeBreak = nav.plannedBreak
                    if (activeBreak != null) PlannedBreakElapsed(activeBreak, compact = true)
                    else if (nav.guidance && !nav.paused && !nav.gpsStale) nav.progress?.nextManeuverDistanceMeters?.let { meters ->
                        Text(if (meters >= 1000) "%.1f km sonra".format(meters / 1000) else "${meters.roundToInt().coerceAtLeast(0)} m sonra",
                            fontSize = 16.sp, lineHeight = 23.sp, color = Forest)
                    }
                }
                if (nav.plannedBreak != null) PlannedBreakResumeButton(plannedBreakResumeEnabled, onResumePlannedBreak)
                if (nav.guidance) IconButton(onMute,
                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("compact-card-mute")) {
                    Icon(if (nav.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                        if (nav.muted) "Sesi aç" else "Sesi kapat")
                }
                TextButton(onManageSession,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("home-finish-session")
                        .semantics { contentDescription = "Yolculuk işlemlerini aç" },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("İşlemler") }
            }
            details()
        }
    }
}

@Composable
private fun HomeSessionText(nav: NavigationState) {
    if (nav.simulation) Text("Simülasyon", color = Forest, style = MaterialTheme.typography.labelSmall)
    if (nav.guidance) Text(navigationInstruction(nav), fontSize = 18.sp, lineHeight = 24.sp)
    else Text("${nav.sessionTransport?.label().orEmpty()} · Serbest yolculuk", style = MaterialTheme.typography.titleLarge)
}

private fun visibleSessionMessage(nav: NavigationState): String? =
    nav.message?.takeUnless { nav.simulation && it == "Deneme sürüşü · günlüğe kaydedilmez" }

@Composable
private fun HomeSessionWarnings(nav: NavigationState, voiceFault: String?, preciseLocationAllowed: Boolean) {
    if (nav.approximateOrigin != null) Text("Yaklaşık konum", modifier = Modifier.testTag("quick-approximate-location"),
        style = MaterialTheme.typography.bodyMedium)
    if (nav.guidance) voiceFault?.let { Text(it, modifier = Modifier.testTag("navigation-voice-fault"),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (nav.gpsStale) Text("GPS konumu bekleniyor", color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
    if (nav.recordingPending) RecordingPendingWarning(preciseLocationAllowed)
    if (!nav.locationActive && !nav.simulation) Text("Konum bağlantısı kuruluyor", style = MaterialTheme.typography.bodySmall)
    visibleSessionMessage(nav)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun rememberPreciseLocationPermission(): Boolean {
    val context = LocalContext.current
    fun hasPreciseLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    var allowed by remember(context) { mutableStateOf(hasPreciseLocation()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = hasPreciseLocation() }
    return allowed
}

@Composable
internal fun RecordingPendingWarning(preciseLocationAllowed: Boolean = rememberPreciseLocationPermission()) {
    val context = LocalContext.current
    Column {
        Text(if (preciseLocationAllowed) "Kayıt için güncel konum bekleniyor." else "Kayıt için hassas konum izni gerekiyor.",
            style = MaterialTheme.typography.bodySmall)
        if (!preciseLocationAllowed) {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("recording-permission-settings")) {
                Text("İzin ayarları")
            }
        }
    }
}
