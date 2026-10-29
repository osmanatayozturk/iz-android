package org.iz.navigation.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** An owner's Back/outside-dismiss path. Explicit Save/Cancel retain feature-defined semantics. */
@Composable
fun rememberUiEditExit(feature: String, working: Any?, baseline: Any?, busy: Boolean = false,
    accountKey: String? = null, discardUnsaved: () -> Unit = {},
    currentWorking: () -> Any? = { working }, currentBaseline: () -> Any? = { baseline },
    currentBusy: () -> Boolean = { busy }, exit: () -> Unit): () -> Unit {
    val guard = LocalUiExitGuard.current
    val key = LocalUiEntryKey.current + "/" + feature
    val revisions = remember(key, accountKey) { UiWorkingRevision() }
    val state = revisions.observe(working, baseline)
    UiEditOwner(UiEditState(key, accountKey, state.revision, state.acknowledged, state.dirty, busy),
        currentState = {
            val live = revisions.observe(currentWorking(), currentBaseline())
            UiEditState(key, accountKey, live.revision, live.acknowledged, live.dirty, currentBusy())
        }, discardUnsaved = discardUnsaved)
    return { if (guard == null) exit() else guard.requestExit(exit) }
}
