package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.iz.navigation.ui.history.rememberUiEditExit
import org.iz.navigation.ui.history.LocalUiOperationBusy
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JourneyEditor(journey: Journey, onDismiss: () -> Unit, onSave: (Journey) -> Unit) {
    val operationBusy = LocalUiOperationBusy.current
    var title by remember(journey.id) { mutableStateOf(journey.title) }
    var note by remember(journey.id) { mutableStateOf(journey.note) }
    var mode by remember(journey.id) { mutableStateOf(journey.transport.name) }
    val working = { listOf(title, note, mode) }
    val baseline = listOf(journey.title, journey.note, journey.transport.name)
    val back = rememberUiEditExit("journey-${journey.id}", working(), baseline,
        currentWorking = working, discardUnsaved = { title = journey.title; note = journey.note; mode = journey.transport.name }, exit = onDismiss)
    AlertDialog(onDismissRequest = back, title = { Text("Yolculuğu düzenle") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { if (!operationBusy()) title = it }, enabled = !operationBusy(), label = { Text("Ad") }, modifier = Modifier.fillMaxWidth())
            Text("Ulaşım türü", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                transportDisplayOrder.forEach { transport ->
                    FilterChip(mode == transport.name, { if (!operationBusy()) mode = transport.name }, { Text(transport.label()) }, enabled = !operationBusy())
                }
            }
            Text(if (mode == Transport.PASSENGER.name) "Başkasının kullandığı araçta yaptığın yolculuk. Kendi sürüşlerinden ayrı tutulur."
                else "Ulaşım türü bu yolculuğun tamamına uygulanır.", style = MaterialTheme.typography.bodySmall, color = Muted)
            OutlinedTextField(note, { if (!operationBusy()) note = it }, enabled = !operationBusy(), label = { Text("Özel not") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { onSave(journey.copy(title = title.trim(), note = note.trim(), transport = Transport.valueOf(mode))) }) { Text("Kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}
