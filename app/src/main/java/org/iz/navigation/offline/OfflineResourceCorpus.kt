package org.iz.navigation.offline

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

data class OfflineFetchedResource(val bytes: ByteArray, val fetchedAt: Long,
    val etag: String? = null, val lastModified: String? = null,
    val cacheControl: String? = null, val expires: String? = null)

/** One acquisition window; missing/corrupt resume journals never reset the transfer counter. */
class OfflineResourceCorpus(private val directory: File, private val identity: String,
    initialBytes: Long, private val checkCurrent: () -> Unit) {
    private val records = linkedMapOf<String, JSONObject>()
    private val allowed = ConcurrentHashMap.newKeySet<String>().apply {
        add(OPEN_FREE_MAP_STYLE); add("https://tiles.openfreemap.org/planet")
    }
    init {
        OfflineProfileNotices.digest(identity)
        require(!Files.isSymbolicLink(directory.toPath()))
        val journal = File(directory, "acquisition.json")
        if (journal.exists()) {
            val wrapper = JSONObject(readBounded(journal, 4_000_000).toString(Charsets.UTF_8))
            val payload = wrapper.getString("payload")
            require(OfflineStyleAudit.sha256(payload.toByteArray(Charsets.UTF_8)) == wrapper.getString("sha256"))
            val parsed = JSONObject(payload)
            require(parsed.getString("schema") == "iz.offline.acquisition.v1" && parsed.getString("identity") == identity)
            val rows = parsed.getJSONArray("resources"); require(rows.length() <= MAX_OFFLINE_TILES + 2)
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val url = row.getString("url")
                OfflineStyleAudit.resourceUrl(url, emptySet())
                require(!records.containsKey(url) && row.getString("role") in setOf("style", "source", "vector") &&
                    row.getLong("bytes") in 0..512_000 && row.getLong("fetchedAt") > 0)
                val sha = OfflineProfileNotices.digest(row.getString("sha256"))
                val bytes = readBounded(File(directory, sha + ".bin"), 512_000)
                require(bytes.size.toLong() == row.getLong("bytes") && OfflineStyleAudit.sha256(bytes) == sha)
                require(when (row.getString("role")) {
                    "style" -> url == OPEN_FREE_MAP_STYLE
                    "source" -> url == "https://tiles.openfreemap.org/planet"
                    else -> url.startsWith("https://tiles.openfreemap.org/planet/") && url.endsWith(".pbf")
                })
                records[url] = row
            }
            require(initialBytes >= records.values.sumOf { it.getLong("bytes") } && initialBytes <= MAX_OFFLINE_BYTES)
        } else {
            check(initialBytes == 0L) { "Missing retained acquisition journal" }
            check(directory.isDirectory || directory.mkdirs())
            save()
        }
    }
    fun admit(url: String) { require(url in allowed) { "Resource is outside this acquisition" }; checkCurrent() }
    fun allowTiles(urls: Set<String>) {
        require(urls.size <= MAX_OFFLINE_TILES)
        urls.forEach {
            OfflineStyleAudit.resourceUrl(it, emptySet())
            require(it.startsWith("https://tiles.openfreemap.org/planet/") && it.endsWith(".pbf"))
        }
        allowed += urls
    }
    fun obtain(url: String, role: String, fetch: (String) -> OfflineFetchedResource): ByteArray {
        admit(url); require(role in setOf("style", "source", "vector"))
        records[url]?.let { row ->
            require(row.getString("role") == role)
            val bytes = readBounded(File(directory, row.getString("sha256") + ".bin"), 512_000)
            require(OfflineStyleAudit.sha256(bytes) == row.getString("sha256"))
            checkCurrent(); return bytes
        }
        val response = fetch(url)
        checkCurrent()
        require(response.bytes.size <= 512_000 && (role == "vector" || response.bytes.isNotEmpty()) && response.fetchedAt > 0)
        val sha = OfflineStyleAudit.sha256(response.bytes)
        writeOwned(directory, sha + ".bin", response.bytes, 512_000)
        val row = JSONObject().put("url", url).put("role", role).put("bytes", response.bytes.size)
            .put("sha256", sha).put("fetchedAt", response.fetchedAt)
            .put("etag", response.etag?.take(256) ?: JSONObject.NULL)
            .put("lastModified", response.lastModified?.take(256) ?: JSONObject.NULL)
            .put("cacheControl", response.cacheControl?.take(256) ?: JSONObject.NULL)
            .put("expires", response.expires?.take(256) ?: JSONObject.NULL)
            .put("contentHashEncoding", "decoded-response-bytes")
        records[url] = row
        try { save() } catch (error: Exception) { records.remove(url); throw error }
        return response.bytes
    }
    fun seal(expectedTiles: Set<String>): Pair<String, Map<String, OfflineLocalFileResource>> {
        checkCurrent()
        require(expectedTiles.isNotEmpty() && expectedTiles.size <= MAX_OFFLINE_TILES)
        require(records.keys == expectedTiles + setOf(OPEN_FREE_MAP_STYLE, "https://tiles.openfreemap.org/planet"))
        val mapping = expectedTiles.associate { url ->
            val coordinate = url.substringAfterLast("/planet/").substringAfter('/')
            val row = requireNotNull(records[url])
            "tiles/" + coordinate to OfflineLocalFileResource(OfflineLocalRole.VECTOR,
                File(directory, row.getString("sha256") + ".bin"), row.getInt("bytes"), row.getString("sha256"))
        }
        val payload = payload()
        val sha = OfflineStyleAudit.sha256(payload.toByteArray(Charsets.UTF_8))
        writeOwned(directory, sha + ".acquisition.json", payload.toByteArray(Charsets.UTF_8), 4_000_000)
        return sha to mapping
    }
    private fun payload(): String = JSONObject().put("schema", "iz.offline.acquisition.v1")
        .put("identity", identity).put("resources", JSONArray(records.toSortedMap().values.toList())).toString()
    private fun save() {
        val payload = payload()
        val bytes = JSONObject().put("payload", payload)
            .put("sha256", OfflineStyleAudit.sha256(payload.toByteArray(Charsets.UTF_8))).toString().toByteArray(Charsets.UTF_8)
        writeOwned(directory, "acquisition.json", bytes, 4_000_000, replace = true)
    }
    companion object {
        internal fun readBounded(file: File, limit: Int): ByteArray {
            require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() in 0..limit.toLong())
            return file.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    require(out.size() + count <= limit); out.write(buffer, 0, count) }
                out.toByteArray()
            }
        }
        internal fun writeOwned(root: File, name: String, bytes: ByteArray, limit: Int, replace: Boolean = false): File {
            require(bytes.size <= limit && name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\'))
            require(!Files.isSymbolicLink(root.toPath())); check(root.isDirectory || root.mkdirs())
            val file = File(root, name); val temp = File(root, name + ".tmp")
            require(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(temp.toPath()))
            if (file.exists() && !replace) {
                require(readBounded(file, limit).contentEquals(bytes)); return file
            }
            try {
                FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp.toPath()) }
            return file
        }
    }
}
