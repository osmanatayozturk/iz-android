package com.atay.iz.integration

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.atay.iz.data.Photo
import java.io.File
import java.io.IOException
import java.util.UUID
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Imports only a URI explicitly selected by the user; no gallery-wide permission is used. */
class MediaStoreHelper(context: Context) {
    private val context = context.applicationContext

    suspend fun importPhoto(
        uri: Uri,
        journeyId: String? = null,
        visitId: String? = null,
    ): Photo = withContext(Dispatchers.IO) {
        val folder = File(context.filesDir, "photos").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val temporary = File(folder, "$id.import")
        var destination: File? = null
        try {
            val reportedType = context.contentResolver.getType(uri)
            require(reportedType == null || reportedType.startsWith("image/")) {
                "Lütfen bir fotoğraf seç."
            }
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Fotoğraf açılamadı.")
            input.use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytes = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count == -1) break
                        bytes += count
                        require(bytes <= 50L * 1024 * 1024) { "Fotoğraf 50 MB sınırını aşıyor." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "Fotoğraf biçimi desteklenmiyor veya dosya okunamıyor."
            }
            val extension = when (bounds.outMimeType ?: reportedType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"
                "image/avif" -> "avif"
                "image/gif" -> "gif"
                "image/bmp" -> "bmp"
                else -> "jpg"
            }
            val file = File(folder, "$id.$extension")
            destination = file
            check(temporary.renameTo(file)) { "Fotoğraf uygulamaya kopyalanamadı." }
            val exif = runCatching { ExifInterface(file) }.getOrNull()
            val coordinates = runCatching { exif?.latLong }.getOrNull()
                ?.takeIf { it.size == 2 && it[0].isFinite() && it[1].isFinite() &&
                    it[0] in -90.0..90.0 && it[1] in -180.0..180.0 }
            Photo(
                id = id,
                relativePath = "photos/${file.name}",
                journeyId = journeyId,
                visitId = visitId,
                // A missing EXIF capture time stays unknown, instead of becoming import time.
                takenAt = readCaptureTime(exif),
                latitude = coordinates?.get(0),
                longitude = coordinates?.get(1),
            )
        } catch (error: Exception) {
            temporary.delete()
            destination?.delete()
            throw error
        }
    }

    fun createCaptureUri(): Uri {
        val folder = File(context.cacheDir, "camera").apply { mkdirs() }
        val staleBefore = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        folder.listFiles()?.filter { it.isFile && it.lastModified() < staleBefore }
            ?.forEach { it.delete() }
        val file = File(folder, "${UUID.randomUUID()}.jpg")
        check(file.createNewFile()) { "Kamera için dosya oluşturulamadı." }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun readCaptureTime(exif: ExifInterface?): Long? = runCatching {
        val raw = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        val localTime = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT))
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.trim()
        val instant = if (!offset.isNullOrEmpty()) localTime.toInstant(ZoneOffset.of(offset))
            else localTime.atZone(ZoneId.systemDefault()).toInstant()
        instant.toEpochMilli()
    }.getOrNull()

    fun deletePendingCapture(uri: Uri) {
        if (uri.scheme == "content" && uri.authority == "${context.packageName}.files" &&
            uri.pathSegments.firstOrNull() == "camera"
        ) {
            context.contentResolver.delete(uri, null, null)
        }
    }
}

/** Accept only private photo files, including when the database came from an imported backup. */
internal fun photoFile(context: Context, photo: Photo): File {
    val folder = File(context.filesDir, "photos").canonicalFile
    val file = File(context.filesDir, photo.relativePath).canonicalFile
    require(file.parentFile == folder && file.isFile) { "Fotoğraf dosyası bulunamadı." }
    return file
}
