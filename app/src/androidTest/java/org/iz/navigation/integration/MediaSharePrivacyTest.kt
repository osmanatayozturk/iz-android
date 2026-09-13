package org.iz.navigation.integration

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.Photo
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSharePrivacyTest {
    private lateinit var context: RecordingContext
    private val sourceFiles = mutableListOf<File>()
    private val exportedUris = mutableSetOf<Uri>()
    private var galleryIdsAtStart = emptySet<Long>()
    private var gallerySnapshotCaptured = false
    private var cacheEntriesAtStart = emptySet<String>()
    private var cacheSnapshotCaptured = false

    @Before
    fun setUp() {
        context = RecordingContext(ApplicationProvider.getApplicationContext())
        galleryIdsAtStart = galleryRows().map { it.id }.toSet()
        gallerySnapshotCaptured = true
        cacheEntriesAtStart = cacheEntries()
        cacheSnapshotCaptured = true
    }

    @After
    fun tearDown() {
        try {
            if (gallerySnapshotCaptured) {
                galleryRows().filterNot { it.id in galleryIdsAtStart }.forEach { row ->
                    context.contentResolver.delete(row.uri, null, null)
                }
            }
            exportedUris.filterNot { it.pathSegments.firstOrNull() == "photos" }.forEach { uri ->
                context.contentResolver.delete(uri, null, null)
            }
        } finally {
            sourceFiles.forEach { it.delete() }
            // Only entries created during this test belong to this cleanup, deepest first.
            if (cacheSnapshotCaptured) {
                (cacheEntries() - cacheEntriesAtStart).sortedByDescending { it.length }.map(::File).forEach { file ->
                    file.delete()
                }
            }
        }
    }

    @Test
    fun sharingPhotoWithoutDatabaseCoordinatesRemovesEmbeddedPrivateMetadata() = runBlocking {
        val source = jpeg(privateMetadata = true)
        val originalBytes = source.readBytes()
        val photo = photo(source)
        assertNull(photo.latitude)
        assertNull(photo.longitude)
        assertNotNull(ExifInterface(source).latLong)

        MediaShare.sharePhotos(context, listOf(photo))

        val streams = sharedStreams()
        assertEquals(1, streams.size)
        val bytes = readBytes(streams.single())
        assertArrayEquals("Sharing must retain the private original", originalBytes, source.readBytes())
        assertPrivateMetadataAbsent(bytes)
        assertNotEquals(sourceUri(source), streams.single())
        assertDecodes(bytes, 32, 24)
    }

    @Test
    fun sharingCleanJpegProducesReadablePhotoAndRetainsOriginal() = runBlocking {
        val source = jpeg(privateMetadata = false)
        val originalBytes = source.readBytes()

        MediaShare.sharePhotos(context, listOf(photo(source)))

        val streams = sharedStreams()
        assertEquals(1, streams.size)
        assertDecodes(readBytes(streams.single()), 32, 24)
        assertArrayEquals(originalBytes, source.readBytes())
    }

    @Test
    fun sharingNormalizesAllEightExifOrientationsWithoutMirroringMistakes() = runBlocking {
        // Expected corner order is hand-derived from EXIF's eight display transforms.
        val corners = listOf(
            listOf(RED, GREEN, BLUE, YELLOW),
            listOf(GREEN, RED, YELLOW, BLUE),
            listOf(YELLOW, BLUE, GREEN, RED),
            listOf(BLUE, YELLOW, RED, GREEN),
            listOf(RED, BLUE, GREEN, YELLOW),
            listOf(BLUE, RED, YELLOW, GREEN),
            listOf(YELLOW, GREEN, BLUE, RED),
            listOf(GREEN, YELLOW, RED, BLUE),
        )
        for (orientation in 1..8) {
            val source = bitmapPhoto(96, 64, Bitmap.CompressFormat.JPEG, "jpg") { x, y ->
                listOf(RED, GREEN, BLUE, YELLOW)[(if (y < 32) 0 else 2) + (if (x < 48) 0 else 1)]
            }
            addPrivateMetadata(source, orientation)
            val originalBytes = source.readBytes()

            MediaShare.sharePhotos(context, listOf(photo(source)))

            val bytes = readBytes(sharedStreams().single())
            assertPrivateMetadataAbsent(bytes)
            val expectedWidth = if (orientation <= 4) 96 else 64
            val expectedHeight = if (orientation <= 4) 64 else 96
            withBitmap(bytes) { bitmap ->
                assertEquals("Orientation $orientation width", expectedWidth, bitmap.width)
                assertEquals("Orientation $orientation height", expectedHeight, bitmap.height)
                val observed = listOf(
                    bitmap.getPixel(bitmap.width / 4, bitmap.height / 4),
                    bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4),
                    bitmap.getPixel(bitmap.width / 4, bitmap.height * 3 / 4),
                    bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4),
                )
                corners[orientation - 1].zip(observed).forEach { (expected, actual) ->
                    assertColorNear("Orientation $orientation", expected, actual, 18)
                }
            }
            assertArrayEquals(originalBytes, source.readBytes())
            context.startedActivities.clear()
        }
    }

    @Test
    fun sharingPngRetainsTransparencyWhileRemovingEmbeddedMetadata() = runBlocking {
        val source = bitmapPhoto(40, 20, Bitmap.CompressFormat.PNG, "png") { x, _ ->
            if (x < 20) Color.TRANSPARENT else Color.argb(128, 70, 120, 210)
        }
        addPrivateMetadata(source)
        assertNotNull("PNG fixture must contain GPS before export", ExifInterface(source).latLong)
        val originalBytes = source.readBytes()

        MediaShare.sharePhotos(context, listOf(photo(source)))

        val bytes = readBytes(sharedStreams().single())
        assertPng(bytes)
        assertPrivateMetadataAbsent(bytes)
        withBitmap(bytes) { bitmap ->
            assertEquals(40, bitmap.width)
            assertEquals(20, bitmap.height)
            assertEquals(0, Color.alpha(bitmap.getPixel(10, 10)))
            assertColorNear("Semitransparent PNG pixel", Color.argb(128, 70, 120, 210), bitmap.getPixel(30, 10), 2)
        }
        assertArrayEquals(originalBytes, source.readBytes())
    }

    @Suppress("DEPRECATION")
    @Test
    fun sharingWebpRemovesEmbeddedMetadataWithoutRawFallback() = runBlocking {
        val source = bitmapPhoto(40, 20, Bitmap.CompressFormat.WEBP, "webp") { _, _ -> GREEN }
        addPrivateMetadata(source)
        assertNotNull("WebP fixture must contain GPS before export", ExifInterface(source).latLong)
        val originalBytes = source.readBytes()

        MediaShare.sharePhotos(context, listOf(photo(source)))

        val bytes = readBytes(sharedStreams().single())
        assertPng(bytes)
        assertPrivateMetadataAbsent(bytes)
        assertDecodes(bytes, 40, 20)
        assertArrayEquals(originalBytes, source.readBytes())
    }

    @Test
    fun sharingUsesImageContentsInsteadOfTheOriginalExtension() = runBlocking {
        val source = bitmapPhoto(48, 32, Bitmap.CompressFormat.JPEG, "data") { _, _ -> BLUE }
        addPrivateMetadata(source)
        val originalBytes = source.readBytes()

        MediaShare.sharePhotos(context, listOf(photo(source)))

        val bytes = readBytes(sharedStreams().single())
        assertPng(bytes)
        assertPrivateMetadataAbsent(bytes)
        assertDecodes(bytes, 48, 32)
        assertArrayEquals(originalBytes, source.readBytes())
    }

    @Test
    fun sharingLargePhotosBoundsBothLongestEdgeAndPixelCountWithoutStretching() = runBlocking {
        for ((width, height) in listOf(5000 to 1000, 3600 to 3000)) {
            val source = bitmapPhoto(width, height, Bitmap.CompressFormat.PNG, "png")
            val originalBytes = source.readBytes()

            MediaShare.sharePhotos(context, listOf(photo(source)))

            withBitmap(readBytes(sharedStreams().single())) { bitmap ->
                assertTrue("Longest edge must fit the export limit", maxOf(bitmap.width, bitmap.height) <= 4096)
                assertTrue("Decoded export must fit the pixel budget", bitmap.width.toLong() * bitmap.height <= 8_000_000L)
                assertTrue(bitmap.width <= width && bitmap.height <= height)
                val ratioError = abs(bitmap.width.toDouble() / bitmap.height - width.toDouble() / height)
                assertTrue("Downsampling must preserve aspect ratio within pixel rounding", ratioError < 0.01)
                assertTrue("Valid photos must retain visible pixels", bitmap.width > 0 && bitmap.height > 0)
            }
            assertArrayEquals(originalBytes, source.readBytes())
            context.startedActivities.clear()
        }
    }

    @Test
    fun sharingSeveralPhotosKeepsInputOrderAndGrantsOnlyPreparedUris() = runBlocking {
        val sources = listOf(RED, GREEN, BLUE).map { color ->
            bitmapPhoto(32, 24, Bitmap.CompressFormat.PNG, "png") { _, _ -> color }
                .also { addPrivateMetadata(it) }
        }
        val originals = sources.map { it.readBytes() }

        MediaShare.sharePhotos(context, sources.map(::photo))

        val streams = sharedStreams()
        assertEquals(3, streams.size)
        assertEquals(3, streams.toSet().size)
        streams.forEachIndexed { index, uri ->
            assertEquals("content", uri.scheme)
            assertFalse("Private source path must never be granted", uri.pathSegments.firstOrNull() == "photos")
            assertNotEquals(sourceUri(sources[index]), uri)
            val bytes = readBytes(uri)
            assertPng(bytes)
            assertPrivateMetadataAbsent(bytes)
            withBitmap(bytes) { bitmap ->
                assertColorNear("Input order", listOf(RED, GREEN, BLUE)[index], bitmap.getPixel(16, 12), 2)
            }
            assertArrayEquals(originals[index], sources[index].readBytes())
        }
    }

    @Test
    fun laterSuccessfulShareKeepsEarlierSharedBytesReadable() = runBlocking {
        val source = jpeg(privateMetadata = true)
        val originalBytes = source.readBytes()
        MediaShare.sharePhotos(context, listOf(photo(source)))
        val firstUri = sharedStreams().single()
        val firstBytes = readBytes(firstUri)
        context.startedActivities.clear()

        MediaShare.sharePhotos(context, listOf(photo(source)))

        val secondUri = sharedStreams().single()
        assertNotEquals("Each share must have an independent prepared copy", firstUri, secondUri)
        assertArrayEquals("A new share must not invalidate an earlier recipient's URI", firstBytes, readBytes(firstUri))
        assertPrivateMetadataAbsent(readBytes(secondUri))
        assertArrayEquals(originalBytes, source.readBytes())
    }

    @Test
    fun shareLaunchFailureRemovesOnlyItsPreparedBatch() = runBlocking {
        val source = jpeg(privateMetadata = true)
        val originalBytes = source.readBytes()
        MediaShare.sharePhotos(context, listOf(photo(source)))
        val earlierUri = sharedStreams().single()
        val earlierBytes = readBytes(earlierUri)
        val before = cacheEntries()
        context.startedActivities.clear()
        context.launchFailure = ActivityNotFoundException("Synthetic unavailable share receiver")

        expectFailure { MediaShare.sharePhotos(context, listOf(photo(source))) }

        assertTrue(context.startedActivities.isEmpty())
        assertEquals("Failed chooser launch must remove its prepared cache", before, cacheEntries())
        assertArrayEquals(earlierBytes, readBytes(earlierUri))
        assertArrayEquals(originalBytes, source.readBytes())
    }

    @Test
    fun sharingInvalidLaterPhotoHasNoChooserOrPartialPreparedBatch() = runBlocking {
        val first = jpeg(privateMetadata = true)
        val firstBytes = first.readBytes()
        for (invalid in invalidPhotos()) {
            val before = cacheEntries()
            val invalidFile = File(context.filesDir, invalid.relativePath)
            val invalidBytes = invalidFile.takeIf { it.isFile }?.readBytes()

            expectFailure { MediaShare.sharePhotos(context, listOf(photo(first), invalid)) }

            assertTrue("A partly prepared batch must never open a chooser", context.startedActivities.isEmpty())
            assertEquals("Failed preparation must remove its batch", before, cacheEntries())
            assertArrayEquals(firstBytes, first.readBytes())
            if (invalidBytes != null) assertArrayEquals(invalidBytes, invalidFile.readBytes())
        }
    }

    @Test
    fun emptyPhotoSelectionsHaveNoExternalSideEffects() = runBlocking {
        val beforeCache = cacheEntries()
        val beforeRows = galleryRows().map { it.id }.toSet()

        expectFailure { MediaShare.sharePhotos(context, emptyList()) }
        expectFailure { MediaShare.savePhotosToGallery(context, emptyList()) }

        assertTrue(context.startedActivities.isEmpty())
        assertEquals(beforeCache, cacheEntries())
        assertEquals(beforeRows, galleryRows().map { it.id }.toSet())
    }

    @Test
    fun sharingOversizedSourceRejectsBeforeCreatingAChooserOrRetainingCache() = runBlocking {
        val source = jpeg(privateMetadata = true)
        RandomAccessFile(source, "rw").use { it.setLength(50L * 1024 * 1024 + 1) }
        val originalLength = source.length()
        val originalDigest = fileDigest(source)
        val before = cacheEntries()

        expectFailure { MediaShare.sharePhotos(context, listOf(photo(source))) }

        assertTrue(context.startedActivities.isEmpty())
        assertEquals(before, cacheEntries())
        assertEquals(originalLength, source.length())
        assertArrayEquals(originalDigest, fileDigest(source))
    }

    @Test
    fun galleryPrivacyOracleReadsGpsFromAnOwnedUnsanitizedControl() {
        val source = jpeg(privateMetadata = true)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "photo-export-oracle-${UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/IzPhotoExportTests")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(source.readBytes()) }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null)

        val observed = ExifInterface(ByteArrayInputStream(readGalleryOriginalBytes(uri)))

        assertNotNull("The gallery oracle must observe GPS, not MediaStore's redacted view", observed.latLong)
        assertEquals(12.345678, observed.latLong!![0], 0.00001)
        assertEquals(45.678901, observed.latLong!![1], 0.00001)
        assertEquals("Synthetic Camera Maker", observed.getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun galleryExportRemovesPrivateBytesCaptureTimeAndOriginalFilename() = runBlocking {
        val source = jpeg(privateMetadata = true)
        val originalBytes = source.readBytes()
        val photo = photo(source).copy(takenAt = 1_577_934_245_000L)
        assertNull(photo.latitude)
        assertNull(photo.longitude)
        val before = galleryRows().map { it.id }.toSet()
        val beforeCache = cacheEntries()

        assertEquals(1, MediaShare.savePhotosToGallery(context, listOf(photo)))

        val row = galleryRows().filterNot { it.id in before }.single()
        assertEquals("image/png", row.mimeType)
        assertEquals(0, row.pending)
        assertFalse("Gallery names must not disclose the original filename", row.name.contains(source.nameWithoutExtension))
        assertNotEquals("Gallery must not copy the original capture time", photo.takenAt, row.takenAt)
        val bytes = readGalleryOriginalBytes(row.uri)
        assertPng(bytes)
        assertPrivateMetadataAbsent(bytes)
        assertDecodes(bytes, 32, 24)
        assertArrayEquals(originalBytes, source.readBytes())
        assertEquals("Gallery completion must dispose of preparation cache", beforeCache, cacheEntries())
        assertTrue(context.startedActivities.isEmpty())
    }

    @Test
    fun galleryExportsCleanPhotosAndPreservesTheirOrderAndOriginals() = runBlocking {
        val sources = listOf(RED, BLUE).map { color ->
            bitmapPhoto(32, 24, Bitmap.CompressFormat.PNG, "png") { _, _ -> color }
        }
        val originals = sources.map { it.readBytes() }
        val before = galleryRows().map { it.id }.toSet()
        val beforeCache = cacheEntries()

        assertEquals(2, MediaShare.savePhotosToGallery(context, sources.map(::photo)))

        val rows = galleryRows().filterNot { it.id in before }.sortedBy { it.id }
        assertEquals(2, rows.size)
        rows.forEachIndexed { index, row ->
            assertEquals("image/png", row.mimeType)
            assertEquals(0, row.pending)
            val bytes = readGalleryOriginalBytes(row.uri)
            assertPng(bytes)
            withBitmap(bytes) { bitmap ->
                assertColorNear("Gallery input order", listOf(RED, BLUE)[index], bitmap.getPixel(16, 12), 2)
            }
            assertArrayEquals(originals[index], sources[index].readBytes())
        }
        assertEquals(beforeCache, cacheEntries())
    }

    @Test
    fun galleryInvalidLaterPhotoPublishesNoRowsAndRetainsNoPreparedBatch() = runBlocking {
        val first = jpeg(privateMetadata = true)
        val firstBytes = first.readBytes()
        for (invalid in invalidPhotos()) {
            val beforeRows = galleryRows().map { it.id }.toSet()
            val beforeCache = cacheEntries()
            val invalidFile = File(context.filesDir, invalid.relativePath)
            val invalidBytes = invalidFile.takeIf { it.isFile }?.readBytes()

            expectFailure { MediaShare.savePhotosToGallery(context, listOf(photo(first), invalid)) }

            assertEquals("Rejected batch must leave no gallery row, including pending rows", beforeRows, galleryRows().map { it.id }.toSet())
            assertEquals(beforeCache, cacheEntries())
            assertTrue(context.startedActivities.isEmpty())
            assertArrayEquals(firstBytes, first.readBytes())
            if (invalidBytes != null) assertArrayEquals(invalidBytes, invalidFile.readBytes())
        }
    }

    private fun jpeg(privateMetadata: Boolean): File {
        val source = bitmapPhoto(32, 24, Bitmap.CompressFormat.JPEG, "jpg")
        if (privateMetadata) {
            addPrivateMetadata(source)
            val comment = "SyntheticJpegCommentMarker".toByteArray()
            val bytes = source.readBytes()
            source.writeBytes(bytes.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, (comment.size + 2).toByte()) + comment + bytes.copyOfRange(2, bytes.size))
            source.appendBytes("SyntheticTrailingMetadataMarker".toByteArray())
        }
        return source
    }

    private fun bitmapPhoto(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
        extension: String,
        pixel: ((Int, Int) -> Int)? = null,
    ): File {
        val source = newSource(extension)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            if (pixel == null) bitmap.eraseColor(Color.rgb(36, 110, 184))
            else for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, pixel(x, y))
            source.outputStream().use { output ->
                check(bitmap.compress(format, 95, output))
            }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    private fun newSource(extension: String): File {
        val folder = File(context.filesDir, "photos").apply { mkdirs() }
        return File(folder, "photo-export-test-${UUID.randomUUID()}.$extension").also { sourceFiles += it }
    }

    private fun addPrivateMetadata(source: File, orientation: Int = ExifInterface.ORIENTATION_NORMAL) {
        ExifInterface(source).apply {
            setLatLong(12.345678, 45.678901)
            setAttribute(ExifInterface.TAG_MAKE, "Synthetic Camera Maker")
            setAttribute(ExifInterface.TAG_MODEL, "Synthetic Camera Model")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
            setAttribute(ExifInterface.TAG_USER_COMMENT, "Synthetic private comment")
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_XMP, """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" dc:description="SyntheticXmpMarker" /></rdf:RDF></x:xmpmeta>""")
            saveAttributes()
        }
    }

    private fun invalidPhotos(): List<Photo> {
        val malformed = newSource("jpg").apply { writeBytes("Synthetic broken image".toByteArray()) }
        val truncated = jpeg(privateMetadata = false).apply {
            val bytes = readBytes()
            writeBytes(bytes.copyOf(bytes.size / 2))
        }
        val unsupported = newSource("svg").apply {
            writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"32\" height=\"24\"/>")
        }
        val animated = newSource("gif").apply { writeBytes(animatedGif()) }
        val animation = ImageDecoder.decodeDrawable(ImageDecoder.createSource(animated))
        assertTrue("Animation rejection requires a valid multi-frame fixture", animation is AnimatedImageDrawable)
        (animation as AnimatedImageDrawable).stop()
        val outside = File(context.filesDir, "photo-export-test-${UUID.randomUUID()}.jpg")
        sourceFiles += outside
        outside.writeBytes(jpeg(privateMetadata = false).readBytes())
        return listOf(
            Photo(relativePath = "photos/missing-${UUID.randomUUID()}.jpg"),
            photo(malformed),
            photo(truncated),
            photo(unsupported),
            photo(animated),
            Photo(relativePath = "photos/../${outside.name}"),
        )
    }

    private fun animatedGif(): ByteArray {
        // Two synthetic 1x1 frames, black then white; no fixture download or personal media.
        val header = byteArrayOf(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, -1, -1, -1)
        val frame = byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0)
        val secondFrame = frame.copyOf().apply { this[20] = 0x4c }
        return header + frame + secondFrame + byteArrayOf(0x3b)
    }

    private fun photo(file: File) = Photo(relativePath = "photos/${file.name}")

    private fun sourceUri(file: File): Uri =
        Uri.parse("content://${context.packageName}.files/photos/${file.name}")

    @Suppress("DEPRECATION")
    private fun sharedStreams(): List<Uri> {
        assertEquals("Exactly one share chooser should open", 1, context.startedActivities.size)
        val chooser = context.startedActivities.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertReadOnlyGrant(chooser)
        val share = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("Chooser must contain the share intent", share)
        share!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, share.action)
        assertReadOnlyGrant(share)
        val streams = share.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("Share intent must contain photo streams", streams)
        streams!!
        exportedUris += streams
        val clip = share.clipData
        assertNotNull("Each shared URI must also be present in ClipData", clip)
        clip!!
        assertEquals(streams.size, clip.itemCount)
        streams.forEachIndexed { index, uri -> assertEquals(uri, clip.getItemAt(index).uri) }
        return streams
    }

    private fun assertReadOnlyGrant(intent: Intent) {
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val unsafeGrants = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        assertEquals("Photo exports must grant read access only", 0, intent.flags and unsafeGrants)
    }

    private fun readBytes(uri: Uri): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }

    private fun readGalleryOriginalBytes(uri: Uri): ByteArray =
        readBytes(MediaStore.setRequireOriginal(uri))

    private fun assertPrivateMetadataAbsent(bytes: ByteArray) {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertNull("Exported pixels must not include the original GPS location", exif.latLong)
        listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_XMP,
        ).forEach { tag -> assertNull("Export must remove $tag", exif.getAttribute(tag)) }
        val raw = bytes.toString(Charsets.ISO_8859_1)
        listOf(
            "Synthetic Camera Maker", "Synthetic Camera Model", "Synthetic private comment",
            "2020:01:02 03:04:05", "SyntheticXmpMarker", "SyntheticJpegCommentMarker",
            "SyntheticTrailingMetadataMarker",
        ).forEach { marker -> assertFalse("Export bytes must not retain source marker $marker", raw.contains(marker)) }
    }

    private fun assertPng(bytes: ByteArray) {
        assertTrue("Prepared photo must contain an image", bytes.size >= 8)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), bytes.copyOf(8))
    }

    private fun assertDecodes(bytes: ByteArray, width: Int, height: Int) {
        withBitmap(bytes) { bitmap ->
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
        }
    }

    private fun withBitmap(bytes: ByteArray, assertion: (Bitmap) -> Unit) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Export must remain a readable image", bitmap)
        bitmap!!
        try {
            assertion(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColorNear(message: String, expected: Int, actual: Int, tolerance: Int) {
        listOf(
            Color.alpha(expected) to Color.alpha(actual), Color.red(expected) to Color.red(actual),
            Color.green(expected) to Color.green(actual), Color.blue(expected) to Color.blue(actual),
        ).forEach { (wanted, observed) ->
            assertTrue("$message: expected $expected, observed $actual", abs(wanted - observed) <= tolerance)
        }
    }

    private suspend fun expectFailure(action: suspend () -> Unit) {
        var failure: Exception? = null
        try {
            action()
        } catch (error: Exception) {
            failure = error
        }
        assertNotNull("Invalid export must fail instead of falling back to original bytes", failure)
    }

    private fun fileDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
    private fun cacheEntries(): Set<String> = File(context.cacheDir, "shared-photos")
        .takeIf { it.exists() }?.walkTopDown()?.drop(1)?.map { it.absolutePath }?.toSet().orEmpty()

    @Suppress("DEPRECATION")
    private fun galleryRows(): List<GalleryRow> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.IS_PENDING,
        )
        val rows = mutableListOf<GalleryRow>()
        requireNotNull(context.contentResolver.query(
            MediaStore.setIncludePending(MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
            projection,
            "${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ?",
            arrayOf(context.packageName),
            "${MediaStore.Images.Media._ID} ASC",
        )) { "Cannot inspect owned gallery rows" }.use { cursor ->
            while (cursor.moveToNext()) {
                rows += GalleryRow(
                    id = cursor.getLong(0), name = cursor.getString(1), mimeType = cursor.getString(2),
                    takenAt = if (cursor.isNull(3)) null else cursor.getLong(3), pending = cursor.getInt(4),
                )
            }
        }
        return rows
    }

    private data class GalleryRow(val id: Long, val name: String, val mimeType: String, val takenAt: Long?, val pending: Int) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val startedActivities = mutableListOf<Intent>()
        var launchFailure: ActivityNotFoundException? = null

        override fun startActivity(intent: Intent) = recordLaunch(intent)

        override fun startActivity(intent: Intent, options: Bundle?) = recordLaunch(intent)

        private fun recordLaunch(intent: Intent) {
            launchFailure?.let { throw it }
            startedActivities += Intent(intent)
        }
    }

    companion object {
        private val RED = Color.rgb(220, 40, 40)
        private val GREEN = Color.rgb(40, 200, 50)
        private val BLUE = Color.rgb(35, 70, 220)
        private val YELLOW = Color.rgb(225, 205, 35)
    }
}
