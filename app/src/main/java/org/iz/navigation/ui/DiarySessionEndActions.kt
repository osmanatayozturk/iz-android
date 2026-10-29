package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.Journey
import org.iz.navigation.navigation.NavigationState

/** Diary entry uses the same distinct actions and captured consequences as navigation. */
@Composable
internal fun DiarySessionEndActions(target: DiarySessionEndTarget, journey: Journey?, current: NavigationState,
    locked: Boolean, onDismiss: () -> Unit, onConfirm: (DiarySessionEndTarget, PhoneSessionEndRequest?) -> Unit) {
    val matches = target.matches(journey, current)
    LaunchedEffect(matches) { if (!matches) onDismiss() }
    if (!matches) return
    var selected by remember(target) { mutableStateOf<PhoneSessionEndRequest?>(null) }
    if (target.snapshot == null) {
        PhoneEndConfirmation("Yolculuk kaydı bitsin mi?", "Yolculuk kaydı bitecek.", PhoneSessionEnd.RECORDING.label,
            locked, onDismiss, { if (target.matches(journey, current)) onConfirm(target, null) })
    } else {
        val request = selected
        if (request != null) PhoneSessionEndConfirmation(request, current, locked, onDismiss,
            { confirmed -> if (target.matches(journey, current)) onConfirm(target, confirmed) })
        else AlertDialog(onDismissRequest = onDismiss, title = { Text("Yolculuk işlemleri") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneSessionEnd.entries.forEach { action ->
                    val choice = phoneSessionEndRequest(action, current)?.takeIf { it.snapshot == target.snapshot }
                    if (choice != null) OutlinedButton(onClick = { selected = choice }, enabled = !locked,
                        modifier = Modifier.fillMaxWidth().testTag("diary-end-choice-${action.name}")) { Text(action.label) }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
    }
}