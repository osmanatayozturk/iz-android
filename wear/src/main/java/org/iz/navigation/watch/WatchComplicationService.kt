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
        // Match the full surface's GPS/report expiry and always retain a non-current fallback.
        // https://developer.android.com/reference/androidx/wear/watchface/complications/datasource/TimeInterval
        val segments = WatchSurfaceTimeline.segments(data.snapshot, data.connected, now, frame)
        fun render(value: WatchSurfaceFrame, at: Long): ComplicationData {
            val phone = WatchSurfacePolicy.phoneState(data.snapshot, data.connected, at)
            val estimate = value.route == WatchSurfaceRoute.NAVIGATION &&
                WatchSurfacePolicy.navigationEstimate(data.snapshot, data.connected, at)
            val title = when (phone) {
                WatchPhoneState.NO_DATA -> "Veri"
                WatchPhoneState.DISCONNECTED -> "Bağlı değil"
                WatchPhoneState.EXPIRED -> "Eski veri"
                WatchPhoneState.CURRENT -> when (value.route) {
                    WatchSurfaceRoute.NAVIGATION -> if (estimate) "Varış" else "Rota"
                    WatchSurfaceRoute.RECORDING -> "Durum"
                    WatchSurfaceRoute.SUMMARY -> "Özet"
                    WatchSurfaceRoute.DAILY -> if (WatchSurfacePolicy.statisticsDeferred(data.snapshot)) "Durum" else "Adım"
                }
            }
            val text = if (request.complicationType == ComplicationType.LONG_TEXT && !estimate)
                value.lines.firstOrNull() ?: value.compact else value.compact
            return complication(request.complicationType, text, title, value.route,
                (listOf(value.title) + value.lines).joinToString(". "))
        }
        val fallbackSegment = segments.last()
        val fallback = render(fallbackSegment.frame, fallbackSegment.start)
        val active = segments.mapNotNull { segment ->
            segment.end?.let { end ->
                TimelineEntry(TimeInterval(Instant.ofEpochMilli(segment.start), Instant.ofEpochMilli(end)),
                    render(segment.frame, segment.start))
            }
        }
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
