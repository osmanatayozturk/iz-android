package org.iz.navigation.ui.history

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import java.lang.ref.WeakReference

/** Framework SavedState owners only; feature handles keep their existing field/size policy. */
internal class UiEntrySavedStateOwners {
    private data class Key(val feature: String, val entry: String) {
        val name get() = PREFIX + feature + "/" + entry
    }
    private class Owner(application: Application, restored: Bundle?) :
        ViewModelStoreOwner, SavedStateRegistryOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = ViewModelStore()
        private val states = SavedStateRegistryController.create(this)
        private val life = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = life
        override val savedStateRegistry: SavedStateRegistry get() = states.savedStateRegistry
        override val defaultViewModelProviderFactory = SavedStateViewModelFactory(application, this)
        override val defaultViewModelCreationExtras: CreationExtras = MutableCreationExtras().apply {
            this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = application
            this[SAVED_STATE_REGISTRY_OWNER_KEY] = this@Owner
            this[VIEW_MODEL_STORE_OWNER_KEY] = this@Owner
        }
        init {
            states.performAttach()
            enableSavedStateHandles()
            states.performRestore(restored)
            life.currentState = Lifecycle.State.CREATED
        }
        fun save() = Bundle().also(states::performSave)
        fun clear() { life.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
    }
    private val owners = mutableMapOf<Key, Owner>()
    // Only structural provider names are indexed here, never a copied draft/body map.
    private val restoredNames = mutableSetOf<String>()
    private var host = WeakReference<SavedStateRegistryOwner>(null)

    fun attach(parent: SavedStateRegistryOwner) {
        if (host.get() === parent) return
        host.get()?.let(::detach)
        host = WeakReference(parent)
        parent.savedStateRegistry.consumeRestoredStateForKey(INDEX)?.getStringArrayList("owners")
            ?.take(UiHistory.MAX_ENTRIES)?.filter { it.startsWith(PREFIX) && it.length <= 256 }
            ?.let(restoredNames::addAll)
        parent.savedStateRegistry.registerSavedStateProvider(INDEX) {
            Bundle().apply { putStringArrayList("owners", ArrayList((restoredNames + owners.keys.map { it.name }).sorted())) }
        }
        owners.forEach { (key, owner) ->
            parent.savedStateRegistry.consumeRestoredStateForKey(key.name)
            restoredNames.remove(key.name)
            parent.savedStateRegistry.registerSavedStateProvider(key.name, owner::save)
        }
    }
    fun detach(parent: SavedStateRegistryOwner) {
        if (host.get() !== parent) return
        owners.keys.forEach { parent.savedStateRegistry.unregisterSavedStateProvider(it.name) }
        parent.savedStateRegistry.unregisterSavedStateProvider(INDEX)
        host.clear()
    }
    fun <T : ViewModel> get(feature: String, entry: String, type: Class<T>, application: Application,
        parent: SavedStateRegistryOwner): T {
        attach(parent)
        val key = Key(feature, entry)
        val owner = owners.getOrPut(key) {
            val restored = parent.savedStateRegistry.consumeRestoredStateForKey(key.name)
            restoredNames.remove(key.name)
            Owner(application, restored).also {
                parent.savedStateRegistry.registerSavedStateProvider(key.name, it::save)
            }
        }
        return ViewModelProvider(owner)[type]
    }
    fun retain(entries: Set<String>) {
        val registry = host.get()?.savedStateRegistry
        owners.keys.filter { it.entry !in entries }.forEach { key ->
            owners.remove(key)?.clear()
            registry?.unregisterSavedStateProvider(key.name)
            registry?.consumeRestoredStateForKey(key.name)
        }
        restoredNames.filter { it.substringAfterLast('/') !in entries }.forEach { name ->
            registry?.consumeRestoredStateForKey(name)
            restoredNames.remove(name)
        }
    }
    fun clear() {
        host.get()?.let(::detach)
        owners.values.forEach(Owner::clear)
        owners.clear(); restoredNames.clear()
    }
    private companion object {
        const val PREFIX = "iz.ui.feature.v1/"
        const val INDEX = "iz.ui.feature.v1.index"
    }
}
