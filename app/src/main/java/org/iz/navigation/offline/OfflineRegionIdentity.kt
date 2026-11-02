package org.iz.navigation.offline

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Locale

/** Immutable region definition only. Job generations, native handles and transfer counters are excluded. */
internal object OfflineRegionIdentity {
    fun key(pack: OfflinePack, auditedStyleSha256: String): String {
        val request = pack.request
        require(auditedStyleSha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(request.routeId.length in 1..300 && request.sourceFingerprint.matches(Regex("[0-9a-fA-F]{64}")))
        require(request.line.size in 2..512 && pack.rings.size in 1..2048 && pack.rings.sumOf { it.size } <= 20_000)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF("iz.offline.region.v2:maplibre-android13.6.1")
            out.writeUTF(request.routeId)
            out.writeUTF(request.sourceFingerprint.lowercase(Locale.ROOT))
            out.writeUTF(request.styleUrl)
            out.writeUTF(auditedStyleSha256.lowercase(Locale.ROOT))
            out.writeDouble(request.widthMeters)
            out.writeInt(request.minZoom); out.writeInt(request.maxZoom)
            out.writeFloat(1f); out.writeBoolean(false) // exact SDK pixelRatio/includeIdeographs
            fun points(points: List<OfflinePoint>) {
                out.writeInt(points.size)
                points.forEach {
                    require(it.latitude.isFinite() && it.longitude.isFinite())
                    out.writeDouble(it.latitude); out.writeDouble(it.longitude)
                }
            }
            points(request.line)
            out.writeInt(pack.rings.size)
            pack.rings.forEach(::points)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun metadata(pack: OfflinePack, auditedStyleSha256: String) =
        ("iz.offline.v2:" + key(pack, auditedStyleSha256)).toByteArray(Charsets.UTF_8)
    fun matches(metadata: ByteArray, pack: OfflinePack, auditedStyleSha256: String): Boolean = runCatching {
        metadata.size == 78 && MessageDigest.isEqual(metadata, OfflineRegionIdentity.metadata(pack, auditedStyleSha256))
    }.getOrDefault(false)
}
