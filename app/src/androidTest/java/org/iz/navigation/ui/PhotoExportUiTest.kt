package org.iz.navigation.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.iz.navigation.data.Photo
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoExportUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var evidenceDirectory: File
    private val sources = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        evidenceDirectory = File(context.cacheDir, "photo-export-ui-evidence/${UUID.randomUUID()}")
    }

    @After
    fun cleanUpSyntheticSources() {
        sources.forEach { it.delete() }
        // Keep only these synthetic UI captures for the test runner's visual review.
    }

    @Test
    fun shareSheetDisclosesSanitizedCopiesBeforeSelectionAndPassesOnlyChosenPhotos() {
        val photos = listOf("Birinci sentetik fotoğraf", "İkinci sentetik fotoğraf", "Üçüncü sentetik fotoğraf")
            .mapIndexed { index, caption -> syntheticPhoto(caption, index) }
        val originalBytes = sources.map { it.readBytes() }
        val sharedSelections = mutableListOf<List<Photo>>()
        val gallerySelections = mutableListOf<List<Photo>>()
        compose.setContent {
            IzTheme {
                PhotoShareSheet(
                    photos = photos,
                    onDismiss = {},
                    onShare = { sharedSelections += it },
                    onGallery = { gallerySelections += it },
                )
            }
        }

        compose.onNodeWithText("Fotoğraflarını paylaş").assertIsDisplayed()
        val disclosure = compose.onNodeWithText("kopyalarda dosyaya ekli konum ve çekim bilgileri kaldırılır", substring = true)
        disclosure.assertIsDisplayed()
        compose.onNodeWithText("Özgün fotoğraflar İz’de korunur", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Büyük fotoğraflar küçültülebilir", substring = true).assertIsDisplayed()
        captureDialog("photo-share-disclosure")
        compose.onNodeWithText("Seçilen fotoğrafları paylaş").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Galeriye kaydet").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(sharedSelections.isEmpty())
            assertTrue(gallerySelections.isEmpty())
        }

        compose.onAllNodes(isToggleable()).assertCountEquals(3)
        // Select out of order: callbacks must retain the input photo order.
        compose.onAllNodes(isToggleable())[2].performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onNodeWithText("Seçilen fotoğrafları paylaş").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Galeriye kaydet").performScrollTo().assertIsEnabled()
        captureDialog("photo-share-selected")

        compose.onNodeWithText("Seçilen fotoğrafları paylaş").performScrollTo().performClick()
        compose.onNodeWithText("Galeriye kaydet").performScrollTo().performClick()

        compose.runOnIdle {
            val expected = listOf(photos[0], photos[2])
            assertEquals(listOf(expected), sharedSelections)
            assertEquals(listOf(expected), gallerySelections)
        }
        sources.forEachIndexed { index, source -> assertArrayEquals(originalBytes[index], source.readBytes()) }
    }

    @Test
    fun editorExplainsRecordOnlyEditsAndClearingCoordinatesPreservesOriginalExifBytes() {
        val photo = syntheticPhoto("Sentetik düzenleme fotoğrafı", 0).copy(latitude = 12.345678, longitude = 45.678901)
        val source = sources.single()
        val originalBytes = source.readBytes()
        assertNotNull(ExifInterface(source).latLong)
        var saved: Photo? = null
        compose.setContent {
            IzTheme { PhotoEditor(photo = photo, onDismiss = {}, onSave = { saved = it }) }
        }

        compose.onNodeWithText("Fotoğraf bilgileri").assertIsDisplayed()
        compose.onNodeWithText("Bu alanlar İz’deki kaydı değiştirir; özgün fotoğraf korunur", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Paylaşım ve galeri kopyalarından dosyaya ekli konum ve çekim bilgileri kaldırılır", substring = true)
            .assertIsDisplayed()
        captureDialog("photo-editor-disclosure")
        compose.runOnIdle { assertEquals(null, saved) }

        compose.onNodeWithText("Enlem (isteğe bağlı)").performScrollTo().performTextClearance()
        compose.onNodeWithText("Boylam (isteğe bağlı)").performScrollTo().performTextClearance()
        captureDialog("photo-editor-cleared-coordinates")
        compose.onNodeWithText("Kaydet").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertNotNull("Saving cleared coordinates must emit the edited photo", saved)
            assertEquals(photo.copy(latitude = null, longitude = null), saved)
        }
        assertArrayEquals("Editing database fields must preserve all original file bytes", originalBytes, source.readBytes())
        val originalCoordinates = requireNotNull(ExifInterface(source).latLong)
        assertEquals(12.345678, originalCoordinates[0], 0.00001)
        assertEquals(45.678901, originalCoordinates[1], 0.00001)
    }

    private fun syntheticPhoto(caption: String, colorIndex: Int): Photo {
        val folder = File(context.filesDir, "photos").apply { mkdirs() }
        val file = File(folder, "photo-ui-test-${UUID.randomUUID()}.jpg")
        sources += file
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            val colors = listOf(Color.rgb(36, 110, 184), Color.rgb(56, 150, 84), Color.rgb(190, 100, 36))
            bitmap.eraseColor(colors[colorIndex % colors.size])
            for (y in 16 until 64) for (x in 24 until 96) bitmap.setPixel(x, y, Color.rgb(230, 225, 205))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(file).apply {
            setLatLong(12.345678, 45.678901)
            setAttribute(ExifInterface.TAG_MAKE, "Synthetic UI fixture")
            saveAttributes()
        }
        return Photo(relativePath = "photos/${file.name}", caption = caption)
    }

    private fun captureDialog(name: String) {
        compose.waitForIdle()
        check(evidenceDirectory.isDirectory || evidenceDirectory.mkdirs())
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        try {
            File(evidenceDirectory, "$name.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
