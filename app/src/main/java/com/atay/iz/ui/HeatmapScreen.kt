package com.atay.iz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.atay.iz.data.Transport
import com.atay.iz.integration.HeatmapColors
import com.atay.iz.integration.HeatmapMap
import com.atay.iz.integration.ExpandableMap
import com.atay.iz.integration.buildHeatmapData

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeatmapScreen(state: DiaryState, now: Long = System.currentTimeMillis()) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var showMapFilters by remember { mutableStateOf(false) }
    val selected = Transport.entries.firstOrNull { it.name == filter }
    val data = remember(state.journeys, state.points, selected, now) {
        buildHeatmapData(state.journeys, state.points, selected, now)
    }
    Column(Modifier.fillMaxSize().testTag("heatmap_screen")) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("Isı haritan", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(6.dp))
            Text("Sık geçtiğin yerleri keşfet.", color = Muted)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = selected == null, onClick = { filter = null },
                    label = { Text("Tümü") },
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
            if (data.cells.isEmpty()) {
                Surface(mapModifier.testTag("heatmap_empty"), color = Color.White) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.TravelExplore, null, Modifier.size(44.dp), tint = Forest)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (selected == null) "İzlerin burada birikecek" else "${selected.label()} için henüz iz yok",
                            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Konum içeren bir yolculuk kaydettiğinde geçtiğin bölgeler burada görünecek.",
                            color = Muted, textAlign = TextAlign.Center,
                        )
                    }
                }
            } else HeatmapMap(data, filter ?: "ALL", mapModifier, expandable = false)
            }
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (data.cells.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${selected?.label() ?: "Tüm ulaşım türleri"} · ${data.journeyCount} yolculuk", style = MaterialTheme.typography.labelLarge)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(Brush.horizontalGradient(HeatmapColors)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Az yoğun", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("Çok yoğun", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            Text(
                "Renkler, seçili yolculuklarda aynı bölgeden ne kadar sık geçtiğini gösterir. Her yolculuk yaklaşık 30 metrelik bölgede bir kez sayılır; bekleme süresi yoğunluğu artırmaz.",
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
        }
    }
}
