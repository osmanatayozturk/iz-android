package org.iz.navigation.data

import java.io.OutputStream
import java.io.Writer
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal const val BACKUP_MANIFEST_BYTES = 32L * 1024 * 1024
internal const val BACKUP_TOTAL_BYTES = 512L * 1024 * 1024
internal const val BACKUP_PHOTO_BYTES = 50L * 1024 * 1024
internal const val BACKUP_MAX_FILES = 10_000

internal data class BackupExportOptions(
    val pageSize: Int = 256,
    val manifestLimit: Long = BACKUP_MANIFEST_BYTES,
    val onPage: (String, Int) -> Unit = { _, _ -> },
) {
    init { require(pageSize in 1..256 && manifestLimit in 1..BACKUP_MANIFEST_BYTES) }
}
internal data class BackupPage<T>(val key: Long, val value: T)

/** Inclusive key ranges also cover a negative/maximum SQLite rowid without overflow or OFFSET scans. */
internal suspend fun <T> forEachBackupPage(
    pageSize: Int = 256,
    maxRecords: Int = 500_000,
    load: suspend (firstInclusive: Long, limit: Int) -> List<BackupPage<T>>,
    consume: suspend (T) -> Unit,
): Int {
    require(pageSize in 1..256 && maxRecords >= 0)
    var first = Long.MIN_VALUE
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = load(first, pageSize)
        require(page.size <= pageSize) { "Yedek okuma sayfası sınırı aşıldı." }
        if (page.isEmpty()) return total
        require(page.first().key >= first && page.zipWithNext().all { (a, b) -> a.key < b.key }) {
            "Yedek sayfalama anahtarları tutarsız."
        }
        if (page.size > maxRecords - total) throw BackupLimitException("Yedekte çok fazla kayıt var.")
        for (row in page) { currentCoroutineContext().ensureActive(); consume(row.value) }
        total += page.size
        val last = page.last().key
        if (last == Long.MAX_VALUE || page.size < pageSize) return total
        first = last + 1
    }
}

internal class BackupLimitException(message: String) : IOException(message)

/** Counts actual encoded UTF-8 bytes, before each downstream write, without a second manifest copy. */
internal class BackupByteLimitOutputStream(
    private val output: OutputStream,
    private val limit: Long,
    private val message: String,
) : OutputStream() {
    var count: Long = 0
        private set
    init { require(limit >= 0) }
    override fun write(value: Int) {
        if (count >= limit) throw BackupLimitException(message)
        output.write(value); count++
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (length.toLong() > limit - count) throw BackupLimitException(message)
        output.write(bytes, offset, length); count += length
    }
    override fun flush() = output.flush()
    // This wrapper owns only an entry budget, never the surrounding ZIP stream.
}

/** Reuses BackupJson's typed field mappings, but never builds a whole-array JSON/string/byte tree. */
internal object BackupJsonStream {
    fun string(out: Writer, value: String) {
        out.write('"'.code)
        for (c in value) when (c) {
            '"' -> out.write("\\\"")
            '\\' -> out.write("\\\\")
            '\b' -> out.write("\\b")
            '\u000c' -> out.write("\\f")
            '\n' -> out.write("\\n")
            '\r' -> out.write("\\r")
            '\t' -> out.write("\\t")
            else -> if (c.code < 32) out.write("\\u" + c.code.toString(16).padStart(4, '0')) else out.write(c.code)
        }
        out.write('"'.code)
    }
    fun value(out: Writer, value: Any?) {
        when {
            value == null || value === JSONObject.NULL -> out.write("null")
            value is String -> string(out, value)
            value is Boolean -> out.write(value.toString())
            value is Number -> {
                require(value.toDouble().isFinite()) { "Geçersiz yedek sayısı." }
                out.write(value.toString())
            }
            value is JSONObject -> {
                out.write('{'.code); var first = true
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!first) out.write(','.code); first = false
                    string(out, key); out.write(':'.code); this.value(out, value.get(key))
                }
                out.write('}'.code)
            }
            value is JSONArray -> {
                out.write('['.code)
                for (i in 0 until value.length()) {
                    if (i != 0) out.write(','.code)
                    this.value(out, value.get(i))
                }
                out.write(']'.code)
            }
            else -> error("Desteklenmeyen yedek alanı.")
        }
    }
}
