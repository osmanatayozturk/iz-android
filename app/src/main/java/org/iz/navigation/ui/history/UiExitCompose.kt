package org.iz.navigation.ui.history

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

val LocalUiExitGuard = staticCompositionLocalOf<UiExitGuard?> { null }
val LocalUiEntryKey = staticCompositionLocalOf { "standalone" }
val LocalUiHistory = staticCompositionLocalOf<UiHistory?> { null }
val LocalUiOperationBusy = staticCompositionLocalOf<() -> Boolean> { { false } }

/** Keep mounted for local child pickers. Only disposal of the exact owner clears its state. */
@Composable
fun UiEditOwner(state: UiEditState, discardUnsaved: () -> Unit = {}) {
    UiEditOwner(state, currentState = { state }, discardUnsaved = discardUnsaved)
}

@Composable
fun UiEditOwner(state: UiEditState, currentState: () -> UiEditState, discardUnsaved: () -> Unit) {
    val guard = LocalUiExitGuard.current ?: return
    val owner = remember(state.entryKey, state.accountKey) { Any() }
    val operationBusy = LocalUiOperationBusy.current
    SideEffect { guard.updateOwned(owner, state.copy(busy = state.busy || operationBusy()),
        currentState = { val live = currentState(); live.copy(busy = live.busy || operationBusy()) }, discardUnsaved = discardUnsaved) }
    DisposableEffect(guard, owner) {
        onDispose { guard.clearOwner(owner) }
    }
}

@Composable
fun UiExitConfirmation(guard: UiExitGuard) {
    val pending by guard.pending.collectAsState()
    val edit by guard.editState.collectAsState()
    if (pending != null) AlertDialog(onDismissRequest = guard::keepEditing,
        title = { Text("Kaydedilmemiş değişiklikler var") },
        text = { Text("Bu sayfadan ayrılırsan yalnızca kaydedilmemiş değişikliklerin silinecek.") },
        confirmButton = { TextButton(onClick = guard::discardAndExit, enabled = edit?.busy != true,
            modifier = Modifier.testTag("ui-exit-discard")) { Text("Değişikliklerden vazgeç") } },
        dismissButton = { TextButton(onClick = guard::keepEditing,
            modifier = Modifier.testTag("ui-exit-keep")) { Text("Düzenlemeye dön") } })
}
