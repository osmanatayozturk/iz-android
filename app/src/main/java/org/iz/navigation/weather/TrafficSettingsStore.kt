package org.iz.navigation.weather

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Public settings intentionally contain only presence and authorization, never the secret. */
data class TrafficSettings(
    val enabled: Boolean = false,
    val hasKey: Boolean = false,
    val freePlanAcknowledged: Boolean = false,
    val revision: String = "empty",
    val freeAccountVerified: Boolean = false,
    val matrixEnabled: Boolean = false,
    val speedFallbackEnabled: Boolean = false,
)

/** Not a data class: generated toString/copy must never accidentally disclose an API key. */
internal class TrafficCredentials(
    val apiKey: String?,
    val enabled: Boolean,
    val freePlanAcknowledged: Boolean,
    val revision: String,
    val freeAccountVerified: Boolean = false,
    val matrixEnabled: Boolean = false,
    val speedFallbackEnabled: Boolean = false,
) {
    override fun toString() = "TrafficCredentials(redacted)"
}

/** A separate non-exportable AndroidKeyStore key; ciphertext is excluded from Android backup. */
class TrafficSettingsStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "traffic")
    private val file = AtomicFile(File(directory, "settings.bin"))

    fun read(): TrafficSettings = credentials().let {
        TrafficSettings(it.enabled, !it.apiKey.isNullOrBlank(), it.freePlanAcknowledged, it.revision,
            it.freeAccountVerified, it.matrixEnabled, it.speedFallbackEnabled)
    }

    internal fun credentials(): TrafficCredentials = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized emptyCredentials()
        try {
            require(file.baseFile.length() in 29..16_384)
            val encrypted = file.openRead().use { it.readBytes() }
            require(encrypted.size in 29..16_384)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            val bytes = cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
            try {
                val value = JSONObject(String(bytes, Charsets.UTF_8))
                require(value.getInt("version") == 1)
                val apiKey = value.optString("key").takeIf(String::isNotBlank)
                if (apiKey != null) validateKey(apiKey)
                val acknowledged = value.getBoolean("freePlanAcknowledged")
                val verified = value.optBoolean("freeAccountVerified") && apiKey != null && acknowledged
                TrafficCredentials(apiKey, value.getBoolean("enabled") && apiKey != null && acknowledged,
                    acknowledged, value.getString("revision"), verified,
                    verified && value.optBoolean("matrixEnabled"), verified && value.optBoolean("speedFallbackEnabled"))
            } finally { bytes.fill(0) }
        } catch (_: Exception) {
            // Fail closed after key invalidation or ciphertext tampering. No exception/key logging.
            emptyCredentials("unavailable")
        }
    }

    /** Null retains the existing key; clear() explicitly removes credentials and authorization. */
    fun save(apiKey: String? = null, enabled: Boolean, freePlanAcknowledged: Boolean,
        freeAccountVerified: Boolean? = null, matrixEnabled: Boolean? = null, speedFallbackEnabled: Boolean? = null) = synchronized(lock) {
        val previous = credentials()
        val value = apiKey?.trim() ?: previous.apiKey
        if (value != null) validateKey(value)
        require(!enabled || !value.isNullOrBlank()) { "Trafik için kişisel TomTom anahtarını gir." }
        require(!enabled || freePlanAcknowledged) { "Yalnızca ücretsiz kullanım onayını işaretle." }
        val sameKey = value == previous.apiKey
        val verified = freePlanAcknowledged && value != null && (freeAccountVerified ?: (sameKey && previous.freeAccountVerified))
        val matrix = matrixEnabled ?: (sameKey && previous.matrixEnabled && verified)
        val speed = speedFallbackEnabled ?: (sameKey && previous.speedFallbackEnabled && verified)
        require(!(matrix || speed) || verified) { "Ücretsiz hesap ve ilgili API erişimini doğrula." }
        val json = JSONObject().put("version", 1).put("key", value.orEmpty()).put("enabled", enabled)
            .put("freePlanAcknowledged", freePlanAcknowledged).put("revision", UUID.randomUUID().toString())
            .put("freeAccountVerified", verified).put("matrixEnabled", matrix).put("speedFallbackEnabled", speed)
        try {
            check(directory.isDirectory || directory.mkdirs())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val ciphertext = try { cipher.iv + cipher.doFinal(bytes) } finally { bytes.fill(0) }
            val stream = file.startWrite()
            try {
                stream.write(ciphertext)
                file.finishWrite(stream)
                changeGeneration++
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        } catch (_: Exception) {
            throw IllegalStateException("Trafik anahtarı güvenli biçimde kaydedilemedi.")
        }
    }

    fun clear() = synchronized(lock) { file.delete(); changeGeneration++ }

    /** Enables independently verified free services without exposing or replacing the stored key. */
    fun saveCapabilities(freeAccountVerified: Boolean, matrixEnabled: Boolean, speedFallbackEnabled: Boolean) = synchronized(lock) {
        val current = credentials()
        save(enabled = current.enabled, freePlanAcknowledged = current.freePlanAcknowledged,
            freeAccountVerified = freeAccountVerified, matrixEnabled = matrixEnabled, speedFallbackEnabled = speedFallbackEnabled)
    }

    internal fun matrixCredentials(): TrafficCredentials? = credentials().takeIf { it.matrixEnabled }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    companion object {
        @Volatile internal var changeGeneration: Long = 0
            private set
        private const val ALIAS = "org.iz.navigation.traffic.personal.v1"
        private val lock = Any()
        private fun emptyCredentials(revision: String = "empty") = TrafficCredentials(null, false, false, revision)
        private fun validateKey(value: String) {
            require(value.length in 8..256 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) {
                "TomTom anahtarının biçimini kontrol et."
            }
        }
    }
}
