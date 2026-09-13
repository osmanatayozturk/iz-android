package org.iz.navigation.gpx

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A process-death marker, never GPX geometry or an automatically restored session. */
open class TrackFollowSessionStore(private val directory: File) {
    private val marker get() = File(directory, "track-follow.active")
    private val temporary get() = File(directory, "track-follow.active.tmp")

    @Synchronized open fun read(): Boolean = marker.isFile

    @Synchronized open fun write() {
        check(directory.isDirectory || directory.mkdirs()) { "GPX kesilme bilgisi saklanamadı." }
        try {
            FileOutputStream(temporary).use { it.write(byteArrayOf(1)); it.fd.sync() }
            Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    @Synchronized open fun clear() {
        Files.deleteIfExists(marker.toPath())
        Files.deleteIfExists(temporary.toPath())
    }
}
