package org.iz.navigation.health

import org.iz.navigation.data.DailyActivityDay
import org.iz.navigation.wearprotocol.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Health-only cache. Production callers always supply a file under Context.noBackupFilesDir. */
internal class DailyActivityCache(private val file: File) {
    fun read(day: DailyActivityDay, now: Long): WearDailySummary? = runCatching {
        if (!file.isFile || file.length() > 32_768) return null
        WearProtocol.decodeSnapshot(file.readBytes())?.daily?.takeIf {
            it.localDate == day.localDate && it.zoneId == day.zoneId && it.dayStartAt == day.startAt && it.dayEndAt == day.endAt &&
                now in day.startAt until day.endAt && it.checkedAt <= now && (it.healthCheckedAt?.let { checked -> checked <= now } != false)
        }
    }.getOrNull()
    fun write(summary: WearDailySummary) {
        val healthOnly = summary.copy(vehicle = WearDailyJourneyTotal(), recordedWalkingRunning = WearDailyJourneyTotal(), cycling = null)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(WearProtocol.encodeSnapshot(WearSnapshot(generatedAt = summary.checkedAt, daily = healthOnly)))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    fun clear() { file.delete(); File(file.parentFile, file.name + ".tmp").delete() }
}
