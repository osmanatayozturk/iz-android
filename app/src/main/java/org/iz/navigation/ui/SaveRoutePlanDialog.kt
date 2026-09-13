package org.iz.navigation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.iz.navigation.data.SavedRoutePlan

@Composable
internal fun SaveRoutePlanDialog(plan: SavedRoutePlan, busy: Boolean, onDismiss: () -> Unit,
    onSave: (SavedRoutePlan) -> Unit) {
    var name by rememberSaveable(plan.id) { mutableStateOf(plan.name) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Rotayı kaydet") }, text = {
        Column {
            OutlinedTextField(value = name, onValueChange = { if (it.length <= 300) name = it },
                label = { Text("Rota adı") }, singleLine = true, enabled = !busy,
                modifier = Modifier.testTag("saved-route-name"))
            Text("Duraklar ve yol tercihlerin saklanır. Açtığında rota yeniden hesaplanır.")
        }
    }, confirmButton = {
        Button(onClick = { onSave(plan.copy(name = name.trim())) }, enabled = !busy && name.isNotBlank(),
            modifier = Modifier.testTag("save-route-confirm")) { Text("Kaydet") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Vazgeç") } })
}
