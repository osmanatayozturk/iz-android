package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.iz.navigation.weather.RouteStop

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlannedBreakEditor(stop: RouteStop, enabled: Boolean, onMinutesChanged: (Int) -> Unit) {
    val plannedSeconds = stop.plannedBreakSeconds
    var editing by remember(stop) { mutableStateOf(false) }
    var draft by remember(stop) { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            draft = if (plannedSeconds == 0) "" else (plannedSeconds / 60).toString()
            editing = true
        }, enabled = enabled, modifier = Modifier.semantics {
            contentDescription = "${stop.label}: " + if (plannedSeconds == 0) "Mola ekle" else "Molayı düzenle"
        }) {
            Text(if (plannedSeconds == 0) "Mola ekle" else "Molayı düzenle · ${formatDuration(plannedSeconds.toDouble())}")
        }
        if (plannedSeconds > 0) {
            TextButton(onClick = { onMinutesChanged(0) }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "${stop.label}: Molayı kaldır" }) { Text("Molayı kaldır") }
        }
    }
    if (!editing) return
    val minutes = draft.toIntOrNull()?.takeIf { it in 0..240 }
    AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("${stop.label} · " + if (plannedSeconds == 0) "Mola ekle" else "Molayı düzenle") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { value -> if (value.length <= 3 && value.all { it in '0'..'9' }) draft = value },
                label = { Text("Mola süresi (dakika)") },
                supportingText = { Text("0–240 dakika") },
                singleLine = true,
                isError = draft.isNotEmpty() && minutes == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                editing = false
                minutes?.let(onMinutesChanged)
            }, enabled = enabled && minutes != null) { Text("Uygula") }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Vazgeç") } },
    )
}
