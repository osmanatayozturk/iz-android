package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class WatchSurfaceRoute { NAVIGATION, RECORDING, DAILY, SUMMARY }
internal enum class WatchPhoneState { NO_DATA, DISCONNECTED, EXPIRED, CURRENT }
internal data class WatchSurfaceFrame(val route: WatchSurfaceRoute = WatchSurfaceRoute.DAILY,
    val title: String = "İz", val lines: List<String> = emptyList(), val compact: String = "İz’i aç",
    val validUntil: Long? = null, val compactUntil: Long? = null)

/** The phone's report and its GPS fix have separate clocks; rendering never renews either. */
internal object WatchSurfacePolicy {
    fun phoneState(snapshot: WearSnapshot?, connected: Boolean, now: Long): WatchPhoneState = when {
        snapshot == null -> WatchPhoneState.NO_DATA
        !connected -> WatchPhoneState.DISCONNECTED
        !WearProtocol.isFreshSnapshot(snapshot, now) -> WatchPhoneState.EXPIRED
        else -> WatchPhoneState.CURRENT
    }
    fun phoneLabel(snapshot: WearSnapshot?, connected: Boolean, now: Long): String =
        when (phoneState(snapshot, connected, now)) {
            WatchPhoneState.NO_DATA -> "Telefon verisi bekleniyor"
            WatchPhoneState.DISCONNECTED -> "Telefon bağlı değil"
            WatchPhoneState.EXPIRED -> "Telefon bilgisi güncel değil"
            WatchPhoneState.CURRENT -> "Telefon bağlı"
        }
    fun phoneAge(snapshot: WearSnapshot, now: Long) = "Telefon bilgisi: ${age(snapshot.generatedAt, now)}"
    private fun gpsAge(nav: WearNavigationSummary, now: Long) =
        "GPS konumu: ${nav.fixAt?.let { age(it, now) } ?: "ölçüm yok"}"
    private fun age(timestamp: Long, now: Long): String {
        if (timestamp < 0 || now < timestamp) return "zamanı doğrulanıyor"
        val seconds = (now - timestamp) / 1_000
        return when {
            seconds < 60 -> "$seconds sn önce"
            seconds < 3_600 -> "${seconds / 60} dk önce"
            else -> "${seconds / 3_600} sa önce"
        }
    }
    private fun expires(timestamp: Long): Long =
        runCatching { Math.addExact(timestamp, WearProtocol.STATE_TTL_MS) }.getOrDefault(Long.MAX_VALUE)

    fun statisticsDeferred(snapshot: WearSnapshot?): Boolean = snapshot?.let { value ->
        value.statsDeferred || value.recording || value.navigation?.let { it.guidance || it.loading } == true
    } == true
    fun day(snapshot: WearSnapshot?, now: Long): WearDailySummary? = snapshot?.takeUnless(::statisticsDeferred)?.daily?.takeIf {
        now >= it.dayStartAt && now < it.dayEndAt && runCatching {
            Instant.ofEpochMilli(now).atZone(ZoneId.of(it.zoneId)).toLocalDate().toString() == it.localDate
        }.getOrDefault(false)
    }
    fun gpsFresh(nav: WearNavigationSummary, now: Long): Boolean = !nav.gpsStale && !nav.loading &&
        !nav.offRoute && !nav.arrived && !nav.simulation &&
        nav.fixAt?.let { now - it in 0 until WearProtocol.STATE_TTL_MS } == true

    /** Facts are scoped to the phone's current navigation owner, including in-process snapshots. */
    fun ownerAdvisory(snapshot: WearSnapshot?): WearGuidanceAdvisory? = snapshot?.let { s ->
        s.advisory?.takeIf { it.sessionId.isNotBlank() && it.sessionId == s.navigation?.sessionId }
    }
    fun navigationEstimate(snapshot: WearSnapshot?, connected: Boolean, now: Long): Boolean =
        phoneState(snapshot, connected, now) == WatchPhoneState.CURRENT &&
            ownerAdvisory(snapshot)?.paused != true &&
            snapshot?.navigation?.let { it.guidance && gpsFresh(it, now) } == true

