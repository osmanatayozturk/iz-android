package org.iz.navigation.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.iz.navigation.gpx.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GpxImportControllerTest {
    private fun track(name: String) = ImportedTrack(name = name, segments = emptyList())
    @Test fun standardGpx11ImportPublishesPreviewWithoutError() = runTest {
        val controller = GpxImportController(backgroundScope)
        val xml = """<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
            <trk><name>Standart örnek</name><trkseg>
            <trkpt lat="41" lon="29"/><trkpt lat="41.001" lon="29.001"/>
            </trkseg></trk></gpx>"""
        controller.read { GpxImporter.read(xml.byteInputStream()) }
        runCurrent()
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.message)
        val segment = requireNotNull(controller.state.value.track).segments.single()
        assertEquals("Standart örnek", segment.trackName)
        assertEquals(2, segment.points.size)
    }
    @Test fun delayedOldImportCannotReplaceSavedTrackSelectedLater() = runTest {
        val controller = GpxImportController(backgroundScope)
        val release = CompletableDeferred<Unit>()
        controller.read { withContext(NonCancellable) { release.await(); track("Eski") } }
        runCurrent()
        assertTrue(controller.state.value.loading)
        val selected = track("Kayıtlı")
        controller.show(selected)
        release.complete(Unit); runCurrent()
        assertEquals(selected, controller.state.value.track)
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.message)
    }
    @Test fun successfulImportPublishesOnlyCompletedGeometry() = runTest {
        val controller = GpxImportController(backgroundScope)
        val old = track("Önceki"); val next = track("Yeni")
        controller.show(old)
        val release = CompletableDeferred<Unit>()
        controller.read { release.await(); next }; runCurrent()
        assertEquals(old, controller.state.value.track)
        assertTrue(controller.state.value.loading)
        release.complete(Unit); runCurrent()
        assertEquals(next, controller.state.value.track)
        assertFalse(controller.state.value.loading)
    }
    @Test fun providerErrorDoesNotExposeUriAndPreservesExistingPreview() = runTest {
        val controller = GpxImportController(backgroundScope)
        val old = track("Önceki"); controller.show(old)
        controller.read { throw java.io.IOException("content://private-location/secret") }; runCurrent()
        assertEquals(old, controller.state.value.track)
        assertFalse(controller.state.value.loading)
        assertNotNull(controller.state.value.message)
        assertFalse(controller.state.value.message!!.contains("private-location"))
    }
}
