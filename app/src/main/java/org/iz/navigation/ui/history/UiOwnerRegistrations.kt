package org.iz.navigation.ui.history

/** Runtime nonces only. Configuration may reattach; a real removal or a fresh process cannot. */
class UiOwnerRegistrations {
    data class Key(val entryKey: String, val editorKey: String, val accountKey: String?)
    private data class Slot(val token: Any, var detachedForConfiguration: Boolean = false)
    private val slots = mutableMapOf<Key, Slot>()

    fun acquire(key: Key): Any {
        val detached = slots[key]?.takeIf { it.detachedForConfiguration }
        if (detached != null) {
            detached.detachedForConfiguration = false
            return detached.token
        }
        return Any().also { slots[key] = Slot(it) }
    }
    fun release(key: Key, token: Any, changingConfigurations: Boolean) {
        val slot = slots[key]?.takeIf { it.token === token } ?: return
        if (changingConfigurations) slot.detachedForConfiguration = true else slots.remove(key)
    }
    fun retainEntries(entries: Set<String>) { slots.keys.removeAll { it.entryKey !in entries } }
}