    private fun mapStatus(advisory: WearGuidanceAdvisory?): String = when (advisory?.map ?: WearMapAvailability.UNKNOWN) {
        WearMapAvailability.UNKNOWN -> "Harita alanı durumu bilinmiyor."
        WearMapAvailability.NO_PACK -> if (advisory?.preparedRoute == true) "Rota hazır. Harita alanı hazır değil."
            else "Harita alanı hazır değil."
        WearMapAvailability.PARTIAL -> "Harita yalnızca kaydedilmiş alanda görünür."
        WearMapAvailability.READY -> if (advisory?.preparedRoute == true) "Rota ve seçili harita alanı hazır."
            else "Seçili harita alanı hazır."
        WearMapAvailability.OUTSIDE -> "Bu bölüm kaydedilmiş harita alanının dışında."
    }
    private fun voiceStatus(advisory: WearGuidanceAdvisory?): String {
        if (advisory?.muted == true) return "Navigasyon sesi kapalı."
        return when (advisory?.voice ?: WearVoiceAvailability.UNKNOWN) {
            WearVoiceAvailability.UNKNOWN -> "Ses durumu bilinmiyor."
            WearVoiceAvailability.LOCAL_READY -> "Türkçe ses motoru hazır. Sesin duyulduğu henüz doğrulanmadı."
            WearVoiceAvailability.NETWORK_REQUIRED -> "Türkçe seslendirme için internet gerekir."
            WearVoiceAvailability.UNAVAILABLE -> "Türkçe seslendirme kullanılamıyor. Görsel yönlendirme sürüyor."
            WearVoiceAvailability.OUTPUT_FAILED -> "Dönüş anonsu duyurulamadı. Görsel yönlendirme sürüyor."
        }
    }

