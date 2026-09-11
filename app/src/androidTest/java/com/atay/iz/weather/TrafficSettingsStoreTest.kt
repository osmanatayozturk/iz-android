package com.atay.iz.weather

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
}
