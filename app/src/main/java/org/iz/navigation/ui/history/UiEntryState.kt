package org.iz.navigation.ui.history

import androidx.compose.runtime.*
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal class UiEntryStorage<T>(owner: String, value: T) {
    var owner by mutableStateOf(owner)
    var value by mutableStateOf(value)
    var revision by mutableLongStateOf(0)
}

/** Ephemeral modal input belongs to its composing entry; late old callbacks cannot mutate a replacement. */
class UiEntryState<T> internal constructor(private val history: UiHistory, private val renderedEntry: String,
    private val storage: UiEntryStorage<T>, private val initial: () -> T) : ReadWriteProperty<Any?, T> {
    val revision: Long get() = storage.revision
    override fun getValue(thisRef: Any?, property: KProperty<*>): T =
        if (storage.owner == history.current.key && renderedEntry == history.current.key) storage.value else initial()
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { setForEntry(renderedEntry, value) }
    fun setForEntry(entry: String, value: T) {
        if (history.current.key == entry) { storage.owner = entry; storage.value = value; storage.revision++ }
    }
}

@Composable
fun <T> rememberUiEntryState(history: UiHistory, onDrop: () -> Unit = {}, initial: () -> T): UiEntryState<T> {
    val key = history.current.key
    val storage = remember(history) { UiEntryStorage(key, initial()) }
    LaunchedEffect(key) { if (storage.owner != key) { storage.owner = key; storage.value = initial(); onDrop() } }
    return UiEntryState(history, key, storage, initial)
}
