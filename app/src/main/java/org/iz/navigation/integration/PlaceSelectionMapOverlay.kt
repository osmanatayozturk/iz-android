package org.iz.navigation.integration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.ui.PlaceInformationDiscoveryContent
import org.iz.navigation.ui.PlaceInformationDiscoveryOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.geometry.LatLngBounds
import org.iz.navigation.power.PowerDiagnostics
import kotlin.math.abs
import kotlin.math.roundToInt

/** Map projection is platform wiring; the same immutable owner state drives numbered pins and list. */
@Composable
internal fun PlaceSelectionMapOverlay(map: MapLibreMap?, owner: PlaceInformationDiscoveryOwner,
    baseInsets: PaddingValues = PaddingValues(16.dp), styleRevision: Int = 0, onSelected: () -> Unit = {},
    onPriorityHit: (Offset) -> Boolean = { false }, onLongPress: (Offset) -> Unit = {}) {
    val state = owner.state
    var projected by remember(map) { mutableStateOf(emptyList<Offset>()) }
    val currentCandidates by rememberUpdatedState(state.candidates)
    val currentPriorityHit by rememberUpdatedState(onPriorityHit)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val touchPx = with(density) { 48.dp.toPx() }
    var panelPx by remember(map) { mutableIntStateOf(0) }
    var appliedPadding by remember(map) { mutableStateOf(false) }
    var viewport by remember(map) { mutableStateOf(IntSize.Zero) }
    val base = with(density) { listOf(baseInsets.calculateLeftPadding(direction).roundToPx(),
        baseInsets.calculateTopPadding().roundToPx(), baseInsets.calculateRightPadding(direction).roundToPx(),
        baseInsets.calculateBottomPadding().roundToPx()) }
    DisposableEffect(map) {
        fun project() { projected = currentCandidates.map { candidate ->
            val point = map?.projection?.toScreenLocation(GeoCoordinate(candidate.place.latitude, candidate.place.longitude).mapCoordinate())
            if (point == null) Offset.Unspecified else Offset(point.x, point.y)
        } }
        val listener = MapLibreMap.OnCameraMoveListener { project() }
        map?.addOnCameraMoveListener(listener)
        project()
        onDispose { map?.removeOnCameraMoveListener(listener) }
    }
    LaunchedEffect(map, state.candidates) {
        projected = state.candidates.map { candidate ->
            val point = map?.projection?.toScreenLocation(GeoCoordinate(candidate.place.latitude, candidate.place.longitude).mapCoordinate())
            if (point == null) Offset.Unspecified else Offset(point.x, point.y)
        }
    }
    val latestSelected by rememberUpdatedState(onSelected)
    LaunchedEffect(state.selectedRef) { if (state.selectedRef != null) latestSelected() }
    val outlineJson = remember(state.selectedCandidate) { mapPlaceOutlineJson(state.selectedCandidate) }
    LaunchedEffect(map, styleRevision, outlineJson) {
        map?.updateMeasuredGeoJson(MAP_OUTLINE_SOURCE, outlineJson, PowerDiagnostics.Metric.MAP_ROUTE)
    }
    LaunchedEffect(map, state.selectedRef, state.selectedCandidate?.outline, panelPx, base, viewport) {
        val target = map ?: return@LaunchedEffect
        val padding = doubleArrayOf(base[0].toDouble(), base[1].toDouble(), base[2].toDouble(),
            maxOf(base[3], panelPx + with(density) { 12.dp.roundToPx() }).toDouble())
        val candidate = state.selectedCandidate
        if (viewport.width <= padding[0] + padding[2] || viewport.height <= padding[1] + padding[3]) return@LaunchedEffect
        if (candidate != null) {
            appliedPadding = true
            val outline = candidate.outline
            if (outline != null) {
                val bounds = LatLngBounds.Builder().includes(outline.points.map { it.mapCoordinate() }).build()
                target.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding[0].toInt(), padding[1].toInt(), padding[2].toInt(), padding[3].toInt()))
            } else target.moveCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(target.cameraPosition).target(GeoCoordinate(candidate.place.latitude, candidate.place.longitude).mapCoordinate())
                .zoom(target.cameraPosition.zoom.coerceAtLeast(16.0)).padding(padding).build()))
        } else if (!state.open && appliedPadding) {
            target.moveCamera(CameraUpdateFactory.paddingTo(base[0].toDouble(), base[1].toDouble(), base[2].toDouble(), base[3].toDouble()))
            appliedPadding = false
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }) {
        state.candidates.forEachIndexed { index, candidate ->
            val point = projected.getOrNull(index) ?: return@forEachIndexed
            if (!point.x.isFinite() || !point.y.isFinite()) return@forEachIndexed
            fun hits(at: Offset): List<SelectedOsmPlace> = state.candidates.filterIndexed { i, _ ->
                projected.getOrNull(i)?.let { abs(it.x - at.x) <= touchPx / 2 && abs(it.y - at.y) <= touchPx / 2 } == true
            }.map { it.place }
            val isSelected = state.selectedRef == candidate.place.osmRef
            fun selectAt(at: Offset) { if (!currentPriorityHit(at)) owner.selectRendered(hits(at)) }
            Box(Modifier.absoluteOffset { IntOffset((point.x - touchPx / 2).roundToInt(), (point.y - touchPx / 2).roundToInt()) }
                .size(48.dp).testTag("place-info-map-candidate-$index")
                .pointerInput(state.generation, projected, state.candidates) {
                    detectTapGestures(onLongPress = { local -> currentLongPress(Offset(point.x - touchPx / 2 + local.x, point.y - touchPx / 2 + local.y)) },
                        onTap = { local -> selectAt(Offset(point.x - touchPx / 2 + local.x, point.y - touchPx / 2 + local.y)) })
                }.semantics {
                    role = Role.Button; selected = isSelected
                    contentDescription = "${index + 1}. ${candidate.place.name.ifBlank { "Adsız OSM nesnesi" }}, ${candidate.typeLabel}"
                    onClick { selectAt(point); true }
                }.focusable(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(28.dp).background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, CircleShape)
                    .border(if (isSelected) 3.dp else 2.dp, MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Text((index + 1).toString(), color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        PlaceInformationDiscoveryContent(owner, Modifier.padding(top = baseInsets.calculateTopPadding()), onPanelHeight = { panelPx = it })
    }
}
