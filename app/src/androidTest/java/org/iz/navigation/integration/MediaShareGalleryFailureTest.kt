package org.iz.navigation.integration

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.Photo
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaShareGalleryFailureTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val sources = mutableListOf<File>()
    private val providerFolders = mutableListOf<File>()

    @After fun cleanup() {
        sources.forEach { it.delete() }
        providerFolders.forEach { it.deleteRecursively() }
    }

    @Test fun galleryWritesOnlyDecodedPixelsWithoutCaptureColumns() = runBlocking {
        val provider = provider()
        val source = photo()
        val original = sources.single().readBytes()
        assertEquals(1, MediaShare.savePhotosToGallery(context(provider), listOf(source)))
        val values = provider.values.single()
        assertFalse(values.containsKey(MediaStore.Images.Media.DATE_TAKEN))
        assertEquals("image/png", values.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertFalse(values.getAsString(MediaStore.Images.Media.DISPLAY_NAME).contains(sources.single().name))
        assertEquals(1, values.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(listOf(1), provider.published)
        assertNull(ExifInterface(provider.files.getValue(1)).latLong)
        assertArrayEquals(original, sources.single().readBytes())
    }

    @Test fun failuresAfterFirstInsertRollBackEveryInsertedRow() = runBlocking {
        for (failure in Failure.entries.filter { it != Failure.NONE }) {
            val provider = provider(failure)
            val selected = listOf(photo(), photo())
            val originals = sources.takeLast(2).map { it.readBytes() }
            val cacheBefore = sharedFiles()
            val error = runCatching { MediaShare.savePhotosToGallery(context(provider), selected) }.exceptionOrNull()
            assertNotNull("Expected failure for $failure", error)
            assertEquals("Every inserted row must be a rollback target for $failure", provider.files.keys.toSet(), provider.deleted.toSet())
            assertEquals("No prepared files may survive $failure", cacheBefore, sharedFiles())
            originals.forEachIndexed { i, bytes -> assertArrayEquals(bytes, sources.takeLast(2)[i].readBytes()) }
            if (failure == Failure.CANCEL) assertTrue(error is CancellationException)
        }
    }

    @Test fun cleanupFailureDoesNotReplaceThePrimaryPublicationError() = runBlocking {
        val provider = provider(Failure.PUBLISH).apply { failDelete = true }
        val error = runCatching {
            MediaShare.savePhotosToGallery(context(provider), listOf(photo(), photo()))
        }.exceptionOrNull()
        assertNotNull(error)
        assertEquals("Fotoğraf galeride görünür hale getirilemedi.", error!!.message)
        assertEquals(provider.files.keys.toSet(), provider.deleted.toSet())
    }

    @Test fun invalidLaterPhotoCreatesNoGalleryRows() = runBlocking {
        val provider = provider()
        val good = photo()
        val bad = photo().also { sources.last().writeText("not an image") }
        val cacheBefore = sharedFiles()
        assertNotNull(runCatching { MediaShare.savePhotosToGallery(context(provider), listOf(good, bad)) }.exceptionOrNull())
        assertTrue(provider.files.isEmpty())
        assertEquals(cacheBefore, sharedFiles())
    }

    private fun context(provider: GalleryProvider): Context {
        val resolver = ContentResolver.wrap(provider)
        return object : ContextWrapper(base) {
            override fun getContentResolver(): ContentResolver = resolver
        }
    }

    private fun provider(failure: Failure = Failure.NONE): GalleryProvider {
        val folder = File(base.cacheDir, "gallery-provider-${UUID.randomUUID()}").apply { mkdirs() }
        providerFolders += folder
        return GalleryProvider(folder, failure).apply {
            attachInfo(base, ProviderInfo().apply { authority = "media" })
        }
    }

    private fun photo(): Photo {
        val file = File(base.filesDir, "photos/synthetic-gallery-${UUID.randomUUID()}.jpg")
        file.parentFile!!.mkdirs()
        sources += file
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
        finally { bitmap.recycle() }
        ExifInterface(file).apply { setLatLong(12.0, 34.0); saveAttributes() }
        return Photo(relativePath = "photos/${file.name}", takenAt = 123456789L)
    }

    private fun sharedFiles(): Set<String> = File(base.cacheDir, "shared-photos").walkTopDown()
        .filter { it.isFile }.map { it.absolutePath }.toSet()

    private enum class Failure { NONE, INSERT, OPEN, NULL_STREAM, WRITE, PUBLISH, CANCEL }

    private class GalleryProvider(private val folder: File, private val failure: Failure) : ContentProvider() {
        val files = linkedMapOf<Int, File>()
        val values = mutableListOf<ContentValues>()
        val deleted = mutableListOf<Int>()
        val published = mutableListOf<Int>()
        var failDelete = false
        private var inserts = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val id = ++inserts
            if (id == 2 && failure == Failure.INSERT) return null
            files[id] = File(folder, "$id.png")
            this.values += ContentValues(requireNotNull(values))
            return Uri.withAppendedPath(uri, id.toString())
        }
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
            if (uri.lastPathSegment == "2" && failure == Failure.NULL_STREAM) return null
            return AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = uri.lastPathSegment!!.toInt()
            if (id == 2 && failure == Failure.OPEN) throw FileNotFoundException("synthetic open failure")
            if (id == 2 && failure == Failure.CANCEL) throw CancellationException("synthetic cancellation")
            val file = files.getValue(id).apply { createNewFile() }
            val flags = if (id == 2 && failure == Failure.WRITE) ParcelFileDescriptor.MODE_READ_ONLY
                else ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
            return ParcelFileDescriptor.open(file, flags)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = uri.lastPathSegment!!.toInt()
            check(values?.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 0)
            if (id == 2 && failure == Failure.PUBLISH) return 0
            published += id
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = uri.lastPathSegment!!.toInt()
            deleted += id
            if (failDelete) throw IllegalStateException("synthetic cleanup failure")
            files.getValue(id).delete()
            return 1
        }
    }
}
