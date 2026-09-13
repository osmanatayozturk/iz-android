package org.iz.navigation.integration

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.Photo
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoExportCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ownedFiles = mutableListOf<File>()

    @After fun cleanup() { ownedFiles.forEach { it.deleteRecursively() } }

    @Test fun staleShareIsRemovedWhileRecentReceiverCanStillReadItsCopy() = runBlocking {
        val stale = cachedBatch()
        val recent = cachedBatch()
        val staleFile = File(stale, "copy.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val recentFile = File(recent, "copy.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val old = System.currentTimeMillis() - 25L * 60 * 60 * 1000
        assertTrue(staleFile.setLastModified(old))
        assertTrue(stale.setLastModified(old))
        val prepared = PhotoExport.prepare(context, listOf(source()))
        try {
            assertFalse(stale.exists())
            assertArrayEquals(byteArrayOf(4, 5, 6), recentFile.readBytes())
            assertEquals(1, prepared.files.size)
            assertTrue(prepared.files.single().isFile)
        } finally { prepared.close() }
        assertFalse(prepared.directory.exists())
        assertTrue(recentFile.exists())
    }

    @Test fun fullRecentCacheRejectsNewBatchWithoutDeletingActiveShare() = runBlocking {
        val recent = cachedBatch()
        val file = File(recent, "copy.png")
        RandomAccessFile(file, "rw").use { it.setLength(256L * 1024 * 1024) }
        val before = entries()
        assertNotNull(runCatching { PhotoExport.prepare(context, listOf(source())) }.exceptionOrNull())
        assertEquals(before, entries())
        assertEquals(256L * 1024 * 1024, file.length())
    }

    private fun cachedBatch(): File = File(context.cacheDir, "shared-photos/cache-test-${UUID.randomUUID()}")
        .apply { mkdirs(); ownedFiles += this }

    private fun entries() = File(context.cacheDir, "shared-photos").walkTopDown()
        .filter { it.exists() }.map { it.absolutePath }.toSet()

    private fun source(): Photo {
        val file = File(context.filesDir, "photos/cache-test-${UUID.randomUUID()}.png")
        file.parentFile!!.mkdirs()
        ownedFiles += file
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        return Photo(relativePath = "photos/${file.name}")
    }
}
