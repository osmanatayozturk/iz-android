package org.iz.navigation.placeinfo

import java.io.File
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class CachedPublicPlace(val snapshot: PublicPlaceSnapshot?, val unavailableStatus: Int? = null)
class PublicPlaceCache(private val directory: File, private val clock: Clock = Clock.systemUTC(),
    private val maxObjects: Int = 200, private val maxBytes: Long = 5L * 1024 * 1024) {
    init { require(maxObjects in 1..200 && maxBytes in 1..5L * 1024 * 1024) }
    private data class Entry(val key: PlaceInfoKey, val value: CachedPublicPlace, val access: Long)
    private val lock = locks.computeIfAbsent(directory.canonicalPath) { Any() }
    private val file get() = File(directory, "places-v1.json")
    private val temporary get() = File(directory, "places-v1.tmp")

    suspend fun put(snapshot: PublicPlaceSnapshot): Boolean = io {
        validKey(snapshot.key); require(snapshot.version > 0 && snapshot.tags.size <= 1024)
        require(snapshot.tags.all { (k,v) -> k.isNotBlank() && k.codePointCount(0,k.length) <= 255 && v.codePointCount(0,v.length) <= 255 })
        val entries = load()
        val prior = entries[snapshot.key]?.value?.snapshot
        if (prior != null && (snapshot.version < prior.version || snapshot.version == prior.version && snapshot.fetchedAtMillis < prior.fetchedAtMillis)) return@io false
        val next = Entry(snapshot.key, CachedPublicPlace(copy(snapshot)), nextAccess(entries))
        if (encode(linkedMapOf(snapshot.key to next)).size > maxBytes) return@io false
        entries[snapshot.key] = next
        while (entries.size > maxObjects || encode(entries).size > maxBytes) {
            val victim = entries.values.filter { it.key != snapshot.key }.minByOrNull { it.access } ?: return@io false
            entries.remove(victim.key)
        }
        save(entries)
    }

    suspend fun cached(key: PlaceInfoKey): CachedPublicPlace? = io {
        validKey(key)
        val entries = load(); val found = entries[key] ?: return@io null
        entries[key] = found.copy(access = nextAccess(entries)); save(entries)
        found.value.copy(snapshot = found.value.snapshot?.let(::copy))
    }

    suspend fun unavailable(key: PlaceInfoKey, status: Int) = io {
        validKey(key); require(status == 404 || status == 410)
        val entries = load()
        entries[key] = Entry(key, CachedPublicPlace(entries[key]?.value?.snapshot, status), nextAccess(entries))
        while (entries.size > maxObjects || encode(entries).size > maxBytes) {
            val victim = entries.values.minByOrNull { it.access } ?: break
            entries.remove(victim.key)
        }
        save(entries); Unit
    }

    suspend fun invalidate(key: PlaceInfoKey) = io {
        validKey(key); val entries = load(); entries.remove(key); save(entries); Unit
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { synchronized(lock) { block() } }
    private fun nextAccess(entries: Map<PlaceInfoKey, Entry>) = (entries.values.maxOfOrNull { it.access } ?: 0L) + 1
    private fun copy(snapshot: PublicPlaceSnapshot) = snapshot.copy(tags = snapshot.tags.toMap())
    private fun validKey(key: PlaceInfoKey) {
        require(key.ref.id > 0)
        val uri = java.net.URI(key.endpoint)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    }

    private fun load(): LinkedHashMap<PlaceInfoKey, Entry> {
        val result = linkedMapOf<PlaceInfoKey, Entry>()
        if (!file.isFile || file.length() > maxBytes) return result
        try {
            val raw = file.readText(Charsets.UTF_8)
            var depth = 0; var quoted = false; var escaped = false
            raw.forEach { c ->
                if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
                else if (c == '"') quoted = true else if (c == '{' || c == '[') { depth++; require(depth <= 6) }
                else if (c == '}' || c == ']') { depth--; require(depth >= 0) }
            }
            val root = JSONObject(raw); require(root.getInt("format") == 1)
            val rows = root.getJSONArray("entries"); require(rows.length() <= maxObjects)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val key = PlaceInfoKey(row.getString("endpoint"), OsmRef(OsmType.valueOf(row.getString("type")), row.getLong("id")))
                validKey(key); require(key !in result)
                val status = if (row.has("unavailable")) row.getInt("unavailable").also { require(it == 404 || it == 410) } else null
                val snapshot = if (row.has("version")) {
                    val tagsJson = row.getJSONObject("tags"); require(tagsJson.length() <= 1024)
                    val tags = linkedMapOf<String, String>()
                    tagsJson.keys().forEach { k ->
                        val v = tagsJson.get(k); require(v is String && k.isNotBlank() && k.codePointCount(0,k.length) <= 255 && v.codePointCount(0,v.length) <= 255)
                        tags[k] = v
                    }
                    PublicPlaceSnapshot(key, row.getLong("version").also { require(it > 0) }, tags, row.getLong("fetchedAt"))
                } else null
                require(snapshot != null || status != null)
                result[key] = Entry(key, CachedPublicPlace(snapshot, status), row.getLong("access"))
            }
        } catch (_: Exception) { result.clear() }
        return result
    }

    private fun encode(entries: Map<PlaceInfoKey, Entry>): ByteArray {
        val rows = JSONArray()
        entries.values.forEach { entry ->
            val row = JSONObject().put("endpoint", entry.key.endpoint).put("type", entry.key.ref.type.name)
                .put("id", entry.key.ref.id).put("access", entry.access)
            entry.value.unavailableStatus?.let { row.put("unavailable", it) }
            entry.value.snapshot?.let { row.put("version", it.version).put("fetchedAt", it.fetchedAtMillis).put("tags", JSONObject(it.tags)) }
            rows.put(row)
        }
        return JSONObject().put("format", 1).put("entries", rows).toString().toByteArray(Charsets.UTF_8)
    }

    private fun save(entries: Map<PlaceInfoKey, Entry>): Boolean {
        val bytes = encode(entries); if (bytes.size > maxBytes) return false
        return try {
            check(directory.isDirectory || directory.mkdirs())
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) { false } finally { temporary.delete() }
    }

    private companion object { val locks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
}
