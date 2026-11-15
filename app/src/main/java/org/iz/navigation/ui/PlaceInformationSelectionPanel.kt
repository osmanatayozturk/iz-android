package org.iz.navigation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import kotlin.math.roundToInt
import java.util.Locale

@Composable
internal fun PlaceInformationDiscoveryContent(owner: PlaceInformationDiscoveryOwner, modifier: Modifier = Modifier,
    onPanelHeight: (Int) -> Unit = {}, compactLayout: Boolean = false) {
    val state = owner.state
    PlaceInformationSelectionPanel(state, { owner.choose(it, state.generation) }, { owner.openInformation(state.generation, state.selectedRef) },
        { owner.close() }, owner::retry, owner::showAll, modifier, onPanelHeight, owner::retryOutline, compactLayout)
}

/** Plain state/events content; no app graph, query or modal scrim. Map remains interactive above it. */
@Composable
internal fun PlaceInformationSelectionPanel(state: PlaceInformationDiscoveryState,
    onChoose: (SelectedOsmPlace) -> Unit, onInformation: () -> Unit, onClose: () -> Unit,
    onRetry: () -> Unit, onShowAll: () -> Unit, modifier: Modifier = Modifier,
    onPanelHeight: (Int) -> Unit = {}, onRetryOutline: () -> Unit = {}, compactLayout: Boolean = false) {
    DisposableEffect(state.open) { if (!state.open) onPanelHeight(0); onDispose { onPanelHeight(0) } }
    if (!state.open) return
    BackHandler(onBack = onClose)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        var fraction by rememberSaveable(state.generation) { mutableFloatStateOf(.5f) }
        var collapsed by rememberSaveable(state.generation) { mutableStateOf(false) }
        val availablePx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        // Only compact multi-selection trades the usual minimum for actions + one full row.
        val minimumExpandedHeight = if (compactLayout) minOf(112.dp, maxHeight) else minOf(160.dp, maxHeight * .7f)
        val height = if (collapsed) minOf(56.dp, maxHeight)
            else (maxHeight * fraction).coerceIn(minimumExpandedHeight, maxOf(minimumExpandedHeight, maxHeight * .8f))
        val drag = rememberDraggableState { delta ->
            collapsed = false
            fraction = (fraction - delta / availablePx).coerceIn(.2f, .8f)
        }
        val list = rememberSaveable(state.generation, saver = LazyListState.Saver) { LazyListState() }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height)
            .onSizeChanged { onPanelHeight(it.height) }.testTag("place-info-discovery"),
            shape = MaterialTheme.shapes.large, tonalElevation = 4.dp, shadowElevation = 5.dp) {
            Column {
                Row(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ collapsed = !collapsed }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("place-info-discovery-collapse").semantics {
                            contentDescription = if (collapsed) "Yer listesini genişlet" else "Yer listesini daralt"
                        }) { Text(if (collapsed) "⌃" else "⌄") }
                    Text("Yakındaki yerler", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    TextButton(onInformation, enabled = state.selectedCandidate != null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("place-info-open-information")) { Text("Bilgileri aç") }
                    TextButton(onClose, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("place-info-discovery-close")) { Text("Kapat") }
                }
                if (!collapsed) LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list,
                    contentPadding = PaddingValues(bottom = if (compactLayout) 0.dp else 12.dp)) {
                    if (state.busy) item { Column(Modifier.padding(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth()); Text("OSM yerleri aranıyor…")
                    } }
                    state.message?.let { message -> item { Text(message, Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall) } }
                    if (state.outlineBusy) item("outline-loading") { Column(Modifier.padding(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("place-info-outline-loading")); Text("Seçili alanın sınırı alınıyor…")
                    } }
                    state.outlineMessage?.let { message -> item("outline-message") { Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
                    if (state.outlineRetryable) item("outline-retry") { TextButton(onRetryOutline, Modifier.heightIn(min = 48.dp)
                        .testTag("place-info-outline-retry")) { Text("Sınırı yeniden dene") } }
                    items(state.visibleCandidates, key = { it.place.osmRef.toString() }) { candidate ->
                        val place = candidate.place
                        val number = state.candidates.indexOfFirst { it.place.osmRef == place.osmRef } + 1
                        val selected = state.selectedRef == place.osmRef
                        val name = place.name.ifBlank { "Adsız OSM nesnesi" }
                        val distance = if (candidate.distanceMeters < 1000) "${candidate.distanceMeters.roundToInt()} m"
                            else String.format(Locale.forLanguageTag("tr"), "%.1f km", candidate.distanceMeters / 1000)
                        val prefix = if (place.osmRef?.type == OsmType.NODE) "Yaklaşık" else "Merkeze yaklaşık"
                        Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .selectable(selected, role = Role.RadioButton, onClick = { onChoose(place) })
                                .testTag("place-info-discovery-choice-${number - 1}")
                                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(number.toString(), Modifier.width(32.dp), style = MaterialTheme.typography.titleMedium)
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyLarge)
                                    Text("${candidate.typeLabel} · $prefix $distance", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (state.hitRefs.isNotEmpty()) item { TextButton(onShowAll, Modifier.heightIn(min = 48.dp)) { Text("Tüm adayları göster") } }
                    if (state.retryable) item { TextButton(onRetry, Modifier.heightIn(min = 48.dp)
                        .testTag("place-info-discovery-retry")) { Text("Yeniden dene") } }
                    item { Text("© OpenStreetMap katkıcıları", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
