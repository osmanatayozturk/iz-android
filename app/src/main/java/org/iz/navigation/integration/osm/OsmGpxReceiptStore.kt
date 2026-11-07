package org.iz.navigation.integration.osm

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

internal interface OsmGpxReceiptStore {
    fun read(accountId: Long, key: OsmGpxReceiptKey): OsmGpxReceipt?
    fun write(receipt: OsmGpxReceipt)
    fun remove(accountId: Long, key: OsmGpxReceiptKey)
}

/** One atomically replaced record per account, journey and prepared trace digest. */
internal class OsmGpxFileReceipts(noBackupRoot: File) : OsmGpxReceiptStore {
    private val root = File(noBackupRoot, "osm-gpx-receipts")

    override fun read(accountId: Long, key: OsmGpxReceiptKey): OsmGpxReceipt? {
        val file = fileFor(accountId, key)
        if (Files.notExists(file.toPath())) return null
        val json = file.inputStream().buffered().use { input ->
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
        val status = OsmGpxReceiptStatus.valueOf(json.getString("status"))
        val id = if (status == OsmGpxReceiptStatus.RECEIVED) json.getLong("traceId") else null
        require(id == null || id > 0)
        return OsmGpxReceipt(accountId, key, status, id, json.getString("attemptId"), json.getLong("attemptedAt"))
    }

    override fun write(receipt: OsmGpxReceipt) {
        val file = fileFor(receipt.accountId, receipt.key)
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val bytes = JSONObject()
            .put("status", receipt.status.name)
            .put("traceId", receipt.traceId)
            .put("attemptId", receipt.attemptId)
            .put("attemptedAt", receipt.attemptedAt)
            .toString().toByteArray(Charsets.UTF_8)
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

    override fun remove(accountId: Long, key: OsmGpxReceiptKey) {
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
        val digestPattern = Regex("[0-9a-f]{64}")
    }
}
