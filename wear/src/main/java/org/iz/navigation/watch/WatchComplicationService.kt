package org.iz.navigation.watch

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.*
import java.time.Instant

class WatchComplicationService : SuspendingTimelineComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline {
        val data = WatchSurfaceReader.read(this)
        val now = System.currentTimeMillis()
        val frame = WatchSurfacePolicy.complication(data.snapshot, data.connected, now)
        val fallback = complication(request.complicationType, "İz’i aç", "İz", frame.route, "Güncel ayrıntılar için İz’i aç")
        val until = frame.compactUntil
        val sourceAt = if (frame.route == WatchSurfaceRoute.DAILY) data.snapshot?.daily?.healthCheckedAt else data.snapshot?.generatedAt
        val sourceLabel = if (frame.route == WatchSurfaceRoute.DAILY) "Samsung Health" else "Son bildirilen ölçüm"
        val partial = if (frame.route == WatchSurfaceRoute.DAILY && data.snapshot?.daily?.stepsStatus == org.iz.navigation.wearprotocol.WearDailyHealthStatus.PARTIAL) "Kısmi veri." else ""
        val active = if (until != null && until > now && frame.compact != "İz’i aç") listOf(
            TimelineEntry(TimeInterval(Instant.ofEpochMilli(now), Instant.ofEpochMilli(until)),
                complication(request.complicationType, frame.compact,
                    when (frame.route) { WatchSurfaceRoute.NAVIGATION -> "Varış"; WatchSurfaceRoute.RECORDING -> "Son süre"; else -> "Adım" },
                    frame.route, "${frame.title}. ${frame.compact}. $sourceLabel. $partial Kontrol ${sourceAt?.let { WatchSurfacePolicy.clock(it, data.snapshot?.daily?.zoneId) } ?: "—"}")))
            else emptyList()
        return ComplicationDataTimeline(fallback, active)
    }
    override fun getPreviewData(type: ComplicationType): ComplicationData = complication(type, "4200", "Adım",
        WatchSurfaceRoute.DAILY, "Bugün Samsung Health 4200 adım")
    private fun complication(type: ComplicationType, value: String, title: String, route: WatchSurfaceRoute, description: String): ComplicationData {
        val tap = PendingIntent.getActivity(this, 500 + route.ordinal,
            Intent(this, WatchActivity::class.java).putExtra("surface", route.name)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = PlainComplicationText.Builder(value).build()
        val desc = PlainComplicationText.Builder(description).build()
        if (type == ComplicationType.MONOCHROMATIC_IMAGE) return MonochromaticImageComplicationData.Builder(
            MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_watch)).build(), desc).setTapAction(tap).build()
        return if (type == ComplicationType.LONG_TEXT) LongTextComplicationData.Builder(text, desc)
            .setTitle(PlainComplicationText.Builder(title).build()).setTapAction(tap).build()
        else ShortTextComplicationData.Builder(text, desc).setTitle(PlainComplicationText.Builder(title).build()).setTapAction(tap).build()
    }
}