    fun frame(snapshot: WearSnapshot?, connected: Boolean, now: Long): WatchSurfaceFrame {
        val s = snapshot ?: return WatchSurfaceFrame(
            lines = listOf("Telefon verisi bekleniyor", "Telefonda İz’i kontrol et"))
        val advisory = ownerAdvisory(s)
        val nav = s.navigation?.takeIf { it.guidance || it.loading || it.arrived || it.simulation || it.offRoute || advisory != null }
        val deferred = statisticsDeferred(s)
        val recording = s.journeyId != null
        val route = when {
            nav != null -> WatchSurfaceRoute.NAVIGATION
            recording -> WatchSurfaceRoute.RECORDING
            !deferred && s.completed != null -> WatchSurfaceRoute.SUMMARY
            else -> WatchSurfaceRoute.DAILY
        }
        val title = when (route) {
            WatchSurfaceRoute.NAVIGATION -> "Navigasyon"
            WatchSurfaceRoute.RECORDING -> s.mode?.label() ?: "Kayıt"
            WatchSurfaceRoute.SUMMARY -> "Yolculuk özeti"
            WatchSurfaceRoute.DAILY -> if (deferred) "İstatistikler" else "Bugün"
        }
        // Cached work flags never outrank no-link or expired-report state.
        if (phoneState(s, connected, now) != WatchPhoneState.CURRENT) {
            val lines = buildList {
                add(phoneLabel(s, connected, now))
                add("Son alınan bilgi · ${age(s.generatedAt, now)}")
                val summary = s.completed
                when {
                    nav != null -> {
                        if (nav.destination.isNotBlank()) add(nav.destination)
                        add("Telefonda İz’i kontrol et")
                    }
                    recording -> {
                        add("Son kayıt durumu")
                        add("Hazırlanmış istatistik bekleniyor")
                    }
                    !deferred && summary != null -> {
                        add("Son yolculuk: ${duration(summary.elapsedMillis)} · ${km(summary.distanceMeters)}")
                        add("Adım ${summary.watch?.watchSteps ?: summary.health?.watchSteps ?: summary.stepCount ?: "—"}")
                    }
                    !deferred -> day(s, now)?.let { daily ->
                        add("Son günlük özet · ${daily.samsungSteps?.let { "$it adım" } ?: missing(daily.stepsStatus)}")
                        add("İz kayıtları · ${km(daily.recordedWalkingRunning.distanceMeters)}")
                    }
                }
            }
            return WatchSurfaceFrame(route, title, lines,
                if (recording && nav == null) "Son durum" else "İz’i aç")
        }
        val reportUntil = expires(s.generatedAt)
        if (nav != null) {
            val live = navigationEstimate(s, connected, now)
            val gpsCurrent = !nav.gpsStale &&
                nav.fixAt?.let { now - it in 0 until WearProtocol.STATE_TTL_MS } == true
            val status = when {
                nav.simulation -> "Simülasyon · telefonda"
                nav.arrived -> "Hedefe ulaşıldı"
                nav.loading -> "Rota hazırlanıyor"
                nav.offRoute -> "Rota dışındasın. Yeni rota bekleniyor."
                nav.guidance && !gpsCurrent -> "GPS konumu bekleniyor"
                advisory?.paused == true -> "Duraklama sürüyor. Varış tahmini bekliyor."
                !nav.guidance && advisory?.preparedRoute == true -> "Rota hazır."
                !nav.guidance -> "Telefonda kontrol et"
                else -> nav.instruction.ifBlank { "Rotayı takip et" }
            }
            val eta = nav.arrivalAt?.let { clock(it, s.daily?.zoneId) }
            // A pause suppresses ETA, but it neither loses GPS nor renews its independent expiry.
            val until = if (nav.guidance && gpsFresh(nav, now)) minOf(reportUntil, expires(nav.fixAt!!)) else reportUntil
            return WatchSurfaceFrame(WatchSurfaceRoute.NAVIGATION,
                if (live) "${turn(nav.maneuverType)} ${nav.nextManeuverMeters?.let(::meters) ?: "Rota"}" else "Navigasyon",
                listOf(status, if (live) "${nav.remainingMeters?.let(::km) ?: "—"} · Varış ${eta ?: "—"}" else nav.destination,
                    if (live) nav.destination else "Telefonda kontrol et", phoneAge(s, now), gpsAge(nav, now),
                    mapStatus(advisory), voiceStatus(advisory)),
                if (live && eta != null) eta else "İz’i aç", until, until)
        }
        if (deferred) {
            val lines = if (recording) listOf(
                if (!s.recording) "Telefon kaydı etkin değil" else if (s.temporary) "Algılama sürüyor" else "Kayıt sürüyor",
                "İstatistikler yolculuk", "sonunda hazırlanacak", phoneAge(s, now))
                else listOf(s.statisticsStatus.ifBlank { "İstatistikler hazırlanacak" },
                    "Hazır olunca burada gösterilecek", phoneAge(s, now))
            return WatchSurfaceFrame(route, title, lines, if (recording) "Kayıt" else "Bekliyor",
                reportUntil, reportUntil)
        }
        s.completed?.let { summary ->
            return WatchSurfaceFrame(WatchSurfaceRoute.SUMMARY, "Yolculuk özeti",
                listOf("İstatistikler hazır", "${duration(summary.elapsedMillis)} · ${km(summary.distanceMeters)}",
                    "Ortalama ${summary.averageSpeedKmh?.let { String.format(Locale.forLanguageTag("tr"), "%.1f km/sa", it) } ?: "—"}",
                    "Adım ${summary.watch?.watchSteps ?: summary.health?.watchSteps ?: summary.stepCount ?: "—"}",
                    "Bitiş ${clock(summary.endedAt)}", phoneAge(s, now)), "Hazır", reportUntil, reportUntil)
        }
        val daily = day(s, now)
        val lines = buildList {
            if (daily == null) add("Günlük özet bekleniyor") else {
                add("Araç · ${km(daily.vehicle.distanceMeters)} · ${duration(daily.vehicle.elapsedMillis)}")
                add("Yürü/koş · ${daily.samsungSteps?.let { "$it adım" } ?: missing(daily.stepsStatus)}${if (daily.stepsStatus == WearDailyHealthStatus.PARTIAL) " · kısmi" else ""}")
                add("Egzersiz · ${daily.samsungExerciseMillis?.let(::duration) ?: "—"} · ${daily.samsungExerciseMeters?.let(::km) ?: "—"}")
                add("İz kayıtları · ${km(daily.recordedWalkingRunning.distanceMeters)} · ${duration(daily.recordedWalkingRunning.elapsedMillis)}")
                daily.cycling?.takeIf { it.journeyCount > 0 }?.let { add("Bisiklet · ${km(it.distanceMeters)} · ${duration(it.elapsedMillis)}") }
                add("Samsung Health · ${daily.healthCheckedAt?.let { clock(it, daily.zoneId) } ?: "—"}")
            }
            add(phoneAge(s, now))
        }
        val until = minOf(reportUntil, daily?.dayEndAt ?: reportUntil)
        return WatchSurfaceFrame(WatchSurfaceRoute.DAILY, "Bugün", lines,
            daily?.samsungSteps?.toString() ?: "İz’i aç", until, until)
    }

    /** Numeric ETA is usable only for the same fresh phone report and GPS window as the full surface. */
    fun complication(snapshot: WearSnapshot?, connected: Boolean, now: Long): WatchSurfaceFrame =
        frame(snapshot, connected, now)

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
