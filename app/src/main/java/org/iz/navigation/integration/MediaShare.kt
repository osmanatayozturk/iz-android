package org.iz.navigation.integration

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.iz.navigation.data.Photo
import org.iz.navigation.data.Place
import org.iz.navigation.data.Transport
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object MediaShare {
    private val photoExportMutex = Mutex()
    fun openPlace(context: Context, place: Place) {
        val coordinates = if (place.latitude != null && place.longitude != null) "${place.latitude},${place.longitude}" else null
        val query = coordinates?.let { "$it (${place.name})" } ?: place.name
        val geo = Uri.parse("geo:${coordinates ?: "0,0"}?q=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, geo)
        try {
            if (intent.resolveActivity(context.packageManager) == null) throw IllegalStateException("Harita uygulaması bulunamadı.")
            launch(context, Intent.createChooser(intent, "Haritada aç"))
        }
        catch (_: IllegalStateException) {
            val url = if (coordinates != null) "https://www.openstreetmap.org/?mlat=${place.latitude}&mlon=${place.longitude}#map=17/${place.latitude}/${place.longitude}"
                else "https://www.openstreetmap.org/search?query=${Uri.encode(place.name)}"
            openUrl(context, url)
        }
    }

    fun openUrl(context: Context, url: String) = launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun launch(context: Context, intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) }
        catch (error: ActivityNotFoundException) { throw IllegalStateException("Bu işlemi açabilecek bir uygulama bulunamadı.", error) }
    }
    suspend fun sharePhotos(context: Context, photos: List<Photo>) = photoExportMutex.withLock {
        require(photos.isNotEmpty()) { "Paylaşmak için fotoğraf seç." }
        val selected = photos.toList()
        var batch: PreparedPhotoBatch? = null
        var handedOff = false
        try {
            // Keep ownership outside withContext: cancellation on its return must also clean up.
            withContext(Dispatchers.IO) { batch = PhotoExport.prepare(context, selected) }
            val prepared = requireNotNull(batch)
            withContext(Dispatchers.Main.immediate) {
                val uris = ArrayList(prepared.files.map { file ->
                    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                })
                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/png"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    clipData = ClipData.newUri(context.contentResolver, "Seçilen fotoğraflar", uris.first()).also { clip ->
                        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                launch(context, Intent.createChooser(shareIntent, "Fotoğrafları paylaş").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                // Receivers read asynchronously. Successful copies survive until stale cleanup.
                handedOff = true
            }
        } finally {
            if (!handedOff) withContext(NonCancellable + Dispatchers.IO) { batch?.close() }
        }
    }

    /** Gallery fallback uses scoped MediaStore storage; partial writes are rolled back. */
    suspend fun savePhotosToGallery(context: Context, photos: List<Photo>): Int = photoExportMutex.withLock {
        require(photos.isNotEmpty()) { "Galeriye kaydetmek için fotoğraf seç." }
        val selected = photos.toList()
        withContext(Dispatchers.IO) {
            PhotoExport.prepare(context, selected).use { batch ->
                val resolver = context.contentResolver
                val inserted = mutableListOf<Uri>()
                try {
                    batch.files.forEach { file ->
                        currentCoroutineContext().ensureActive()
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "Iz_${file.name}")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Iz")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("Galeriye fotoğraf eklenemedi.")
                        inserted += uri
                        val output = resolver.openOutputStream(uri)
                            ?: throw IOException("Galeri dosyası açılamadı.")
                        output.use { destination -> file.inputStream().use { it.copyTo(destination) } }
                    }
                    inserted.forEach { uri ->
                        currentCoroutineContext().ensureActive()
                        val updated = resolver.update(uri, ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }, null, null)
                        check(updated > 0) { "Fotoğraf galeride görünür hale getirilemedi." }
                    }
                    inserted.size
                } catch (error: Exception) {
                    inserted.forEach { runCatching { resolver.delete(it, null, null) } }
                    throw error
                }
            }
        }
    }


}
