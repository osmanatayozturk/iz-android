package org.iz.navigation.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Framework feature owners, not saved page data. Hidden parents retain their own existing VM; popped owners clear. */
internal class UiEntryViewModels : ViewModel() {
    private data class Key(val feature: String, val entry: String)
    private class Owner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        var onRetain: ((Set<String>) -> Unit)? = null
    }
    private val owners = mutableMapOf<Key, Owner>()
    private val savedOwners = UiEntrySavedStateOwners()
    fun attachSavedStateHost(host: androidx.savedstate.SavedStateRegistryOwner) = savedOwners.attach(host)
    fun detachSavedStateHost(host: androidx.savedstate.SavedStateRegistryOwner) = savedOwners.detach(host)
    fun <T : ViewModel> getSavedState(feature: String, entry: String, type: Class<T>, application: android.app.Application,
        host: androidx.savedstate.SavedStateRegistryOwner): T = savedOwners.get(feature, entry, type, application, host)
    fun <T : ViewModel> get(feature: String, entry: String, type: Class<T>, factory: ViewModelProvider.Factory): T =
        ViewModelProvider(owners.getOrPut(Key(feature, entry)) { Owner() }, factory)[type]
    fun onRetain(feature: String, entry: String, action: (Set<String>) -> Unit) { owners[Key(feature, entry)]?.onRetain = action }
    fun retain(entries: Set<String>) {
        savedOwners.retain(entries)
        val removed = owners.keys.filter { it.entry !in entries }
        removed.forEach { owners.remove(it)?.viewModelStore?.clear() }
        owners.values.forEach { it.onRetain?.invoke(entries) }
    }
    override fun onCleared() { savedOwners.clear(); owners.values.forEach { it.viewModelStore.clear() }; owners.clear() }
}
