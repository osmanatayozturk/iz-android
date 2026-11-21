@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
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

// WeatherRouteMap uses a 48 dp location action with a 12 dp end inset.
private val HomeCornerActionSize = 48.dp
private val HomeCornerInset = 12.dp

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
    leanInstrument: (@Composable () -> Unit)? = null,
    idleNavigationBar: (@Composable () -> Unit)? = null,
    onPointDirections: ((GeoCoordinate) -> Unit)? = null,
    inspectMapPlaceRequest: Int = 0,
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
    val idleMainMap = baseShowBrand && nav.acceptsIdleMapTap() && onPointDirections != null
    var tappedPoint by remember(placeInformationIdentity, idleMainMap) { mutableStateOf<GeoCoordinate?>(null) }
    var inspectingPlaces by remember(placeInformationIdentity, idleMainMap) { mutableStateOf(false) }
    var seenInspectionRequest by remember { mutableIntStateOf(inspectMapPlaceRequest) }
    LaunchedEffect(inspectMapPlaceRequest) {
        if (seenInspectionRequest != inspectMapPlaceRequest) {
            seenInspectionRequest = inspectMapPlaceRequest
            inspectingPlaces = idleMainMap
        }
    }
    BackHandler(enabled = inspectingPlaces) { inspectingPlaces = false }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        tappedPoint = null
        inspectingPlaces = false
    }
    tappedPoint?.let { point ->
        AlertDialog(onDismissRequest = { tappedPoint = null }, title = { Text("Haritada seçilen nokta") },
            confirmButton = {
                TextButton(onClick = { tappedPoint = null; onPointDirections?.invoke(point) },
                    modifier = Modifier.testTag("map-point-directions")) { Text("Yol tarifi") }
            }, dismissButton = {
                TextButton(onClick = { tappedPoint = null; onPin(point) },
                    modifier = Modifier.testTag("map-point-add")) { Text("Yer ekle") }
            })
    }
    val showShortcuts = !nav.guidance && !showPlanner && !dashboardVisible && (!live || shortcuts.isNotEmpty())
    val showDestinationHeader = !nav.guidance && !dashboardVisible
    val externalHeader = !showPlanner && !dashboardVisible && nav.guidance
    var shortcutMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var headerMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var panelsMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var dashboardMeasurement by remember { mutableStateOf<Pair<List<Any?>, IntSize>?>(null) }
    var dashboardButtonMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var fixedTopMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var multiSelectionOpen by remember { mutableStateOf(false) }
    val dashboardEpoch = remember(dashboardVisible) { Any() }
    val navigationBar = idleNavigationBar.takeIf {
        baseShowBrand && !showDashboard && !nav.recordingPending && nav.trackFollow == null
    }
    HomeMapLayout(navigationBar) { dockedNavigationBar ->
        var bottomActionsHeight by remember(maxWidth, maxHeight, density.density, density.fontScale) { mutableStateOf(0.dp) }
        val compactSelection = multiSelectionOpen && (maxWidth > maxHeight || maxHeight < 400.dp)
        val shortIdle = baseShowBrand && (maxWidth > maxHeight || maxHeight < 400.dp)
        val dashboardAtSide = dashboardVisible && maxWidth > maxHeight
        val dashboardKey = listOf(maxWidth, maxHeight, density.density, density.fontScale, dashboardAtSide, dashboardEpoch)
        val measuredDashboard = dashboardMeasurement?.takeIf { it.first == dashboardKey }?.second
        val desiredSideWidth = (maxWidth * .44f).coerceAtMost(400.dp).coerceAtLeast(minOf(240.dp, maxWidth))
        val desiredBottomHeight = (maxHeight * .52f).coerceAtMost((maxHeight - 100.dp).coerceAtLeast(0.dp))
        val dashboardSideWidth = if (dashboardAtSide) measuredDashboard?.let { with(density) { it.width.toDp() } } ?: desiredSideWidth else 0.dp
        val dashboardBottomHeight = if (dashboardVisible && !dashboardAtSide) measuredDashboard?.let { with(density) { it.height.toDp() } } ?: desiredBottomHeight else 0.dp
        val dashboardButtonInMap = false
        val dashboardButtonKey = listOf(maxWidth, maxHeight, density.density, density.fontScale, dashboardEpoch)
        val dashboardButtonHeight = if (dashboardButtonInMap) dashboardButtonMeasurement?.takeIf { it.first == dashboardButtonKey }?.second ?: 48.dp else 0.dp
        val shortLandscape = maxWidth > maxHeight && maxHeight < 480.dp
        val showLean = leanInstrument != null && !showPlanner && !dashboardVisible && !multiSelectionOpen
        val inlineLean = showLean && shortLandscape && maxWidth >= 480.dp
        val measuredIdleDock = maxOf(bottomActionsHeight, HomeCornerActionSize + HomeCornerInset * 2)
        // The native location action aligns with the record action even if the dock grows with text.
        val idleLocationTop = (maxHeight - (measuredIdleDock + HomeCornerActionSize) / 2f).coerceAtLeast(0.dp)
        val paddedDockHeight = when {
            baseShowBrand -> measuredIdleDock
            inlineLean -> bottomActionsHeight
            else -> 0.dp
        }
        val dockAttributionInset = maxOf(92.dp, paddedDockHeight + 8.dp)
        val dockCameraInset = maxOf(112.dp, paddedDockHeight + 28.dp)
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
        val headerKey = shortcutKey + baseShowBrand
        val shortcutHeight = shortcutMeasurement?.takeIf { it.first == shortcutKey }?.second ?: 0.dp
        val shortcutOffset = if (showShortcuts) shortcutHeight + 8.dp else 0.dp
        val headerHeight = if (!showDestinationHeader) 0.dp else
            headerMeasurement?.takeIf { it.first == headerKey }?.second ?: if (baseShowBrand) 48.dp else 80.dp

        val compact = maxHeight - shortcutOffset < 640.dp
        val controlsTopInset = headerHeight + shortcutOffset + if (shortIdle) 20.dp else 24.dp
        val horizontalMapControls = baseShowBrand && (shortIdle || controlsTopInset + 104.dp > maxHeight - 92.dp)
        // Compact branches depend on measured header/shortcut extents as well as input data.
        val panelsKey = layoutKey + listOf(compact, shortIdle, headerHeight, shortcutOffset, compactSelection)
        val panelsHeight = panelsMeasurement?.takeIf { it.first == panelsKey }?.second ?: 0.dp
        val fixedTopHeight = fixedTopMeasurement?.takeIf { it.first == panelsKey }?.second ?: 0.dp
        // Warnings stay outside this scroll. Reserve actions + one candidate and real map space.
        val selectionHeaderBudget = (maxHeight - bottomActionsHeight - 112.dp - 48.dp - fixedTopHeight).coerceAtLeast(0.dp)
        val selectionTopInset = panelsHeight + if (compactSelection) 0.dp else 24.dp
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
            if (showDestinationHeader) {
                val headerModifier = Modifier.fillMaxWidth()
                    .onSizeChanged { headerMeasurement = headerKey to with(density) { it.height.toDp() } }
                if (inspectingPlaces) {
                    Surface(headerModifier, shape = MaterialTheme.shapes.large, shadowElevation = 4.dp) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("İncelemek istediğin yere dokun", Modifier.weight(1f))
                            TextButton({ inspectingPlaces = false }, Modifier.heightIn(min = 48.dp)
                                .testTag("map-inspection-close")) { Text("Vazgeç") }
                        }
                    }
                } else if (baseShowBrand) {
                    HomeSearchBar(onSearch, onMenu, headerModifier)
                } else {
                    Surface(onClick = onSearch, modifier = headerModifier.heightIn(min = 48.dp).testTag("open-navigation"),
                        shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Search, null, tint = Forest)
                            Text("Nereye?", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            Icon(Icons.Outlined.Route, "Yol tarifi", tint = Forest)
                        }
                    }
                }
            }
            if (showShortcuts) Box(Modifier.fillMaxWidth().onSizeChanged { shortcutMeasurement = shortcutKey to with(density) { it.height.toDp() } }) {
                ShortcutDestinationsRow(shortcuts, enabled = !ui.busy, showUnassigned = !live, onShortcut = onShortcut)
            }
        }
        val statusMessage: @Composable () -> Unit = {
            val message = ui.message ?: if (baseShowBrand && ui.locating) "Konum alınıyor…" else null
            message?.let { Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
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
            navigationControlsTopInset = when {
                baseShowBrand -> idleLocationTop
                compactSelection -> selectionTopInset
                presentation.editPreviewLabel != null -> panelsHeight + 24.dp
                else -> controlsTopInset
            },
            navigationControlsHorizontal = horizontalMapControls,
            attributionBottomInset = if (dashboardBottomHeight > 0.dp) dashboardBottomHeight + 92.dp else if (showPlanner && !dashboardVisible) maxHeight * .64f + 4.dp else dockAttributionInset,
            cameraViewportInsets = PaddingValues(start = 28.dp,
                top = if (externalHeader) 24.dp else if (compactSelection) selectionTopInset else if (shortIdle) controlsTopInset else panelsHeight + 24.dp,
                end = if (externalHeader) 28.dp else if (horizontalMapControls) 124.dp else 84.dp,
                bottom = if (dashboardBottomHeight > 0.dp) dashboardBottomHeight + 112.dp else if (showPlanner && !dashboardVisible) maxHeight * .64f + 24.dp else dockCameraInset + dashboardButtonHeight),
            navigationHeader = if (externalHeader) navigationHeader else null, layersRequest = layersRequest,
            onOsmPlaceClick = onOsmPlaceClick, placeInformationIdentity = placeInformationIdentity,
            onMapPointTap = if (idleMainMap && !inspectingPlaces && onOsmPlaceClick != null) {
                { point -> tappedPoint = point }
            } else null,
            selectionPanelInsets = PaddingValues(top = if (externalHeader) 0.dp else selectionTopInset,
                bottom = bottomActionsHeight), compactSelectionPanel = compactSelection,
            onMultiSelectionOpenChanged = { multiSelectionOpen = it })
        if (!externalHeader) Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(end = dashboardSideWidth)
            .padding(horizontal = 12.dp, vertical = if (compactSelection) 0.dp else 12.dp)
            .onSizeChanged { panelsMeasurement = panelsKey to with(density) { it.height.toDp() } },
            verticalArrangement = Arrangement.spacedBy(if (compactSelection) 0.dp else 8.dp)) {
            if (compactSelection) Column(Modifier.fillMaxWidth().heightIn(max = selectionHeaderBudget)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { entryPanels() }
            else entryPanels()
            Column(Modifier.fillMaxWidth().onSizeChanged { fixedTopMeasurement = panelsKey to with(density) { it.height.toDp() } },
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            if (!showPlanner && !dashboardVisible) statusMessage()
            if (!showPlanner && !dashboardVisible) HomeWeatherChip(weatherLabel, onWeather,
                if (compact && live) Modifier.align(Alignment.End).widthIn(max = compactWeatherWidth) else Modifier)
            }
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
        if (dashboardButtonInMap && onDashboard != null && leanInstrument == null) Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 92.dp)) {
            HomeDashboardButton(onDashboard, Modifier.onSizeChanged {
                dashboardButtonMeasurement = dashboardButtonKey to with(density) { it.height.toDp() }
            })
        }
        if (showLean && !inlineLean && leanInstrument != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = maxOf(bottomActionsHeight, 56.dp) + 12.dp)) {
                leanInstrument()
            }
        }
        if (baseShowBrand) {
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { bottomActionsHeight = with(density) { it.height.toDp() } }
                .testTag("home-bottom-actions").padding(HomeCornerInset),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, shadowElevation = 3.dp) {
                    IconButton(onRecord, Modifier.size(HomeCornerActionSize).testTag("home-record"),
                        enabled = !nav.recordingPending) {
                        Icon(Icons.Outlined.FiberManualRecord, "Yolculuk kaydını başlat")
                    }
                }
                if (dockedNavigationBar != null) Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    dockedNavigationBar()
                }
                Spacer(Modifier.size(HomeCornerActionSize))
            }
        } else {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { bottomActionsHeight = with(density) { it.height.toDp() } }
                .testTag("home-bottom-actions")
                .padding(end = dashboardSideWidth).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
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
                    if (inlineLean && leanInstrument != null) leanInstrument()
                }
            }
        }
    }
}

