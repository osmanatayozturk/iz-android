package org.iz.navigation.integration

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingGpxStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun newStoreInstanceRecoversXmlAfterPickerRecreatesActivity() {
        val directory = File(temporaryFolder.root, "pending-gpx")
        val xml = """<?xml version="1.0"?><gpx><trk><name>İstanbul</name></trk></gpx>"""
        val id = PendingGpxStore(directory).prepare(xml)
        assertEquals(xml, PendingGpxStore(directory).read(id))
        assertNotEquals(id, PendingGpxStore(directory).prepare(xml))
    }

    @Test fun invalidIdsCannotReadOrRemoveFilesOutsideStore() {
        val outside = temporaryFolder.newFile("private-note.txt").apply { writeText("private") }
        val directory = temporaryFolder.newFolder("pending-gpx")
        val store = PendingGpxStore(directory)
        listOf("../private-note.txt", outside.absolutePath, "1-1-1-1-1", "").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { store.read(id) }
            assertThrows(IllegalArgumentException::class.java) { store.remove(id) }
        }
        assertEquals("private", outside.readText())
    }

    @Test fun blankPayloadIsRejectedWithoutCreatingAnExport() {
        val directory = temporaryFolder.newFolder("pending-gpx")
        val store = PendingGpxStore(directory)
        assertThrows(IllegalArgumentException::class.java) { store.prepare(" \n\t") }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun removingOneExportPreservesOtherPendingExportsAndUnrelatedFiles() {
        val directory = temporaryFolder.newFolder("pending-gpx")
        val unrelated = File(directory, "notes.txt").apply { writeText("keep") }
        val store = PendingGpxStore(directory)
        val first = store.prepare("<gpx>first</gpx>")
        val second = store.prepare("<gpx>second</gpx>")
        store.remove(first)
        assertThrows(IllegalStateException::class.java) { store.read(first) }
        assertEquals("<gpx>second</gpx>", store.read(second))
        assertEquals("keep", unrelated.readText())
        store.remove(first)
    }
}
