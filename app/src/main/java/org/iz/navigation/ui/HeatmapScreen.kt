@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.ExpandableMap
import org.iz.navigation.integration.HeatmapMapContent
import org.iz.navigation.integration.HeatmapRender
import org.iz.navigation.integration.HeatmapFilter
import org.iz.navigation.integration.HeatmapPeriod
import org.iz.navigation.integration.rememberHeatmapWindow
import org.iz.navigation.integration.heatmapFrequencyBands
import org.iz.navigation.integration.rememberHeatmapRender
import org.iz.navigation.power.AnalysisPhase

@Composable
fun HeatmapScreen(
    state: DiaryState,
    now: Long? = null,
    onOpenJourneys: (() -> Unit)? = null,
) {
    HeatmapScreenContent(state, onOpenJourneys) { filter ->
        rememberHeatmapRender(state.analysis, filter.mode, rememberHeatmapWindow(filter.period, now))
    }
}

@Composable
internal fun HeatmapScreenContent(
    state: DiaryState,
    onOpenJourneys: (() -> Unit)? = null,
    preparedRender: @Composable (HeatmapFilter) -> HeatmapRender?,
) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf(HeatmapPeriod.ALL) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var mapShown by remember { mutableStateOf(false) }
    val selected = Transport.entries.firstOrNull { it.name == filter }
    val selection = HeatmapFilter(selected, period)
    val render = preparedRender(selection)
    fun apply(filterValue: HeatmapFilter) {
        filter = filterValue.mode?.name
        period = filterValue.period
    }
    val hasGeometry = render?.hasGeometry == true
    LaunchedEffect(hasGeometry) {
        if (hasGeometry) mapShown = true
    }

    Column(
        Modifier.fillMaxSize().testTag("heatmap_screen").padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Yoğunluk haritası", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        ExpandableMap(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)),
            title = "Yoğunluk haritası",
            expandable = mapShown || hasGeometry,
            actions = {
                var open by rememberSaveable { mutableStateOf(false) }
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Isı haritasını filtrele")
                }
                if (open) HeatmapFilterSheet(selection, onDismiss = { open = false }) { applied ->
                    apply(applied)
                    open = false
                }
            },
        ) { mapModifier ->
            Column(mapModifier) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Keep the map mounted during filter changes so it retains its camera.
                    if (mapShown || hasGeometry) {
                        HeatmapMapContent(render, "${period.name}_${filter ?: "ALL"}", Modifier.fillMaxSize())
                    }
                    if (render == null) {
                        Surface(Modifier.fillMaxSize().testTag("heatmap_loading"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(
                                Modifier.fillMaxSize().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (state.analysis.phase == AnalysisPhase.RUNNING) {
                                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                }
                                Text(
                                    if (state.analysis.pending || state.analysis.phase == AnalysisPhase.WAITING_END)
                                        state.analysis.label else "İzler hazırlanıyor",
                                    color = Muted, textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else if (!hasGeometry) {
                        HeatmapEmptyState(selection, Modifier.fillMaxSize())
                    }
                }
                if (render?.hasGeometry == true) HeatmapLegend(selection, render.data.journeyCount)
            }
        }
        StatisticsSurface {
            FilledTonalButton(
                onClick = { showFilters = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("heatmap_show_filters"),
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Filtreler")
            }
            Text(
                "${period.label} · ${selected?.label() ?: "Tüm ulaşım türleri"}",
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
            if (render != null && !hasGeometry) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selection != HeatmapFilter()) TextButton(onClick = { apply(HeatmapFilter()) }) { Text("Filtreyi temizle") }
                    if (onOpenJourneys != null) TextButton(onClick = onOpenJourneys) { Text("Yolculuklara dön") }
                }
            }
        }
    }
    if (showFilters) HeatmapFilterSheet(selection, onDismiss = { showFilters = false }) { applied ->
        apply(applied)
        showFilters = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeatmapFilterSheet(applied: HeatmapFilter, onDismiss: () -> Unit, onApply: (HeatmapFilter) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(applied.mode) }
    var period by rememberSaveable { mutableStateOf(applied.period) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Filtreler", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("Dönem", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeatmapPeriod.entries.forEach { option ->
                    FilterChip(
                        selected = period == option, onClick = { period = option }, label = { Text(option.label) },
                        modifier = Modifier.testTag("heatmap_period_${option.name}"),
                    )
                }
            }
            Text("Ulaşım türü", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = draft == null, onClick = { draft = null }, label = { Text("Tümü") },
                    leadingIcon = { Icon(Icons.Outlined.Layers, contentDescription = null, Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("heatmap_filter_all"),
                )
                transportDisplayOrder.forEach { mode ->
                    FilterChip(
                        selected = draft == mode, onClick = { draft = mode },
                        label = { Text(mode.label()) },
                        leadingIcon = { Icon(mode.icon(), contentDescription = null, Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("heatmap_filter_${mode.name}"),
                    )
                }
            }
            Text(
                "Her yolculuk yaklaşık 30 metrelik bölgede bir kez sayılır. Beklemek veya aynı yolculukta geri dönmek sıklığı artırmaz.",
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
            Button(
                onClick = { onApply(HeatmapFilter(draft, period)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("heatmap_apply_filters"),
            ) {
                Text("Filtreleri uygula")
            }
        }
    }
}

@Composable
private fun HeatmapEmptyState(selection: HeatmapFilter, modifier: Modifier) {
    val selected = selection.mode
    Surface(modifier.testTag("heatmap_empty"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.TravelExplore, contentDescription = null, Modifier.size(32.dp), tint = Forest)
            Text(
                when {
                    selection.period != HeatmapPeriod.ALL -> "Bu dönemde seçimine uygun iz yok"
                    selected != null -> "${selected.label()} için henüz iz yok"
                    else -> "İzlerin burada birikecek"
                },
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            if (selection == HeatmapFilter()) Text(
                "Konum içeren bir yolculuk kaydettiğinde geçtiğin yollar burada görünür.",
                style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HeatmapLegend(selection: HeatmapFilter, journeyCount: Int) {
    Surface(Modifier.fillMaxWidth().testTag("heatmap_legend"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Geçiş sıklığı", style = MaterialTheme.typography.labelLarge)
            Text(
                "${selection.period.label} · ${selection.mode?.label() ?: "Tüm ulaşım türleri"} · $journeyCount yolculuk",
                style = MaterialTheme.typography.labelSmall, color = Muted,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                heatmapFrequencyBands.forEach { band ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(
                            Modifier.width(20.dp).height(4.dp).background(
                                Color(band.color.drop(1).toLong(16) or 0xFF000000), RoundedCornerShape(2.dp),
                            )
                        )
                        Text(band.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
