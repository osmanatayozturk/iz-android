package org.iz.navigation.watch

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import org.iz.navigation.wearprotocol.*

internal fun ScalingLazyListScope.dailyDetails(daily: WearDailySummary) {
    item { Note("Samsung Health · " + daily.localDate + if (daily.stepsStatus == WearDailyHealthStatus.PARTIAL) " · Kısmi veri" else "") }
    item { Note("Araç · ${WatchSurfacePolicy.km(daily.vehicle.distanceMeters)} · ${duration(daily.vehicle.elapsedMillis)}") }
    item { Note("Yürüyüş ve koşu · İz kayıtları\n${WatchSurfacePolicy.km(daily.recordedWalkingRunning.distanceMeters)} · ${duration(daily.recordedWalkingRunning.elapsedMillis)}") }
    daily.cycling?.takeIf { it.journeyCount > 0 }?.let { item { Note("Bisiklet · ${WatchSurfacePolicy.km(it.distanceMeters)} · ${duration(it.elapsedMillis)}") } }
    item { Note("Samsung Health · Egzersiz\n${daily.samsungExerciseMillis?.let(::duration) ?: WatchSurfacePolicy.missing(daily.exerciseStatus)} · ${daily.samsungExerciseMeters?.let(WatchSurfacePolicy::km) ?: WatchSurfacePolicy.missing(daily.distanceStatus)}") }
    daily.healthCheckedAt?.let { item { Note("Sağlık kontrolü ${WatchSurfacePolicy.clock(it, daily.zoneId)}") } }
}

internal fun ScalingLazyListScope.completedDetails(summary: WearCompletedSummary) {
    item { WatchValue(WatchSurfacePolicy.km(summary.distanceMeters)) }
    item { Note("${summary.mode?.label() ?: "Yolculuk"} · ${duration(summary.elapsedMillis)} · Bitiş ${WatchSurfacePolicy.clock(summary.endedAt)}") }
    summary.averageSpeedKmh?.let { item { Note("Ortalama hız ${speed(it)}") } }
    summary.maxSpeedKmh?.let { item { Note("En yüksek hız ${speed(it)}") } }
    summary.averagePaceSecondsPerKm?.let { item { Note("Ortalama tempo ${pace(it)}") } }
    summary.stepCount?.let { item { Note("Telefon adımları $it") } }
    summary.watch?.let { healthDetails("İz · Saat kaydı", it) }
    summary.health?.let {
        healthDetails("Samsung Health · Saat", it)
        it.totalCaloriesKcal?.let { energy -> item { Note("Toplam enerji ${energy.toInt()} kcal · Dinlenme dahil") } }
        item { Note("Samsung Health aktarımı gecikebilir.") }
    }
}

private fun ScalingLazyListScope.healthDetails(source: String, health: WearHealthSummary) {
    item { Note(source, highlighted = true) }
    item { Note("Ortalama nabız " + (health.heartRateMeanBpm?.let { "${it.toInt()} atım/dk" } ?: "ölçüm yok")) }
    item { Note("Adım " + (health.watchSteps?.toString() ?: "ölçüm yok")) }
    if (health.partial) item { Note("Kısmi sağlık verisi") }
}

private fun speed(value: Double) = String.format(java.util.Locale.forLanguageTag("tr"), "%.1f km/sa", value)

@Composable
internal fun WatchSurfaceDetail(data: WatchSurfaceData, route: WatchSurfaceRoute, now: Long, onControls: () -> Unit) {
    WatchScreen(state = WatchUiState(phoneId = data.phoneId.takeIf { data.connected }, snapshot = data.snapshot,
        phoneVersion = data.version), now = now, onStart = {}, onStop = {}, onRefresh = {}, surfaceData = data,
        entry = route, onOpenSettings = onControls)
}
