package org.iz.navigation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.ExpandableMap
import org.iz.navigation.integration.HeatmapMapContent
import org.iz.navigation.integration.heatmapFrequencyBands
import org.iz.navigation.integration.rememberHeatmapRender

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeatmapScreen(state: DiaryState, now: Long = System.currentTimeMillis()) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var showMapFilters by remember { mutableStateOf(false) }
    var mapShown by remember { mutableStateOf(false) }
    val selected = Transport.entries.firstOrNull { it.name == filter }
    val render = rememberHeatmapRender(state.journeys, state.points, selected, now)
    LaunchedEffect(render) { if (render?.hasGeometry == true) mapShown = true }
    Column(Modifier.fillMaxSize().testTag("heatmap_screen")) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("Isı haritan", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(6.dp))
            Text("Sık geçtiğin yolları keşfet.", color = Muted)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = selected == null, onClick = { filter = null }, label = { Text("Tümü") },
                    leadingIcon = { Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("heatmap_filter_all"),
                )
                transportDisplayOrder.forEach { mode ->
                    FilterChip(
                        selected = selected == mode, onClick = { filter = mode.name },
                        label = { Text(mode.label()) },
                        leadingIcon = { Icon(mode.icon(), null, Modifier.size(18.dp)) },
                        colors = if (mode == Transport.RUN) FilterChipDefaults.filterChipColors(
                            selectedContainerColor = mode.badgeColor(), selectedLabelColor = mode.accentColor(),
                            selectedLeadingIconColor = mode.accentColor(),
                        ) else FilterChipDefaults.filterChipColors(),
                        modifier = Modifier.testTag("heatmap_filter_${mode.name}"),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp))) {
            ExpandableMap(Modifier.fillMaxSize(), title = "Isı haritası", actions = {
                IconButton(onClick = { showMapFilters = true }) { Icon(Icons.Outlined.Tune, "Isı haritasını filtrele") }
                if (showMapFilters) AlertDialog(onDismissRequest = { showMapFilters = false },
                    title = { Text("Ulaşım türü") }, text = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected == null, { filter = null }, { Text("Tümü") })
                            transportDisplayOrder.forEach { mode ->
                                FilterChip(selected == mode, { filter = mode.name }, { Text(mode.label()) })
                            }
                        }
                    }, confirmButton = { TextButton(onClick = { showMapFilters = false }) { Text("Haritaya dön") } })
            }) { mapModifier ->
                Column(mapModifier) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Retain the native map across empty/loading results to preserve the camera.
                        if (mapShown || render?.hasGeometry == true) {
                            HeatmapMapContent(render, filter ?: "ALL", Modifier.fillMaxSize())
                        }
                        if (render == null) {
                            Surface(Modifier.fillMaxSize().testTag("heatmap_loading")) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.height(12.dp))
                                    Text("İzler hazırlanıyor…", color = Muted)
                                }
                            }
                        } else if (!render.hasGeometry) {
                            HeatmapEmptyState(selected, Modifier.fillMaxSize())
                        }
                    }
                    if (render?.hasGeometry == true) HeatmapLegend(selected, render.data.journeyCount)
                }
            }
        }
        Text(
            "Her yolculuk yaklaşık 30 metrelik bölgede bir kez sayılır. Beklemek veya aynı yolculukta geri dönmek sıklığı artırmaz.",
            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
    }
}

@Composable
private fun HeatmapEmptyState(selected: Transport?, modifier: Modifier) {
    Surface(modifier.testTag("heatmap_empty"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.TravelExplore, null, Modifier.size(44.dp), tint = Forest)
            Spacer(Modifier.height(16.dp))
            Text(if (selected == null) "İzlerin burada birikecek" else "${selected.label()} için henüz iz yok",
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Konum içeren bir yolculuk kaydettiğinde geçtiğin yollar burada görünecek.",
                color = Muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HeatmapLegend(selected: Transport?, journeyCount: Int) {
    Surface(Modifier.fillMaxWidth().testTag("heatmap_legend"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Geçiş sıklığı", style = MaterialTheme.typography.labelLarge)
            Text("${selected?.label() ?: "Tüm ulaşım türleri"} · $journeyCount yolculuk",
                style = MaterialTheme.typography.labelSmall, color = Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                heatmapFrequencyBands.forEach { band ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.width(24.dp).height(3.dp).background(
                            Color(band.color.drop(1).toLong(16) or 0xFF000000), RoundedCornerShape(2.dp)))
                        Text(band.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
