package org.iz.navigation.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.iz.navigation.lean.LeanStore

/** Opaque, process-local handle. Provider destinations and plaintext health are never persisted here. */
data class PreparedDiaryBackup internal constructor(val id: String, val sizeBytes: Long, val sha256: String)
enum class BackupCopyPhase { WRITE, VERIFY }
class BackupCopyException(val phase: BackupCopyPhase, cause: Throwable) : IOException(
    if (phase == BackupCopyPhase.WRITE) "Yedek kaydedilemedi" else "Yedek doğrulanamadı", cause)
class BackupPreparationException(val cleanupFailed: Boolean, val limitMessage: String?, cause: Throwable) :
    IOException("Yedek hazırlanamadı.", cause)
class BackupPreparationCancelledException(val cleanupFailed: Boolean) : CancellationException("Yedek hazırlama iptal edildi.")

internal data class BackupDigest(val bytes: Long, val sha256: String)
internal fun backupCopy(input: InputStream, output: OutputStream?, maximum: Long, check: () -> Unit): BackupDigest {
    require(maximum >= 0)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var count = 0L
    while (true) {
        check()
        val size = input.read(buffer)
        if (size < 0) break
        if (size == 0) continue
        if (size.toLong() > maximum - count) throw IOException("Dosya beklenen boyut sınırını aşıyor.")
        output?.write(buffer, 0, size)
        digest.update(buffer, 0, size); count += size
    }
    check()
    return BackupDigest(count, digest.digest().joinToString("") { "%02x".format(it) })
}
internal fun backupDigest(input: InputStream, maximum: Long, check: () -> Unit) = backupCopy(input, null, maximum, check)

