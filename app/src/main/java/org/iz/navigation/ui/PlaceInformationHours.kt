package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.iz.navigation.placeinfo.*
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

internal fun placeOpeningLabel(state: PlaceOpeningState, historical: Boolean): String = when (state) {
    PlaceOpeningState.OPEN -> "${if (historical) "Kaydedilmiş OSM saatlerine" else "OSM saatlerine"} göre şu an açık"
    PlaceOpeningState.CLOSED -> "${if (historical) "Kaydedilmiş OSM saatlerine" else "OSM saatlerine"} göre şu an kapalı"
    PlaceOpeningState.UNKNOWN -> "Açıklık durumu belirlenemedi"
    PlaceOpeningState.MISSING -> "Saat bilgisi yok"
}

@Composable
internal fun PlaceInformationHoursDetail(hours: PlaceHoursResult, zone: EffectivePlaceZone, notice: String? = null, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Saat programı") }, text = {
        Column(Modifier.testTag("place-info-hours-detail").verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Saat dilimi: ${zone.zone.id}")
            notice?.let { Text(it) }
            if (hours.weeklyProgram != null) hours.weeklyProgram!!.forEach { day ->
                Text(day.day.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("tr-TR")), Modifier.semantics { heading() })
                Text(if (day.intervals.isNotEmpty()) day.intervals.joinToString(" · ") { interval ->
                    "${minuteLabel(interval.startMinute)}–${minuteLabel(interval.endMinute)}" +
                        if (interval.endMinute <= interval.startMinute) " (ertesi gün)" else ""
                } else when (day.state) {
                    PlaceOpeningState.CLOSED -> "Kapalı"
                    PlaceOpeningState.OPEN -> "Açık"
                    PlaceOpeningState.UNKNOWN -> "Durum belirlenemedi"
                    PlaceOpeningState.MISSING -> "OSM'de bilgi yok"
                })
            } else {
                Text("Orijinal OSM saat ifadesi", Modifier.semantics { heading() })
                Text(hours.raw ?: "OSM'de bilgi yok")
            }
        }
    }, confirmButton = { TextButton(onClose, Modifier.heightIn(min = 48.dp).testTag("place-info-hours-close")) { Text("Karta dön") } })
}

private fun minuteLabel(value: Int): String = "%02d:%02d".format(Locale.ROOT, value / 60, value % 60)

@Composable
internal fun PlaceInformationZoneDialog(zone: EffectivePlaceZone, overrideId: String?,
    onChange: (String?) -> Unit, onClose: () -> Unit, instant: Instant = Instant.now()) {
    var input by remember { mutableStateOf("") }
    var selectedId by remember(zone.zone.id) { mutableStateOf(zone.zone.id) }
    val zones = remember(zone.zone.id) { (ZoneId.getAvailableZoneIds() + zone.zone.id).sorted().map { id ->
        val candidate = ZoneId.of(id)
        Triple(candidate, id.substringAfterLast('/').replace('_', ' '),
            candidate.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("tr-TR")))
    } }
    val choices = remember(input, zones) { zones.filter { (candidate, city, label) ->
        "$city $label ${candidate.id}".contains(input.trim(), ignoreCase = true)
    } }
    val list = rememberLazyListState()
    LaunchedEffect(input) { list.scrollToItem(0) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Saat dilimini değiştir") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Seçili: $selectedId", Modifier.semantics { selected = true })
            Text("Şehir veya saat dilimi adıyla ara ve listeden seç. Tercihin yalnız bu yer için bu telefonda saklanır.")
            OutlinedTextField(input, { input = it }, label = { Text("Şehir veya saat dilimi ara") },
                modifier = Modifier.fillMaxWidth().testTag("place-info-zone-input"))
            if (choices.isEmpty()) Text("Eşleşen saat dilimi yok. Başka bir şehir veya ad dene.")
            LazyColumn(Modifier.fillMaxWidth().height(220.dp).testTag("place-info-zone-options"), state = list) {
                items(choices, key = { it.first.id }) { (candidate, city, label) ->
                    val offset = candidate.rules.getOffset(instant).id.let { if (it == "Z") "+00:00" else it }
                    TextButton({ selectedId = candidate.id }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("place-info-zone-option-${candidate.id}").semantics { selected = selectedId == candidate.id }) {
                        RadioButton(selected = selectedId == candidate.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { Text("$city · UTC$offset"); Text("$label · ${candidate.id}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            TextButton({ onChange(null); onClose() }, enabled = overrideId != null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("place-info-zone-reset")) { Text("Yerel tercihi kaldır") }
        }
    }, confirmButton = { TextButton({ onChange(selectedId); onClose() }, enabled = choices.any { it.first.id == selectedId },
        modifier = Modifier.heightIn(min = 48.dp).testTag("place-info-zone-save")) { Text("Kaydet") } },
        dismissButton = { TextButton(onClose, Modifier.heightIn(min = 48.dp).testTag("place-info-zone-cancel")) { Text("Vazgeç") } })
}
