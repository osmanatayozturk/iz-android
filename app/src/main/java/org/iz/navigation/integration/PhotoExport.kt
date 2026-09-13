package org.iz.navigation.integration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import java.io.Closeable
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.iz.navigation.data.Photo

/** Owns only freshly encoded files, never the diary's originals. */
internal class PreparedPhotoBatch(val directory: File, val files: List<File>) : Closeable {
    override fun close() { directory.deleteRecursively() }
}

/** The shared boundary for public photo exports. Original container bytes never leave it. */
internal object PhotoExport {
    private const val MAX_SOURCE_BYTES = 50L * 1024 * 1024
    private const val MAX_BATCH_BYTES = 128L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 256L * 1024 * 1024
    private const val RETENTION_MS = 24L * 60 * 60 * 1000

    // MediaShare serializes preparation and delivery, including cache accounting.
    suspend fun prepare(context: Context, photos: List<Photo>): PreparedPhotoBatch {
        val job = currentCoroutineContext()
        job.ensureActive()
        val root = File(context.cacheDir, "shared-photos")
        check(root.isDirectory || root.mkdirs()) { "Fotoğraf kopyaları için yer açılamadı." }
        val now = System.currentTimeMillis()
        root.listFiles()?.filter { now - it.lastModified() > RETENTION_MS }
            ?.forEach { it.deleteRecursively() }
        val retainedBytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val directory = File(root, UUID.randomUUID().toString())
        check(directory.mkdir()) { "Fotoğraf kopyaları hazırlanamadı." }
        var complete = false
        val files = mutableListOf<File>()
        var batchBytes = 0L
        try {
            photos.forEach {
                job.ensureActive()
                val source = photoFile(context, it)
                require(source.length() in 1..MAX_SOURCE_BYTES) { "Fotoğraf boş veya 50 MB sınırını aşıyor." }
                val budget = minOf(MAX_BATCH_BYTES - batchBytes, MAX_CACHE_BYTES - retainedBytes - batchBytes)
                require(budget > 0) { "Fotoğraf kopyaları için alan sınırına ulaşıldı. Daha az fotoğraf seç veya daha sonra dene." }
                val bitmap = try {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                        require(!info.isAnimated) { "Hareketli görseller aktarılamıyor. Durağan bir fotoğraf seç." }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                        decoder.setOnPartialImageListener { false }
                        val (width, height) = photoExportSize(info.size.width, info.size.height)
                        decoder.setTargetSize(width, height)
                    }
                } catch (error: ImageDecoder.DecodeException) {
                    throw IOException("Fotoğraf biçimi desteklenmiyor veya dosya okunamıyor.", error)
                }
                try {
                    job.ensureActive()
                    val output = File(directory, "${UUID.randomUUID()}.png")
                    output.outputStream().buffered().use { stream ->
                        val limited = PhotoExportOutputStream(stream, budget) { job.ensureActive() }
                        // ImageDecoder applies orientation to pixels; PNG encoding does not copy
                        // source EXIF/XMP/IPTC, thumbnails, comments, names or trailing payloads.
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, limited)) { "Fotoğraf kopyası yazılamadı." }
                        limited.flush()
                        batchBytes += limited.bytesWritten
                    }
                    job.ensureActive()
                    files += output
                } finally {
                    bitmap.recycle()
                }
            }
            job.ensureActive()
            val result = PreparedPhotoBatch(directory, files.toList())
            complete = true
            return result
        } catch (error: OutOfMemoryError) {
            throw IOException("Fotoğraf için yeterli bellek yok. Daha küçük bir fotoğraf seç.", error)
        } finally {
            if (!complete) directory.deleteRecursively()
        }
    }
}

/** Bound decoded memory before allocation without upscaling or changing the aspect ratio. */
internal fun photoExportSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Fotoğraf boyutları okunamıyor." }
    val scale = minOf(1.0, 4096.0 / max(width, height), sqrt(8_000_000.0 / (width.toDouble() * height)))
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/** Reject before writing beyond the batch/cache budget, including during native compression. */
internal class PhotoExportOutputStream(
    output: OutputStream,
    private val limit: Long,
    private val checkActive: () -> Unit,
) : FilterOutputStream(output) {
    var bytesWritten = 0L
        private set

    override fun write(value: Int) {
        checkBudget(1)
        out.write(value)
        bytesWritten++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkBudget(length)
        out.write(bytes, offset, length)
        bytesWritten += length
    }

    private fun checkBudget(length: Int) {
        checkActive()
        if (bytesWritten > limit - length) throw IOException("Fotoğraf kopyaları alan sınırını aşıyor. Daha az fotoğraf seç.")
    }
}
