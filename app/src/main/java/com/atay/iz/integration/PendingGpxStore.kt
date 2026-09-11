package com.atay.iz.integration

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Keeps an export alive across Activity recreation while the Android document picker is open. */
class PendingGpxStore(directory: File) {
    private val root = directory.canonicalFile

    fun prepare(xml: String): String {
        require(xml.isNotBlank()) { "Boş GPX dosyası hazırlanamaz." }
        check((root.isDirectory || root.mkdirs()) && root.isDirectory) { "GPX dosyası için özel klasör oluşturulamadı." }
        val id = UUID.randomUUID().toString()
        val target = exportFile(id)
        var temporary: File? = null
        try {
            temporary = File.createTempFile("pending-", ".tmp", root)
            FileOutputStream(temporary).use { output ->
                output.write(xml.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return id
        } catch (error: IOException) {
            throw IllegalStateException("GPX dosyası hazırlanamadı. Yeniden dışa aktarmayı dene.", error)
        } finally {
            temporary?.delete()
        }
    }

    fun read(id: String): String {
        val file = exportFile(id)
        check(file.isFile) { "Hazırlanan GPX dosyası bulunamadı. Yeniden dışa aktarmayı dene." }
        return try {
            file.readText(Charsets.UTF_8).also {
                check(it.isNotBlank()) { "Hazırlanan GPX dosyası boş. Yeniden dışa aktarmayı dene." }
            }
        } catch (error: IOException) {
            throw IllegalStateException("Hazırlanan GPX dosyası okunamadı. Yeniden dışa aktarmayı dene.", error)
        }
    }

    fun remove(id: String) {
        val file = exportFile(id)
        check(!file.exists() || (file.isFile && file.delete())) { "Geçici GPX dosyası kaldırılamadı." }
    }

    private fun exportFile(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Geçersiz GPX dosyası kimliği." }
        val file = File(root, "$id.gpx").canonicalFile
        require(file.parentFile == root) { "Geçersiz GPX dosyası yolu." }
        return file
    }
}