/** The only temporary namespace is a dedicated private no-backup child; no old backups are touched. */
internal class DiaryBackupStages(context: Context, private val repository: DiaryRepository) {
    private val context = context.applicationContext
    private val root get() = File(context.noBackupFilesDir, "diary-export")
    private fun directory(): File {
        val base = context.noBackupFilesDir.canonicalFile
        val directory = root
        require(!Files.isSymbolicLink(directory.toPath())) { "Geçici yedek alanı geçersiz." }
        check(directory.isDirectory || directory.mkdirs()) { "Yedek için geçici alan oluşturulamadı." }
        require(directory.canonicalFile.parentFile == base) { "Geçici yedek alanı geçersiz." }
        return directory
    }
    private fun file(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        val directory = directory()
        return File(directory, "$id.zip").also {
            require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == directory.canonicalFile)
        }
    }
    private fun key(file: File) = file.absolutePath
    private fun remove(file: File): Boolean = synchronized(active) {
        val removed = !file.exists() || (!Files.isSymbolicLink(file.toPath()) && file.isFile && file.delete())
        active.remove(key(file))
        removed
    }

    suspend fun prepare(includeHealth: Boolean, options: BackupExportOptions): PreparedDiaryBackup {
        var created: File? = null
        // Catch outside withContext: prompt cancellation while returning a completed handle must clean it too.
        try {
            return withContext(Dispatchers.IO) {
                val current = currentCoroutineContext()
                val check = { current.ensureActive() }
                val id = UUID.randomUUID().toString()
                val target = synchronized(active) {
                    file(id).also {
                        kotlin.check(it.createNewFile()) { "Geçici yedek dosyası oluşturulamadı." }
                        created = it
                        active[key(it)] = null
                    }
                }
                val expected = LeanStore.withSnapshotLease {
                    ZipOutputStream(target.outputStream().buffered(8192)).use { zip ->
                        val exporter = repository.withBackupRead { dao ->
                            PagedBackupExporter(repository, dao, options).also { it.writeManifest(zip, includeHealth) }
                        }
                        exporter.writeAttachments(zip)
                    }
                }
                // Database and raw lease have both been released before validation, picker or provider copy.
                validateClosedZip(target, expected, check)
                val digest = target.inputStream().use { backupDigest(it, MAX_ARCHIVE_BYTES, check) }
                require(digest.bytes > 0)
                val prepared = PreparedDiaryBackup(id, digest.bytes, digest.sha256)
                synchronized(active) { active[key(target)] = prepared }
                check()
                prepared
            }
        } catch (failure: Throwable) {
            val cleaned = withContext(NonCancellable + Dispatchers.IO) {
                created?.let { runCatching { remove(it) }.getOrDefault(false) } ?: true
            }
            if (failure is CancellationException) throw BackupPreparationCancelledException(!cleaned).also { it.initCause(failure) }
            if (failure is Error) throw failure
            throw BackupPreparationException(!cleaned, (failure as? BackupLimitException)?.message, failure)
        }
    }

    suspend fun save(prepared: PreparedDiaryBackup, uri: Uri) = withContext(Dispatchers.IO) {
        val current = currentCoroutineContext()
        val check = { current.ensureActive() }
        try {
            val source = file(prepared.id)
            require(synchronized(active) { active[key(source)] == prepared } && source.isFile)
            val before = source.inputStream().use { backupDigest(it, prepared.sizeBytes, check) }
            require(before.bytes == prepared.sizeBytes && before.sha256 == prepared.sha256) { "Geçici yedek değişti veya kayboldu." }
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Hedef açılamadı.")
            output.use { target ->
                source.inputStream().use { input ->
                    val copied = backupCopy(input, target, prepared.sizeBytes, check)
                    require(copied.bytes == prepared.sizeBytes && copied.sha256 == prepared.sha256) { "Geçici yedek kopyalama sırasında değişti." }
                }
                target.flush()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw BackupCopyException(BackupCopyPhase.WRITE, failure) }
        try {
            check()
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Hedef geri okunamadı.")
            val actual = input.use { backupDigest(it, prepared.sizeBytes, check) }
            require(actual.bytes == prepared.sizeBytes && actual.sha256 == prepared.sha256) { "Hedef yedek eşleşmedi." }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw BackupCopyException(BackupCopyPhase.VERIFY, failure) }
    }

    suspend fun discard(prepared: PreparedDiaryBackup): Boolean = withContext(Dispatchers.IO) {
        runCatching { remove(file(prepared.id)) }.getOrDefault(false)
    }
    suspend fun cleanupAbandoned(): Boolean = withContext(Dispatchers.IO) {
        synchronized(active) {
            val directory = directory()
            val files = directory.listFiles() ?: return@synchronized false
            var clean = true
            for (file in files) {
                val id = file.name.removeSuffix(".zip")
                val ownedName = file.name == "$id.zip" && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
                if (!ownedName || active.containsKey(key(file))) continue
                if (Files.isSymbolicLink(file.toPath()) || !file.isFile || !file.delete()) clean = false
            }
            clean
        }
    }

    private fun validateClosedZip(file: File, expected: Map<String, Long>, check: () -> Unit) {
        val seen = HashSet<String>()
        var expanded = 0L
        ZipInputStream(file.inputStream().buffered(8192)).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                check()
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && seen.add(entry.name) && seen.size <= BACKUP_MAX_FILES)
                val maximum = expected[entry.name] ?: error("Beklenmeyen yedek girdisi.")
                var count = 0L
                while (true) {
                    check()
                    val size = zip.read(buffer)
                    if (size < 0) break
                    count += size; expanded += size
                    require(count <= maximum && expanded <= BACKUP_TOTAL_BYTES) { "Yedek boyutu eşleşmedi." }
                }
                require(count == maximum) { "Eksik yedek girdisi." }
                zip.closeEntry() // ZipInputStream checks entry CRC while exhausting each closed entry.
            }
        }
        require(seen == expected.keys && seen.contains("manifest.json")) { "Yedek girdileri eşleşmedi." }
    }
    companion object {
        // Extra room for ZIP headers/deflate overhead while expanded bytes stay strictly <=512 MiB.
        private const val MAX_ARCHIVE_BYTES = BACKUP_TOTAL_BYTES + 16L * 1024 * 1024
        private val active = mutableMapOf<String, PreparedDiaryBackup?>()
    }
}
