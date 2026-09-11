package com.atay.iz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atay.iz.data.Journey
import com.atay.iz.data.Transport

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JourneyEditor(journey: Journey, onDismiss: () -> Unit, onSave: (Journey) -> Unit) {
    var title by rememberSaveable(journey.id) { mutableStateOf(journey.title) }
    var note by rememberSaveable(journey.id) { mutableStateOf(journey.note) }
    var mode by rememberSaveable(journey.id) { mutableStateOf(journey.transport.name) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Yolculuğu düzenle") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Ad") }, modifier = Modifier.fillMaxWidth())
            Text("Ulaşım türü", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                transportDisplayOrder.forEach { transport ->
                    FilterChip(mode == transport.name, { mode = transport.name }, { Text(transport.label()) })
                }
            }
            Text(if (mode == Transport.PASSENGER.name) "Başkasının kullandığı araçta yaptığın yolculuk. Kendi sürüşlerinden ayrı tutulur."
                else "Ulaşım türü bu yolculuğun tamamına uygulanır.", style = MaterialTheme.typography.bodySmall, color = Muted)
            OutlinedTextField(note, { note = it }, label = { Text("Özel not") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { onSave(journey.copy(title = title.trim(), note = note.trim(), transport = Transport.valueOf(mode))) }) { Text("Kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}
