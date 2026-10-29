package org.iz.navigation.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun rememberUiHistory(): UiHistory = rememberSaveable(saver = Saver<UiHistory, Any>(
    save = { it.save() }, restore = { UiHistory.restore(it) })) { UiHistory() }
