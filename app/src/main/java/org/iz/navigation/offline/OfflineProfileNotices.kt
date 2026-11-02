package org.iz.navigation.offline

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import org.json.JSONObject

/** Actual selected-profile source text only. All reads are bounded, local, hash-checked and read-only. */
object OfflineProfileNotices {
    const val PROFILE_ID = "iz-offline-labelled-latin-v1"
    const val METADATA_KEY = "iz:offline:profile"
    fun read(directory: File, styleJson: String, expectedStyleSha256: String): String? = runCatching {
        val bytes = styleJson.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..512_000 && OfflineStyleAudit.sha256(bytes) == expectedStyleSha256.lowercase())
        val profile = JSONObject(styleJson).getJSONObject("metadata").getJSONObject(METADATA_KEY)
        require(profile.getString("id") == PROFILE_ID)
        val font = digest(profile.getString("fontBundleSha256"))
        val notice = digest(profile.getString("noticesSha256"))
        val manifest = JSONObject(text(readFile(directory, "notices/" + notice + ".json", 128_000, notice)))
        require(manifest.getString("schema") == "iz.offline.notices.v1" &&
            manifest.getString("profileId") == PROFILE_ID && manifest.getString("fontBundleSha256") == font)
        val files = manifest.getJSONArray("files")
        require(files.length() in 1..16)
        var total = 0L
        val seen = mutableSetOf<String>()
        val parts = (0 until files.length()).map { index ->
            val item = files.getJSONObject(index)
            val sha = digest(item.getString("sha256"))
            val path = item.getString("path")
            require(path == "notices/" + sha + ".txt" && seen.add(path))
            val size = item.getLong("bytes")
            require(size in 1..128_000)
            total += size; require(total <= 512_000)
            val data = readFile(directory, path, 128_000, sha)
            require(data.size.toLong() == size)
            text(data)
        }
        parts.joinToString("\n\n")
    }.getOrNull()

    internal fun digest(value: String) = value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    internal fun readFile(directory: File, relative: String, limit: Int, sha256: String): ByteArray {
        require(relative.isNotBlank() && !relative.contains('\\') && !relative.startsWith('/') &&
            relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
        val root = directory.canonicalFile
        require(!Files.isSymbolicLink(directory.toPath()))
        var cursor = root
        for (part in relative.split('/')) {
            cursor = File(cursor, part)
            require(!Files.isSymbolicLink(cursor.toPath()))
        }
        val file = cursor.canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file != root &&
            file.isFile && file.length() in 1..limit.toLong())
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(OfflineStyleAudit.sha256(bytes) == sha256)
        return bytes
    }
    private fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
