package org.iz.navigation.tracking

import java.io.File

/** Bounded noBackup metadata only. Never accepts fixes, identifiers, exception text or health. */
internal class LocationDiagnostics(private val directory: File) {
    enum class Event { REQUEST, ACKNOWLEDGED, REPLACEMENT_FAILED, INITIAL_FAILED, STOPPED, DISPLAY_FAILED }
    @Synchronized fun record(event: Event, desiredMillis: Long?, currentMillis: Long?) {
        runCatching {
            directory.mkdirs()
            val file = File(directory, "location-events.log")
            val lines = if (file.isFile && file.length() < 64_000) file.readLines().takeLast(127) else emptyList()
            file.writeText((lines + "${System.currentTimeMillis()} ${event.name} desired=${desiredMillis ?: 0} current=${currentMillis ?: 0}").joinToString("\n", postfix = "\n"))
        }
    }
}
