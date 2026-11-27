package org.iz.navigation.integration.osm

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

internal data class OsmGpxStoredReceipt(
    val sha256: String, val journeyId: String?, val status: OsmGpxReceiptStatus,
    val traceId: Long?, val verified: Boolean, val recordName: String, val attemptId: String, val attemptedAt: Long,
)

internal interface OsmGpxReceiptStore {
    val coverageCache: OsmGpxCoverageCache
    val visibilityJournal: OsmGpxVisibilityJournal
    fun verifyRemoteReceipt(accountId: Long, record: OsmGpxStoredReceipt, id: Long)
    fun inventory(accountId: Long): List<OsmGpxStoredReceipt>
    fun read(accountId: Long, key: OsmGpxReceiptKey): OsmGpxReceipt?
    fun write(receipt: OsmGpxReceipt)
    fun remove(accountId: Long, key: OsmGpxReceiptKey)
}

/** One atomically replaced record per account, journey and prepared trace digest. */
internal class OsmGpxFileReceipts(noBackupRoot: File) : OsmGpxReceiptStore {
    private val root = File(noBackupRoot, "osm-gpx-receipts")

    override val coverageCache = OsmGpxCoverageCache(File(noBackupRoot, "osm-gpx-coverage"))
    override val visibilityJournal = OsmGpxVisibilityJournal(File(noBackupRoot, "osm-gpx-visibility"))

    override fun inventory(accountId: Long): List<OsmGpxStoredReceipt> {
        require(accountId > 0)
        val dir = File(root, accountId.toString())
        if (Files.notExists(dir.toPath())) return emptyList()
        return Files.newDirectoryStream(dir.toPath(), "*.json").use { files ->
            files.asSequence().take(MAX_OSM_TRACES + 1).map { path ->
                val name = path.fileName.toString()
                check(name.matches(RECORD_NAME))
                val json = readJson(path.toFile())
                val key = json.optString("journeyId").takeIf { it.isNotEmpty() }
                val sha = name.substringAfter('-').removeSuffix(".json")
                if (key != null) check(fileFor(accountId, OsmGpxReceiptKey(key, sha)).name == name)
                val status = OsmGpxReceiptStatus.valueOf(json.getString("status"))
                val id = if (status == OsmGpxReceiptStatus.RECEIVED) json.getLong("traceId").also { require(it > 0) } else null
                OsmGpxStoredReceipt(sha, key, status, id, json.opt("directPost") == true || json.opt("matchedRemoteData") == true,
                    name, json.getString("attemptId"), json.getLong("attemptedAt"))
            }.toList().also { require(it.size <= MAX_OSM_TRACES) }
        }
    }

    private fun readJson(file: File): JSONObject = file.inputStream().buffered().use { input ->
            val bytes = ByteArray(MAX_RECORD_BYTES)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            check(size < bytes.size)
            JSONObject(String(bytes, 0, size, Charsets.UTF_8))
        }
    override fun read(accountId: Long, key: OsmGpxReceiptKey): OsmGpxReceipt? {
        val file = fileFor(accountId, key)
        if (Files.notExists(file.toPath())) return null
        val json = readJson(file)
        val status = OsmGpxReceiptStatus.valueOf(json.getString("status"))
        val id = if (status == OsmGpxReceiptStatus.RECEIVED) json.getLong("traceId") else null
        require(id == null || id > 0)
        return OsmGpxReceipt(accountId, key, status, id, json.getString("attemptId"), json.getLong("attemptedAt"), json.opt("directPost") == true)
    }

    @Synchronized override fun write(receipt: OsmGpxReceipt) {
        val file = fileFor(receipt.accountId, receipt.key)
        val json = JSONObject()
            .put("journeyId", receipt.key.journeyId)
            .put("directPost", receipt.directPost)
            .put("status", receipt.status.name)
            .put("traceId", receipt.traceId)
            .put("attemptId", receipt.attemptId)
            .put("attemptedAt", receipt.attemptedAt)
        persist(file, json)
    }

    @Synchronized override fun verifyRemoteReceipt(accountId: Long, record: OsmGpxStoredReceipt, id: Long) {
        require(accountId > 0 && id > 0 && RECORD_NAME.matches(record.recordName))
        val file = File(File(root, accountId.toString()), record.recordName)
        val json = readJson(file)
        check(json.getString("attemptId") == record.attemptId && json.getString("status") == record.status.name)
        check(json.opt("directPost") != true && json.opt("matchedRemoteData") != true)
        if (record.status == OsmGpxReceiptStatus.RECEIVED) check(json.getLong("traceId") == record.traceId)
        persist(file, json.put("status", "RECEIVED").put("traceId", id).put("matchedRemoteData", true))
    }

    private fun persist(file: File, json: JSONObject) {
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size < MAX_RECORD_BYTES)
        val temp = File.createTempFile("receipt-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    @Synchronized override fun remove(accountId: Long, key: OsmGpxReceiptKey) {
        Files.deleteIfExists(fileFor(accountId, key).toPath())
    }

    private fun fileFor(accountId: Long, key: OsmGpxReceiptKey): File {
        require(accountId > 0 && key.sha256.matches(digestPattern))
        val journeyDigest = MessageDigest.getInstance("SHA-256").digest(key.journeyId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(root, accountId.toString()), listOf(journeyDigest, key.sha256).joinToString("-") + ".json")
    }

    private companion object {
        const val MAX_RECORD_BYTES = 1024
        val RECORD_NAME = Regex("[0-9a-f]{64}-[0-9a-f]{64}\\.json")
        val digestPattern = Regex("[0-9a-f]{64}")
    }
}
