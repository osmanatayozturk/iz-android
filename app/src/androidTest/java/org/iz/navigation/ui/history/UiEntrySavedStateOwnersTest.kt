package org.iz.navigation.ui.history

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiEntrySavedStateOwnersTest {
    class Probe(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
        var body: String
            get() = saved["body"] ?: ""
            set(value) { saved["body"] = value }
        var cleared = false
        override fun onCleared() { cleared = true }
    }
    private class Host(restored: Bundle? = null) : SavedStateRegistryOwner {
        private val life = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = life
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        init { controller.performAttach(); controller.performRestore(restored); life.currentState = Lifecycle.State.CREATED }
        fun save() = Bundle().also(controller::performSave)
    }
    @Test fun distinctEntryHandlesRetainThroughConfigurationRestoreAndClearOnPop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        instrumentation.runOnMainSync {
            val pool = UiEntryViewModels()
            val firstHost = Host()
            val first = pool.getSavedState("community", "entry-1", Probe::class.java, application, firstHost)
            val second = pool.getSavedState("community", "entry-2", Probe::class.java, application, firstHost)
            assertNotSame(first, second)
            first.body = "Synthetic private parent input"
            second.body = "Synthetic child input"
            val configurationState = firstHost.save()
            pool.detachSavedStateHost(firstHost)
            val nextHost = Host(configurationState)
            pool.attachSavedStateHost(nextHost)
            assertSame(first, pool.getSavedState("community", "entry-1", Probe::class.java, application, nextHost))
            assertSame(second, pool.getSavedState("community", "entry-2", Probe::class.java, application, nextHost))
            assertEquals("Synthetic private parent input", first.body)
            pool.retain(setOf("entry-1"))
            assertTrue(second.cleared)
            assertFalse(first.cleared)
            val processState = nextHost.save()
            pool.retain(emptySet())
            pool.detachSavedStateHost(nextHost)
            val restoredPool = UiEntryViewModels()
            val restoredHost = Host(processState)
            val restored = restoredPool.getSavedState("community", "entry-1", Probe::class.java, application, restoredHost)
            assertNotSame(first, restored)
            assertEquals("Synthetic private parent input", restored.body)
            restoredPool.retain(emptySet())
            assertTrue(restored.cleared)
            assertNull(restoredHost.savedStateRegistry.getSavedStateProvider("iz.ui.feature.v1/community/entry-1"))
            restoredPool.detachSavedStateHost(restoredHost)
        }
    }
}
