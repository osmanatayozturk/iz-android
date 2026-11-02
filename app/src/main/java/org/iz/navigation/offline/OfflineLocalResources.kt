package org.iz.navigation.offline

import java.io.IOException
import java.io.File
import java.nio.file.Files
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer

enum class OfflineLocalRole { STYLE, GLYPH, VECTOR }
data class OfflineLocalResource(val role: OfflineLocalRole, val bytes: ByteArray, val sha256: String)
data class OfflineLocalFileResource(val role: OfflineLocalRole, val file: File, val bytes: Int, val sha256: String)

/** In-memory pinned local responses for both Native13.6.1 resource paths. Owns no network client. */
class OfflineLocalResources : Call.Factory {
    private class Payload(val role: OfflineLocalRole, val size: Int, val sha256: String, val read: () -> ByteArray)
    private class Owner(val identity: String, val generation: Long,
        val resources: Map<String, Payload>, val current: () -> Boolean) {
        var closed = false
    }
    private var owner: Owner? = null

    fun install(identity: String, generation: Long, resources: Map<String, OfflineLocalResource>,
        isCurrent: () -> Boolean): AutoCloseable {
        val payloads = resources.mapValues { (path, entry) ->
            require(validPath(path, entry.role))
            val bytes = entry.bytes.copyOf()
            require(bytes.size in (if (entry.role == OfflineLocalRole.VECTOR) 0 else 1)..512_000)
            require(OfflineStyleAudit.sha256(bytes) == entry.sha256.lowercase())
            Payload(entry.role, bytes.size, entry.sha256.lowercase()) { bytes }
        }
        return installPayloads(identity, generation, payloads, isCurrent)
    }
    fun installFiles(identity: String, generation: Long, directory: File,
        resources: Map<String, OfflineLocalFileResource>, isCurrent: () -> Boolean): AutoCloseable {
        val root = directory.canonicalFile
        require(!Files.isSymbolicLink(directory.toPath()))
        val payloads = resources.mapValues { (path, entry) ->
            require(validPath(path, entry.role))
            val file = entry.file.canonicalFile
            require(file.toPath().startsWith(root.toPath()) && file != root &&
                !Files.isSymbolicLink(entry.file.toPath()))
            var ancestor = entry.file.parentFile
            while (ancestor != null && ancestor.canonicalFile != root) {
                require(!Files.isSymbolicLink(ancestor.toPath()))
                ancestor = ancestor.parentFile
            }
            require(ancestor != null)
            val sha = OfflineProfileNotices.digest(entry.sha256.lowercase())
            require(entry.bytes in (if (entry.role == OfflineLocalRole.VECTOR) 0 else 1)..512_000)
            fun read(): ByteArray {
                require(!Files.isSymbolicLink(entry.file.toPath()) && entry.file.canonicalFile == file)
                val data = OfflineResourceCorpus.readBounded(file, 512_000)
                require(data.size == entry.bytes && OfflineStyleAudit.sha256(data) == sha)
                return data
            }
            read() // A malformed corpus cannot become active.
            Payload(entry.role, entry.bytes, sha, ::read)
        }
        return installPayloads(identity, generation, payloads, isCurrent)
    }
    @Synchronized private fun installPayloads(identity: String, generation: Long, resources: Map<String, Payload>,
        isCurrent: () -> Boolean): AutoCloseable {
        require(identity.matches(Regex("[a-f0-9]{64}")) && generation >= 0)
        require(resources.size in 1..3257 && resources.containsKey("style.json") &&
            resources.values.sumOf { it.size.toLong() } <= 60_000_000)
        check(runCatching(isCurrent).getOrDefault(false)) { "Retired local corpus" }
        owner?.let { if (!admitted(it)) { it.closed = true; owner = null } }
        check(owner == null) { "Another local corpus is installed" }
        val installed = Owner(identity, generation, resources.toMap(), isCurrent)
        owner = installed
        return AutoCloseable {
            synchronized(this) {
                installed.closed = true
                if (owner === installed) owner = null
            }
        }
    }
    @Synchronized override fun newCall(request: Request): Call = LocalCall(request, owner)
    private fun admitted(value: Owner?): Boolean = synchronized(this) {
        value != null && owner === value && !value.closed && runCatching(value.current).getOrDefault(false)
    }

