package org.iz.navigation.ui.history

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder

private class BrowseListReference { lateinit var state: LazyListState }

/** Provider surrounds ONLY the list index/offset Saver, never its screen's editors/auth children. */
@Composable
fun rememberUiBrowseListState(holder: SaveableStateHolder, entryKey: String): LazyListState {
    val reference = remember(entryKey) { BrowseListReference() }
    holder.SaveableStateProvider("list/$entryKey") { reference.state = rememberLazyListState() }
    return reference.state
}