@Composable
private fun HomeMapLayout(
    navigationBar: (@Composable () -> Unit)?,
    map: @Composable BoxWithConstraintsScope.((@Composable () -> Unit)?) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Share the existing action row on short landscape screens to retain map height.
        val docked = maxWidth >= 400.dp && maxWidth > maxHeight && maxHeight < 380.dp
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).testTag("navigation-home")) {
                map(navigationBar.takeIf { docked })
            }
            if (!docked) navigationBar?.invoke()
        }
    }
}

@Composable
internal fun HomeNavigationBar(onMap: () -> Unit, onJourneys: () -> Unit, onOsmContribution: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("home-primary-navigation"), tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            HomeNavigationItem("Harita", "home-nav-map", selected = true, onClick = onMap)
            HomeNavigationItem("Yolculuklar", "home-nav-journeys", selected = false, onClick = onJourneys)
            HomeNavigationItem("OSM katkısı", "home-nav-osm", selected = false, onClick = onOsmContribution)
        }
    }
}

@Composable
private fun RowScope.HomeNavigationItem(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.weight(1f).sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag)
        .clip(MaterialTheme.shapes.medium)
        .background(if (selected) colors.secondaryContainer else colors.surface)
        .selectable(selected, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HomeWeatherChip(label: String, onWeather: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(onClick = onWeather, modifier = modifier.testTag("home-weather"),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface))
}

@Composable
private fun HomeSearchBar(onSearch: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = colors.primary,
        contentColor = colors.onPrimary, shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.testTag("home-brand").semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Image(painterResource(R.drawable.ic_launcher), contentDescription = null,
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).testTag("home-brand-logo"))
                Text("İz", style = MaterialTheme.typography.titleMedium)
            }
            VerticalDivider(Modifier.padding(horizontal = 8.dp).height(20.dp),
                color = colors.onPrimary.copy(alpha = .35f))
            Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("open-navigation")
                .clickable(role = Role.Button, onClick = onSearch),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(20.dp))
                Text("Nereye?", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onMenu, Modifier.size(48.dp).testTag("home-menu")) {
                Icon(Icons.Outlined.Menu, "Harita menüsü")
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