    private inner class LocalCall(private val original: Request, private val binding: Owner?) : Call {
        @Volatile private var cancelled = false
        @Volatile private var executed = false
        override fun request() = original
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = LocalCall(original, binding)
        override fun cancel() { cancelled = true }
        @Synchronized private fun start() { check(!executed) { "Already Executed" }; executed = true }
        private fun checkCurrent() {
            if (cancelled || !admitted(binding)) throw IOException("Yerel harita kaynağı artık kullanılamıyor.")
        }
        private fun response(): Response {
            checkCurrent()
            val url = original.url
            val prefix = "/" + requireNotNull(binding).identity + "/"
            if (original.method != "GET" || url.scheme != "https" || url.host != HOST || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
                !url.encodedPath.startsWith(prefix) || original.header("Range") != null)
                throw IOException("Yerel harita kaynağı doğrulanamadı.")
            val path = url.encodedPath.removePrefix(prefix)
            val resource = binding.resources[path] ?: throw IOException("Yerel harita kaynağı bulunamadı.")
            checkCurrent()
            val bytes = try { resource.read() } catch (_: Exception) { throw IOException("Yerel harita kaynağı değişti veya okunamadı.") }
            checkCurrent()
            val source = object : ForwardingSource(Buffer().write(bytes)) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    checkCurrent()
                    return super.read(sink, byteCount)
                }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = (if (resource.role == OfflineLocalRole.STYLE)
                    "application/json; charset=utf-8" else "application/x-protobuf").toMediaType()
                override fun contentLength() = resource.size.toLong()
                override fun source() = source
            }
            return Response.Builder().request(original).protocol(Protocol.HTTP_1_1).code(200).message("Local resource")
                .header("Cache-Control", "private, max-age=31536000, immutable")
                .header("ETag", "\"" + resource.sha256 + "\"").header("X-Iz-Local-SHA256", resource.sha256)
                .body(body).build()
        }
        override fun execute(): Response { start(); return response() }
        override fun enqueue(responseCallback: Callback) {
            start()
            val result = try { response() } catch (error: IOException) {
                responseCallback.onFailure(this, error); return
            }
            // Exactly one terminal callback. A later revocation/cancel rejects subsequent body reads.
            responseCallback.onResponse(this, result)
        }
    }
    companion object {
        const val HOST = "iz-offline.invalid"
        const val ORIGIN = "https://iz-offline.invalid"
        fun styleUrl(identity: String): String {
            require(identity.matches(Regex("[a-f0-9]{64}")))
            return ORIGIN + "/" + identity + "/style.json"
        }
        private fun validPath(path: String, role: OfflineLocalRole): Boolean {
            if (role == OfflineLocalRole.STYLE) return path == "style.json"
            if (role == OfflineLocalRole.VECTOR) {
                val tile = Regex("tiles/([0-9]+)/([0-9]+)/([0-9]+)\\.pbf").matchEntire(path) ?: return false
                val z = tile.groupValues[1].toIntOrNull() ?: return false
                val x = tile.groupValues[2].toIntOrNull() ?: return false
                val y = tile.groupValues[3].toIntOrNull() ?: return false
                return z in 0..16 && x in 0 until (1 shl z) && y in 0 until (1 shl z) &&
                    z.toString() == tile.groupValues[1] && x.toString() == tile.groupValues[2] && y.toString() == tile.groupValues[3]
            }
            val match = Regex("glyphs/((?:[A-Za-z0-9_-]|%20){1,160})/([0-9]+)-([0-9]+)\\.pbf").matchEntire(path)
                ?: return false
            val start = match.groupValues[2].toIntOrNull() ?: return false
            val end = match.groupValues[3].toIntOrNull() ?: return false
            return start in 0..65280 && start % 256 == 0 && end == start + 255 &&
                match.groupValues[2] == start.toString() && match.groupValues[3] == end.toString()
        }
    }
}
