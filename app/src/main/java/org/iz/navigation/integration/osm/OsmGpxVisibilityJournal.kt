package org.iz.navigation.integration.osm

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class OsmVisibilityAttempt(val nonce: String, val fieldsDigest: String)

/** A pending PUT survives cancellation and process death. Evictable geometry lives in another directory. */
internal class OsmGpxVisibilityJournal(private val root: File) {
    private fun file(account: Long, id: Long): File {
        require(account > 0 && id > 0)
        return File(File(root, account.toString()), "$id.put")
    }

    fun attempts(account: Long): Map<Long, OsmVisibilityAttempt> = synchronized(lock) {
        val dir = file(account, 1).parentFile!!
        if (Files.notExists(dir.toPath())) return@synchronized emptyMap()
        Files.newDirectoryStream(dir.toPath(), "*.put").use { files ->
            val result = mutableMapOf<Long, OsmVisibilityAttempt>()
            for (path in files) {
                require(result.size < MAX_OSM_TRACES)
                val id = path.fileName.toString().removeSuffix(".put").toLong()
                result[id] = requireNotNull(read(file(account, id)))
            }
            result
        }
    }

    private fun read(file: File): OsmVisibilityAttempt? {
        if (Files.notExists(file.toPath())) return null
        require(file.length() in 1..160)
        val lines = file.readLines(Charsets.US_ASCII)
        require(lines.size == 2 && digestPattern.matches(lines[1]))
        UUID.fromString(lines.first())
        return OsmVisibilityAttempt(lines.first(), lines[1])
    }

    fun reserve(account: Long, id: Long, expected: OsmVisibilityAttempt?, digest: String): OsmVisibilityAttempt? =
        synchronized(lock) {
            require(digestPattern.matches(digest))
            val target = file(account, id)
            if (read(target) != expected) return@synchronized null
            val dir = requireNotNull(target.parentFile)
            check(dir.isDirectory || dir.mkdirs())
            val attempt = OsmVisibilityAttempt(UUID.randomUUID().toString(), digest)
            val temp = File.createTempFile("reservation-", ".tmp", dir)
            try {
                FileOutputStream(temp).use {
                    it.write((attempt.nonce + "\n" + attempt.fieldsDigest).toByteArray(Charsets.US_ASCII))
                    it.fd.sync()
                }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            attempt
        }

    fun complete(account: Long, id: Long, expected: OsmVisibilityAttempt) = synchronized(lock) {
        val target = file(account, id)
        if (read(target) == expected) Files.deleteIfExists(target.toPath())
        Unit
    }

    private companion object {
        val lock = Any()
        val digestPattern = Regex("[a-f0-9]{64}")
    }
}

internal data class OsmGpxVisibilityReview(
    val notes: Map<Long, String>,
    val blocked: Set<Long>,
    val retry: Map<Long, OsmVisibilityAttempt>,
    val updates: Map<Long, OsmGpxRemoteMetadata>,
)

internal suspend fun reconcileVisibility(account: Long, token: String, journal: OsmGpxVisibilityJournal,
    gateway: OsmGpxHistoryGateway, entries: List<OsmGpxHistoryEntry>, current: () -> Boolean): OsmGpxVisibilityReview {
    val listed = entries.mapTo(HashSet()) { it.id }
    val notes = mutableMapOf<Long, String>()
    val blocked = mutableSetOf<Long>()
    val retry = mutableMapOf<Long, OsmVisibilityAttempt>()
    val updates = mutableMapOf<Long, OsmGpxRemoteMetadata>()
    for ((id, attempt) in journal.attempts(account)) {
        check(current())
        blocked += id
        notes[id] = "Önceki görünürlük işlemi doğrulanamadı. Yeniden kontrol et."
        if (id !in listed) continue
        try {
            val now = gateway.readMetadata(token, account, id, current)
            check(current() && now.id == id)
            updates[id] = now
            when {
                now.pending -> notes[id] = "OSM bu izi henüz işliyor."
                now.visibility == "trackable" && now.digest() == attempt.fieldsDigest -> {
                    journal.complete(account, id, attempt)
                    notes[id] = "Önceki işlem doğrulandı. İz takip edilebilir."
                }
                now.visibility == "identifiable" -> {
                    retry[id] = attempt
                    blocked -= id
                    notes[id] = "İz hâlâ tanımlanabilir. Değiştirmek için yeniden seçip onayla."
                }
                else -> notes[id] = "Güncel iz veya alanları değişti. Yeni yazım yapılmadı."
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { check(current()) }
    }
    return OsmGpxVisibilityReview(notes, blocked, retry, updates)
}
