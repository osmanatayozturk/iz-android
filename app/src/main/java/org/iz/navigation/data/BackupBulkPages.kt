package org.iz.navigation.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.iz.navigation.offline.OfflineJsonDepth
import org.json.JSONArray
import org.json.JSONObject

internal data class BackupBulkPart(val type: String, val sequence: Int, val path: String,
    val records: Int, val bytes: Long, val sha256: String)
internal data class BackupBulkGroup(val type: String, val records: Int, val parts: List<BackupBulkPart>)

/** Shared by every metadata table in one export; the validated directory applies the same guard on read. */
internal class BackupBulkBudget {
    private var metadataRows = 0L
    private var metadataBytes = 0L
    fun reserve(type: String, records: Int, bytes: Long) {
        require(type in BackupBulkPages.TYPES && records >= 0 && bytes >= 0)
        if (type in BackupBulkPages.STREAMED_TYPES) return
        if (records.toLong() > BackupBulkPages.MAX_METADATA_RECORDS - metadataRows ||
            bytes > BackupBulkPages.MAX_METADATA_BYTES - metadataBytes)
            throw BackupLimitException("Yedek metadata 64 MiB / 200.000 kayıt sınırını aşan tablo: $type.")
        metadataRows += records
        metadataBytes += bytes
    }
}

/** v14 bulk directory. Every ZIP member is one bounded JSON array; streams remain owned by the caller. */
internal object BackupBulkPages {
    const val VERSION = 14
    const val LEGACY_VERSION = 13
    const val MAX_PAGE_BYTES = 1024 * 1024
    const val MAX_INDEX_BYTES = 4 * 1024 * 1024
    const val MAX_PAGE_RECORDS = 512
    const val MAX_RECORDS = 500_000
    const val MAX_METADATA_BYTES = 64L * 1024 * 1024
    const val MAX_METADATA_RECORDS = 200_000L
    val STREAMED_TYPES: Set<String> = setOf("points", "healthSamples", "watchHealthSamples", "trackSegments", "trackCoordinates")
    val TYPES: List<String> = listOf("journeys", "points", "places", "visits", "photos", "drafts", "contributions",
        "healthSamples", "garageVehicles", "fuelFills", "journeyVehicles", "mapEdits", "osmPresetFavorites",
        "watchHealthSessions", "watchHealthSamples", "savedPlans", "collections", "memberships", "destinationShortcuts", "leanSessions",
        "importedTracks", "trackSegments", "trackCoordinates")

    fun pageName(type: String, sequence: Int): String {
        require(type in TYPES && sequence in 0 until BACKUP_MAX_FILES - 1) { "Geçersiz yedek sayfası." }
        return "bulk/$type/${sequence.toString().padStart(6, '0')}.json"
    }
    fun isPageName(name: String): Boolean = TYPES.any { type ->
        val prefix = "bulk/$type/"
        if (!name.startsWith(prefix) || !name.endsWith(".json")) false else {
            val number = name.removePrefix(prefix).removeSuffix(".json")
            number.length == 6 && number.all { it in '0'..'9' } &&
                number.toInt() in 0 until BACKUP_MAX_FILES - 1
        }
    }

    suspend fun write(zip: ZipOutputStream, type: String, register: (String, Long) -> Unit,
        budget: BackupBulkBudget = BackupBulkBudget(),
        collect: suspend (suspend (JSONObject) -> Unit) -> Unit): BackupBulkGroup {
        require(type in TYPES)
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        val parts = mutableListOf<BackupBulkPart>()
        val buffer = ByteArrayOutputStream()
        val record = RecordBuffer(type, check)
        val writer = OutputStreamWriter(record, Charsets.UTF_8).buffered(1024)
        buffer.write('['.code)
        var records = 0
        var total = 0
        fun flushPage() {
            if (records == 0) return
            check()
            buffer.write(']'.code)
            val bytes = buffer.toByteArray()
            if (parts.size >= BACKUP_MAX_FILES - 1) throw BackupLimitException("Yedekte çok fazla sayfa var.")
            val name = pageName(type, parts.size)
            val part = BackupBulkPart(type, parts.size, name, records, bytes.size.toLong(), sha256(bytes))
            budget.reserve(type, part.records, part.bytes)
            // The exporter's shared file/expanded-byte budget is reserved before emitting any ZIP data.
            register(name, part.bytes)
            zip.putNextEntry(ZipEntry(name))
            var offset = 0
            while (offset < bytes.size) {
                check()
                val count = minOf(8192, bytes.size - offset)
                zip.write(bytes, offset, count)
                offset += count
            }
            zip.closeEntry()
            check()
            parts += part
            buffer.reset()
            buffer.write('['.code)
            records = 0
        }
        collect { row ->
            check()
            if (total >= MAX_RECORDS) throw BackupLimitException("Yedekte 500.000 kayıt sınırını aşan tablo: $type.")
            record.reset()
            BackupJsonStream.value(writer, row)
            writer.flush()
            if (records == MAX_PAGE_RECORDS || buffer.size().toLong() + (if (records == 0) 0 else 1) + record.size() + 1 > MAX_PAGE_BYTES)
                flushPage()
            if (records != 0) buffer.write(','.code)
            record.writeTo(buffer)
            records++
            total++
            check()
        }
        flushPage()
        return BackupBulkGroup(type, total, parts.toList())
    }

