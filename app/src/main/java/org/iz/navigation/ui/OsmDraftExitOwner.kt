package org.iz.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.iz.navigation.ui.history.LocalUiEntryKey
import org.iz.navigation.ui.history.LocalUiExitGuard
import org.iz.navigation.ui.history.UiEditOwner
import org.iz.navigation.ui.history.UiEditState

/** Ephemeral form revisions only; never a new SavedState/history/draft store. */
private class OsmFormRevision {
    private var previous: List<Any?>? = null
    private var revision = 0L
    private var acknowledged = 0L
    fun read(entry: String, account: String?, working: List<Any?>, baseline: List<Any?>, busy: Boolean): UiEditState {
        if (previous != working) { previous = working.toList(); revision++ }
        val dirty = working != baseline
        if (!dirty) acknowledged = revision
        return UiEditState(entry, account, revision, acknowledged, dirty, busy)
    }
}

@Composable
internal fun OsmDraftExitOwner(
    owner: String,
    accountKey: () -> String?,
    working: () -> List<Any?>,
    baseline: () -> List<Any?>,
    busy: () -> Boolean,
    discard: () -> Unit,
): (() -> Unit) -> Unit {
    val entry = LocalUiEntryKey.current + "/" + owner
    val guard = LocalUiExitGuard.current
    val revisions = remember(entry, accountKey()) { OsmFormRevision() }
    fun liveState() = revisions.read(entry, accountKey(), working(), baseline(), busy())
    UiEditOwner(liveState(), currentState = ::liveState, discardUnsaved = discard)
    return { exit ->
        if (!busy()) {
            if (guard == null) exit() else {
                guard.requestExit(exit)
            }
        }
    }
}