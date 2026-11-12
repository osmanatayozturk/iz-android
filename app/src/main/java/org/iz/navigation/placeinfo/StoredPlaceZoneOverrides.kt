package org.iz.navigation.placeinfo

import java.time.ZoneId
import java.security.MessageDigest

class StoredPlaceZoneOverrides(private val readValue: (String) -> String?,
    private val writeValue: (String, String?) -> Unit) : PlaceZoneOverrides {
    override fun read(key: PlaceInfoKey): String? = readValue(storageKey(key))?.let {
        if (it.length > 255) null else runCatching { ZoneId.of(it).id }.getOrNull()
    }
    override fun write(key: PlaceInfoKey, zoneId: String?) {
        require(zoneId == null || zoneId.length <= 255)
        val validated = zoneId?.let { requireNotNull(runCatching { ZoneId.of(it).id }.getOrNull()) }
        writeValue(storageKey(key),validated)
    }
    private fun storageKey(key: PlaceInfoKey): String {
        require(key.ref.id > 0)
        val identity = "${key.endpoint}\n${key.ref.type.name}\n${key.ref.id}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
