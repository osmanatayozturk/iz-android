package org.iz.navigation.lean

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Length-framed raw entries, bounded independently of attacker-controlled ZIP size headers. */
internal object LeanArchive {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun rawName(id: String): String {
        require(uuid.matches(id) && UUID.fromString(id).toString() == id) { "Invalid lean session UUID" }
        return "lean/$id.raw"
    }
    fun isRawName(name: String): Boolean = name.length == 45 && name.startsWith("lean/") &&
        name.endsWith(".raw") && runCatching { rawName(name.substring(5, 41)) == name }.getOrDefault(false)

    fun framedBytes(sessions: List<LeanBackupSession>): Long =
        sessions.sumOf { session -> session.chunks.sumOf { it.byteCount.toLong() + 4 } }

    fun validateEntryNames(names: Set<String>, photos: Set<String>, sessions: List<LeanBackupSession>) {
        val raw = sessions.filter { it.chunks.isNotEmpty() }.map { rawName(it.input.token.id) }.toSet()
        require(names == photos + raw + "manifest.json") { "Missing or unexpected backup entries" }
    }

    /** Store loads one immutable BLOB at a time while holding its snapshot lease. */
    fun writeRaw(session: LeanBackupSession, output: OutputStream, load: (LeanChunkManifest) -> ByteArray) {
        val framed = DataOutputStream(output)
        session.chunks.forEach { part ->
            val bytes = load(part)
            validateChunk(session, part, bytes)
            framed.writeInt(bytes.size)
            framed.write(bytes)
        }
    }

    /** Reserve old DB plus all staged payload bytes BEFORE any payload allocation/write. */
    suspend fun stage(input: InputStream, directory: File, reserve: suspend (Int) -> Unit): List<File> {
        val framed = DataInputStream(input)
        val files = mutableListOf<File>()
        while (true) {
            val first = framed.read()
            if (first < 0) break
            val rest = ByteArray(3)
            framed.readFully(rest)
            val length = (first shl 24) or ((rest[0].toInt() and 255) shl 16) or
                ((rest[1].toInt() and 255) shl 8) or (rest[2].toInt() and 255)
            require(length in 1..LeanCodec.MAX_CHUNK_BYTES && files.size < LeanStore.MAX_CHUNKS)
            reserve(length)
            val bytes = ByteArray(length)
            framed.readFully(bytes)
            // No component of an archive path reaches the filesystem.
            val file = File.createTempFile("lean-", ".part", directory)
            try { file.outputStream().use { it.write(bytes) }; files += file }
            catch (failure: Throwable) { file.delete(); throw failure }
        }
        require(files.isNotEmpty()) { "Empty lean raw entry" }
        return files
    }

    fun validate(session: LeanBackupSession, files: List<File>) {
        require(files.size == session.chunks.size) { "Lean chunk count mismatch" }
        session.chunks.zip(files).forEach { (part, file) ->
            require(file.isFile && file.length() == part.byteCount.toLong() &&
                file.length() in 1..LeanCodec.MAX_CHUNK_BYTES.toLong())
            validateChunk(session, part, file.readBytes())
        }
    }

    internal fun validateChunk(session: LeanBackupSession, part: LeanChunkManifest, bytes: ByteArray) {
        require(bytes.size == part.byteCount && LeanCodec.sha256(bytes) == part.sha256) { "Lean chunk checksum mismatch" }
        val records = LeanCodec.decodeRecords(bytes).toList() // Consume lazy decoding to validate every record.
        require(records.size == part.recordCount &&
            records.minOf { it.elapsedNanos } == part.firstElapsedNanos &&
            records.maxOf { it.elapsedNanos } == part.lastElapsedNanos) { "Lean chunk metadata mismatch" }
        require(records.all { it.elapsedNanos >= session.input.startedAtElapsedNanos &&
            it.elapsedNanos <= requireNotNull(session.input.endedAtElapsedNanos) })
    }
}