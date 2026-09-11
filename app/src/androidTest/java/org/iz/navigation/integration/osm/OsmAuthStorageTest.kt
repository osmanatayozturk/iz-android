package org.iz.navigation.integration.osm

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmAuthStorageTest {
    private lateinit var isolatedDirectory: File
    private lateinit var storage: OsmAuthStorage

    @Before fun createIsolatedStorage() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        isolatedDirectory = File(app.cacheDir, "osm-auth-test-${UUID.randomUUID()}").apply { mkdirs() }
        storage = OsmAuthStorage(object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = isolatedDirectory
        })
    }

    @After fun removeIsolatedStorage() { isolatedDirectory.deleteRecursively() }

    @Test fun credentialsRoundTripWithoutPlaintextOnDisk() {
        val fictionalToken = "fictional-token-for-local-encryption-test"
        val value = JSONObject().put("accessToken", fictionalToken).put("pendingVerifier", "fictional-pkce-verifier")
        storage.write(value)
        val ciphertext = File(isolatedDirectory, "osm-auth/session.bin").readBytes()
        assertFalse(String(ciphertext, Charsets.UTF_8).contains(fictionalToken))
        assertFalse(String(ciphertext, Charsets.UTF_8).contains("fictional-pkce-verifier"))
        assertEquals(fictionalToken, storage.read()?.getString("accessToken"))
        storage.clear()
        assertNull(storage.read())
    }

    @Test fun modifiedCiphertextCannotRestoreAnAccount() {
        storage.write(JSONObject().put("accessToken", "fictional-test-token"))
        val file = File(isolatedDirectory, "osm-auth/session.bin")
        val damaged = file.readBytes()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        file.writeBytes(damaged)
        assertTrue(runCatching { storage.read() }.isFailure)
    }
}
