package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class WatchSurfaceRoute { NAVIGATION, RECORDING, DAILY }
internal data class WatchSurfaceFrame(val route: WatchSurfaceRoute = WatchSurfaceRoute.DAILY,
    val title: String = "İz", val lines: List<String> = emptyList(), val compact: String = "İz’i aç",
    val validUntil: Long? = null, val compactUntil: Long? = null)

/** Pure presentation policy. All durations are measured values, never disconnected extrapolations. */
internal object WatchSurfacePolicy {
    const val ACTIVE_EXPIRY = 300_000L
    fun day(snapshot: WearSnapshot?, now: Long): WearDailySummary? = snapshot?.daily?.takeIf {
        now >= it.dayStartAt && now < it.dayEndAt && runCatching {
            Instant.ofEpochMilli(now).atZone(ZoneId.of(it.zoneId)).toLocalDate().toString() == it.localDate
        }.getOrDefault(false)
    }
    fun gpsFresh(nav: WearNavigationSummary, now: Long): Boolean = !nav.gpsStale && !nav.loading &&
        !nav.offRoute && !nav.arrived && !nav.simulation && nav.fixAt?.let { now - it in 0 until 30_000 } == true
    fun frame(snapshot: WearSnapshot?, connected: Boolean, now: Long): WatchSurfaceFrame {
        val s = snapshot ?: return WatchSurfaceFrame(lines = listOf("Telefon verisi bekleniyor", "Ayrıntılar için dokun"))
        val nav = s.navigation?.takeIf { it.guidance }
        if (nav != null) {
            val live = connected && gpsFresh(nav, now) && WearProtocol.isFreshSnapshot(s, now)
            val status = when {
                nav.simulation -> "Simülasyon · telefonda"
                nav.arrived -> "Hedefe ulaşıldı"
                nav.loading -> "Rota hazırlanıyor"
                nav.offRoute -> "Rota yeniden hesaplanıyor"
                !connected -> "Telefon bağlı değil"
                !live -> "GPS güncel değil"
                else -> nav.instruction.ifBlank { "Rotayı takip et" }
            }
            val eta = nav.arrivalAt?.let { clock(it, s.daily?.zoneId) }
            return WatchSurfaceFrame(WatchSurfaceRoute.NAVIGATION,
                if (live) "${turn(nav.maneuverType)} ${nav.nextManeuverMeters?.let(::meters) ?: "Rota"}" else "Navigasyon",
                listOf(status, if (live) "${nav.remainingMeters?.let(::km) ?: "—"} · Varış ${eta ?: "—"}" else nav.destination,
                    if (live) nav.destination else "Telefonda kontrol et"),
                if (live && eta != null) eta else "İz’i aç",
                if (live) minOf(nav.fixAt!! + 30_000, s.generatedAt + WearProtocol.STATE_TTL_MS + 1) else null,
                if (live && eta != null) s.generatedAt + ACTIVE_EXPIRY else null)
        }
        if (s.recording && s.journeyId != null && !s.temporary) {
            val valid = now - s.generatedAt in 0 until ACTIVE_EXPIRY
            val health = s.health
            return WatchSurfaceFrame(WatchSurfaceRoute.RECORDING, s.mode?.label() ?: "Kayıt",
                if (valid) listOf("${duration(s.elapsedMillis)} · ${km(s.distanceMeters)}",
                    "Nabız — · Adım ${health?.watchSteps ?: s.stepCount ?: "—"}",
                    "Enerji ${health?.totalCaloriesKcal?.let { "${it.toInt()} kcal" } ?: "—"} · gecikmeli",
                    if (connected) "Son kayıt ölçümü" else "Telefon bağlı değil · son ölçüm")
                else listOf("Kayıt verisi güncel değil", "İz’i aç"),
                if (valid) duration(s.elapsedMillis) else "İz’i aç",
                if (valid) s.generatedAt + ACTIVE_EXPIRY else null,
                if (valid) s.generatedAt + ACTIVE_EXPIRY else null)
        }
        val daily = day(s, now)
        val lines = buildList {
            if (daily == null) add("Günlük özet bekleniyor") else {
                add("Araç · ${km(daily.vehicle.distanceMeters)} · ${duration(daily.vehicle.elapsedMillis)}")
                add("Yürü/koş · ${daily.samsungSteps?.let { "$it adım" } ?: missing(daily.stepsStatus)}${if (daily.stepsStatus == WearDailyHealthStatus.PARTIAL) " · kısmi" else ""}")
                add("Egzersiz · ${daily.samsungExerciseMillis?.let(::duration) ?: "—"} · ${daily.samsungExerciseMeters?.let(::km) ?: "—"}")
                add("İz kayıtları · ${km(daily.recordedWalkingRunning.distanceMeters)} · ${duration(daily.recordedWalkingRunning.elapsedMillis)}")
                daily.cycling?.takeIf { it.journeyCount > 0 }?.let { add("Bisiklet · ${km(it.distanceMeters)} · ${duration(it.elapsedMillis)}") }
            }
            if (daily != null) add("Samsung Health · ${daily.healthCheckedAt?.let { clock(it, daily.zoneId) } ?: "—"}")
            if (s.temporary) add("Algılama sürüyor · kayıt değil")
        }
        return WatchSurfaceFrame(WatchSurfaceRoute.DAILY, "Bugün", lines,
            daily?.samsungSteps?.toString() ?: "İz’i aç", daily?.dayEndAt, daily?.dayEndAt)
    }
    /** Frozen advisory ETA from an originally fresh phone report, never a running countdown. */
    fun complication(snapshot: WearSnapshot?, connected: Boolean, now: Long): WatchSurfaceFrame {
        val frame = frame(snapshot, connected, now)
        val s = snapshot ?: return frame
        val nav = s.navigation?.takeIf { it.guidance } ?: return frame
        val knownGood = gpsFresh(nav, s.generatedAt) && now - s.generatedAt in 0 until ACTIVE_EXPIRY
        return if (knownGood && nav.arrivalAt != null) frame.copy(compact = clock(nav.arrivalAt!!, s.daily?.zoneId), compactUntil = s.generatedAt + ACTIVE_EXPIRY)
            else frame.copy(compact = "İz’i aç", compactUntil = null)
    }
    fun km(value: Double) = String.format(Locale.forLanguageTag("tr"), "%.1f km", value / 1000)
    fun meters(value: Double) = if (value >= 1000) km(value) else "${value.toInt()} m"
    fun clock(value: Long, zone: String? = null): String = Instant.ofEpochMilli(value)
        .atZone(runCatching { ZoneId.of(zone ?: "") }.getOrDefault(ZoneId.systemDefault()))
        .format(DateTimeFormatter.ofPattern("HH:mm"))
    fun missing(status: WearDailyHealthStatus) = when (status) {
        WearDailyHealthStatus.PERMISSION_REQUIRED -> "izin gerekli"
        WearDailyHealthStatus.PARTIAL -> "kısmi veri"
        else -> "ölçüm yok"
    }
    // Valhalla maneuver type values shared by the phone providers; unknowns retain a neutral marker.
    fun turn(type: Int?): String = when (type) {
        1, 2, 3, 7, 8, 17, 22 -> "↑"; 9, 18, 20, 23, 37 -> "↗"; 10 -> "→"; 11 -> "↘"
        14 -> "↙"; 15 -> "←"; 16, 19, 21, 24, 38 -> "↖"; 12, 13 -> "↶"; 26, 27 -> "⟳"; 4, 5, 6 -> "⚑"
        else -> "◆"
    }
}
