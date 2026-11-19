package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.ui.history.LocalUiOperationBusy
import org.iz.navigation.ui.history.rememberUiEditExit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JourneyEditor(journey: Journey, onDismiss: () -> Unit, onSave: (Journey) -> Unit) {
    val operationBusy = LocalUiOperationBusy.current
    var title by remember(journey.id) { mutableStateOf(journey.title) }
    var note by remember(journey.id) { mutableStateOf(journey.note) }
    var mode by remember(journey.id) { mutableStateOf(journey.transport.name) }
    val working = { listOf(title, note, mode) }
    val baseline = listOf(journey.title, journey.note, journey.transport.name)
    val busy = operationBusy()
    val back = rememberUiEditExit(
        "journey-${journey.id}", working(), baseline, busy = busy, currentWorking = working, currentBusy = operationBusy,
        discardUnsaved = { title = journey.title; note = journey.note; mode = journey.transport.name },
        exit = onDismiss,
    )
    val requestBack = { if (!operationBusy()) back() }
    Dialog(onDismissRequest = requestBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("journey-editor")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                TravelScreenHeader("Yolculuğu düzenle", requestBack, enabled = !busy)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(title, { if (!operationBusy()) title = it }, enabled = !busy,
                        label = { Text("Yolculuk adı") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("journey-editor-title"))
                    Text("Vasıta", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        transportDisplayOrder.forEach { transport ->
                            FilterChip(mode == transport.name, { if (!operationBusy()) mode = transport.name },
                                { Text(transport.label()) }, enabled = !busy)
                        }
                    }
                    if (mode == Transport.PASSENGER.name) {
                        Text("Yolcu olarak yapılan yolculuklar kendi sürüşlerinden ayrı tutulur.",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    OutlinedTextField(note, { if (!operationBusy()) note = it }, enabled = !busy,
                        label = { Text("Özel notlar") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        if (!operationBusy()) onSave(journey.copy(title = title.trim(), note = note.trim(), transport = Transport.valueOf(mode)))
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("journey-editor-save"),
                ) { Text("Kaydet") }
            }
        }
    }
}

@Composable
internal fun TravelScreenHeader(title: String, onBack: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = enabled) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Geri")
        }
        Text(title, Modifier.weight(1f).padding(end = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.headlineSmall)
    }
}
