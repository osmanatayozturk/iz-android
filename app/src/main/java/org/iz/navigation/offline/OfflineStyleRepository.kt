package org.iz.navigation.offline

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Only app-owned, bounded, atomic metadata/style files. No provider client or restored command. */
class OfflineStyleRepository(directory: File) {
    val directory: File = directory.canonicalFile
    private fun key(pack: OfflinePack) = OfflineRegionIdentity.key(pack, "0".repeat(64))
    @Synchronized fun save(pack: OfflinePack, style: AuditedOfflineStyle): File {
        val bytes = style.json.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..512_000 && OfflineStyleAudit.sha256(bytes) == style.closure.styleSha256)
        val output = File(directory, style.closure.styleSha256 + ".json")
        if (output.exists()) require(read(output, 512_000).contentEquals(bytes)) else atomic(output, bytes)
        atomic(File(directory, key(pack) + ".style"), style.closure.styleSha256.toByteArray(Charsets.UTF_8))
        return output
    }
    @Synchronized fun load(pack: OfflinePack): Pair<File, AuditedOfflineStyle>? = runCatching {
        val pointer = File(directory, key(pack) + ".style")
        if (!pointer.isFile) return null
        val digest = read(pointer, 64).toString(Charsets.UTF_8)
        require(digest.matches(Regex("[0-9a-f]{64}")))
        val styleFile = File(directory, digest + ".json")
        val bytes = read(styleFile, 512_000)
        require(OfflineStyleAudit.sha256(bytes) == digest)
        val parsed = JSONObject(OfflineJsonDepth.requireSafe(bytes.toString(Charsets.UTF_8)))
        val audit = if (parsed.optJSONObject("metadata")?.has(OfflineProfileNotices.METADATA_KEY) == true)
            OfflineLabelledProfile.parse(bytes)
        else OfflineStyleAudit.resolve(bytes) { error("Pinned style must not require another metadata request") }
        styleFile to AuditedOfflineStyle(bytes.toString(Charsets.UTF_8), audit.closure.copy(styleSha256 = digest))
    }.getOrNull()
    /** Local verified profile only. Missing/retired/tampered notices are unavailable, never generic. */
    @Synchronized fun noticeText(pack: OfflinePack): String? {
        val expected = pack.closure?.styleSha256 ?: return null
        val pinned = load(pack) ?: return null
        if (pinned.second.closure.styleSha256 != expected) return null
        return OfflineProfileNotices.read(directory, pinned.second.json, expected)
    }
    @Synchronized fun tileKeys(pack: OfflinePack): Set<String> {
        val file = File(directory, key(pack) + ".tiles")
        if (!file.exists()) {
            check(pack.downloadedBytes == 0L && pack.regionId == null) { "Missing retained transfer journal" }
            return emptySet()
        }
        val value = JSONObject(OfflineJsonDepth.requireSafe(read(file, 1_000_000).toString(Charsets.UTF_8)))
        val payload = value.getString("payload")
        require(OfflineStyleAudit.sha256(payload.toByteArray(Charsets.UTF_8)) == value.getString("sha256"))
        val keys = JSONArray(OfflineJsonDepth.requireSafe(payload))
        require(keys.length() <= MAX_OFFLINE_TILES)
        val result = (0 until keys.length()).map { keys.getString(it).also { key ->
            OfflineStyleAudit.resourceUrl(key, emptySet())
        } }.toSet()
        require(result.size == keys.length())
        return result
    }
    @Synchronized fun saveTileKeys(pack: OfflinePack, keys: Set<String>): Boolean = runCatching {
        require(keys.size <= MAX_OFFLINE_TILES && keys.all { it.length <= 2000 })
        keys.forEach { OfflineStyleAudit.resourceUrl(it, emptySet()) }
        val payload = JSONArray(keys.sorted()).toString()
        atomic(File(directory, key(pack) + ".tiles"), JSONObject().put("payload", payload)
            .put("sha256", OfflineStyleAudit.sha256(payload.toByteArray(Charsets.UTF_8))).toString().toByteArray(Charsets.UTF_8))
        true
    }.getOrDefault(false)
    @Synchronized fun remove(pack: OfflinePack) {
        val id = key(pack)
        for (suffix in listOf(".style", ".tiles")) {
            val file = File(directory, id + suffix)
            require(!Files.isSymbolicLink(file.toPath()))
            Files.deleteIfExists(file.toPath())
        }
        val parent = File(directory, "corpus")
        val corpus = File(parent, id)
        require(!Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(corpus.toPath()) &&
            corpus.canonicalFile.parentFile == parent.canonicalFile)
        if (corpus.exists()) {
            val paths = Files.walk(corpus.toPath()).use { it.iterator().asSequence().toList() }
            require(paths.size <= 7000 && paths.all {
                !Files.isSymbolicLink(it) && it.toFile().canonicalFile.toPath().startsWith(corpus.canonicalFile.toPath())
            })
            paths.sortedByDescending { it.nameCount }.forEach { Files.deleteIfExists(it) }
        }
        // Styles may be shared with a still-valid previous package; no global cache purge.
    }
    private fun read(file: File, limit: Int): ByteArray {
        require(file.canonicalFile.parentFile == directory && !Files.isSymbolicLink(file.toPath()) &&
            file.isFile && file.length() in 1..limit.toLong())
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                require(out.size() + count <= limit)
                out.write(bytes, 0, count)
            }
            out.toByteArray()
        }
    }
    private fun atomic(file: File, bytes: ByteArray) {
        require(file.canonicalFile.parentFile == directory && bytes.size <= 1_000_000)
        check(directory.isDirectory || directory.mkdirs())
        // Bound retained style history; fail rather than evict a still-valid package implicitly.
        check((directory.listFiles()?.size ?: 0) < 128 || file.exists())
        val temporary = File(directory, file.name + ".tmp")
        require(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(temporary.toPath()))
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
}