    fun encode(groups: List<BackupBulkGroup>): JSONArray {
        validate(groups)
        return JSONArray().apply {
            TYPES.forEach { type ->
                val group = groups.single { it.type == type }
                put(JSONObject().put("type", type).put("records", group.records).put("partCount", group.parts.size)
                    .put("parts", JSONArray().apply {
                        group.parts.forEach { part -> put(JSONObject().put("sequence", part.sequence).put("path", part.path)
                            .put("records", part.records).put("bytes", part.bytes).put("sha256", part.sha256)) }
                    }))
            }
        }
    }

    /** Requires every v14 table directory and empty inline arrays; never accepts a partial index. */
    fun parse(manifest: JSONObject): List<BackupBulkGroup> {
        require(integer(manifest, "version") == VERSION.toLong()) { "Geçersiz sayfalı yedek sürümü." }
        TYPES.forEach { require(manifest.getJSONArray(it).length() == 0) { "Yedek dizisi iki kez tanımlanmış." } }
        val directory = manifest.getJSONArray("bulkPages")
        require(directory.length() == TYPES.size) { "Yedek sayfa dizini eksik." }
        val groups = List(directory.length()) { index ->
            val row = directory.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("type", "records", "partCount", "parts"))
            val type = row.get("type") as String
            val pages = row.getJSONArray("parts")
            require(integer(row, "partCount") == pages.length().toLong()) { "Yedek parça sayısı tutarsız." }
            if (pages.length() >= BACKUP_MAX_FILES) throw BackupLimitException("Yedekte çok fazla sayfa var.")
            val parts = List(pages.length()) { position ->
                val part = pages.getJSONObject(position)
                require(part.keys().asSequence().toSet() == setOf("sequence", "path", "records", "bytes", "sha256"))
                BackupBulkPart(type, int(part, "sequence"), part.get("path") as String,
                    int(part, "records"), integer(part, "bytes"), part.get("sha256") as String)
            }
            BackupBulkGroup(type, int(row, "records"), parts)
        }
        validate(groups)
        return TYPES.map { type -> groups.single { it.type == type } }
    }
    fun validatedParts(manifest: JSONObject): List<BackupBulkPart> = parse(manifest).flatMap { it.parts }

    /** Consumes one entry through EOF/CRC, verifies its bytes/hash/count, then exposes at most 512 rows. */
    fun readPage(input: InputStream, part: BackupBulkPart, check: () -> Unit = {}): List<JSONObject> {
        validatePart(part)
        val output = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            check()
            val size = input.read(buffer)
            if (size < 0) break
            if (size == 0) continue
            require(size.toLong() <= part.bytes - count) { "Yedek sayfası boyutu eşleşmedi." }
            output.write(buffer, 0, size)
            digest.update(buffer, 0, size)
            count += size
        }
        check()
        require(count == part.bytes && hex(digest.digest()) == part.sha256) { "Yedek sayfası eksik veya değişmiş." }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
        val values = JSONArray(OfflineJsonDepth.requireSafe(text))
        require(values.length() == part.records) { "Yedek sayfası kayıt sayısı eşleşmedi." }
        return List(values.length()) { index -> check(); values.getJSONObject(index) }
    }

    private fun validate(groups: List<BackupBulkGroup>) {
        require(groups.size == TYPES.size && groups.map { it.type }.toSet() == TYPES.toSet()) { "Yedek sayfa türleri eksik veya yinelenmiş." }
        var fileCount = 1 // manifest.json
        val budget = BackupBulkBudget()
        groups.forEach { group ->
            require(group.records in 0..MAX_RECORDS)
            var rows = 0L
            group.parts.forEachIndexed { index, part ->
                require(part.type == group.type && part.sequence == index) { "Yedek sayfa sırası tutarsız." }
                validatePart(part)
                budget.reserve(group.type, part.records, part.bytes)
                rows += part.records
                fileCount++
                if (fileCount > BACKUP_MAX_FILES) throw BackupLimitException("Yedekte çok fazla dosya var.")
            }
            require(rows == group.records.toLong()) { "Yedek dizini kayıt sayısı tutarsız." }
        }
    }
    private fun validatePart(part: BackupBulkPart) {
        require(part.path == pageName(part.type, part.sequence) && part.records in 1..MAX_PAGE_RECORDS &&
            part.bytes in 2..MAX_PAGE_BYTES.toLong() && part.sha256.matches(Regex("[0-9a-f]{64}"))) { "Geçersiz yedek sayfası." }
    }
    private class RecordBuffer(private val type: String, private val check: () -> Unit) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            check()
            if (size() >= MAX_PAGE_BYTES - 2) throw limit()
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check()
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length > MAX_PAGE_BYTES - 2 - size()) throw limit()
            super.write(bytes, offset, length)
        }
        private fun limit() = BackupLimitException("Yedek tek kayıt 1 MiB sınırını aşan tablo: $type.")
    }
    private fun integer(row: JSONObject, key: String): Long = when (val value = row.get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IOException("Geçersiz yedek dizini sayısı.")
    }
    private fun int(row: JSONObject, key: String): Int = integer(row, key).also {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE)
    }.toInt()
    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
