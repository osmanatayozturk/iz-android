package org.iz.navigation.weather

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrafficSettingsStoreTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var store: TrafficSettingsStore
    @Before fun isolate() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        directory = File(app.cacheDir, "traffic-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        store = TrafficSettingsStore(context)
    }
    @After fun clean() { directory.deleteRecursively() }

    @Test fun keyIsEncryptedExcludedFromBackupAndNotExposedBySettings() {
        store.save("fictional-personal-key", true, true)
        assertTrue(store.read().enabled)
        assertTrue(store.read().hasKey)
        assertEquals("fictional-personal-key", TrafficSettingsStore(context).credentials().apiKey)
        val ciphertext = File(directory, "traffic/settings.bin").readBytes()
        assertFalse(String(ciphertext, Charsets.UTF_8).contains("fictional-personal-key"))
        assertFalse(store.read().toString().contains("fictional-personal-key"))
        assertFalse(store.credentials().toString().contains("fictional-personal-key"))
        val revision = store.read().revision
        store.clear()
        assertFalse(store.read().hasKey)
        assertFalse(store.read().enabled)
        assertNotEquals(revision, store.read().revision)
    }

    @Test fun enablingRequiresPersonalKeyAndFreePlanAcknowledgement() {
        assertThrows(IllegalArgumentException::class.java) { store.save(null, true, true) }
        assertThrows(IllegalArgumentException::class.java) { store.save("fictional-key", true, false) }
        assertFalse(store.read().enabled)
        store.save("fictional-key", true, true)
        store.save(enabled = false, freePlanAcknowledged = true)
        assertTrue(store.read().hasKey)
        assertFalse(store.read().enabled)
    }

    @Test fun tamperedCiphertextFailsClosedWithoutRestoringCredentials() {
        store.save("fictional-key", true, true)
        val file = File(directory, "traffic/settings.bin")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFalse(store.read().enabled)
        assertNull(store.credentials().apiKey)
    }

    @Test fun newCapabilitiesDefaultOffAndRetainExistingEncryptedKeyWhenEnabled() {
        store.save("fictional-personal-key", true, true)
        assertFalse(store.read().matrixEnabled)
        assertFalse(store.read().speedFallbackEnabled)
        assertFalse(store.read().freeAccountVerified)
        val oldRevision = store.read().revision
        store.saveCapabilities(true, true, true)
        assertEquals("fictional-personal-key", store.credentials().apiKey)
        assertTrue(store.read().enabled)
        assertTrue(store.read().matrixEnabled)
        assertTrue(store.read().speedFallbackEnabled)
        assertNotEquals(oldRevision, store.read().revision)
        store.save(enabled = false, freePlanAcknowledged = true)
        assertTrue(store.read().matrixEnabled)
        assertTrue(store.read().speedFallbackEnabled)
        store.save("different-fictional-key", enabled = false, freePlanAcknowledged = true)
        assertFalse(store.read().freeAccountVerified)
        assertFalse(store.read().matrixEnabled)
        assertFalse(store.read().speedFallbackEnabled)
    }

    @Test fun advancedServicesCannotBypassFreeAccountVerification() {
        store.save("fictional-personal-key", true, true)
        assertThrows(IllegalArgumentException::class.java) { store.saveCapabilities(false, true, false) }
        assertThrows(IllegalArgumentException::class.java) { store.saveCapabilities(false, false, true) }
        assertEquals("fictional-personal-key", store.credentials().apiKey)
        assertFalse(store.read().matrixEnabled)
    }
}
