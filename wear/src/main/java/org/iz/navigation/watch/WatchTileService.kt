package org.iz.navigation.watch

import androidx.compose.ui.graphics.toArgb
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.tiles.*
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.DimensionBuilders.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*

/** ProtoLayout Tile: no sensors, foreground service, or phone command is started by this surface. */
class WatchTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                try {
                    val data = WatchSurfaceReader.read(this@WatchTileService)
                    val now = System.currentTimeMillis()
                    val frame = watchSurfaceFrame(this@WatchTileService, data, now)
                    val entries = TimelineBuilders.Timeline.Builder()
                    fun entry(value: WatchSurfaceFrame, start: Long, end: Long? = null) {
                        val validity = TimelineBuilders.TimeInterval.Builder().setStartMillis(start)
                        end?.let(validity::setEndMillis)
                        entries.addTimelineEntry(TimelineBuilders.TimelineEntry.Builder()
                            .setValidity(validity.build()).setLayout(LayoutElementBuilders.Layout.Builder()
                                .setRoot(WatchTileLayout.build(packageName, value, requestParams.deviceConfiguration.screenWidthDp, data.snapshot?.paletteId)).build()).build())
                    }
                    WatchSurfaceTimeline.segments(data.snapshot, data.connected, now, frame).forEach { entry(it.frame, it.start, it.end) }
                    completer.set(TileBuilders.Tile.Builder().setResourcesVersion("1")
                        .setFreshnessIntervalMillis(60_000).setTileTimeline(entries.build()).build())
                } catch (failure: Exception) { completer.setException(failure) }
            }
            "Iz tile"
        }
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer -> completer.set(ResourceBuilders.Resources.Builder().setVersion("1").build()); "Iz resources" }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

internal object WatchTileLayout {
    fun build(packageName: String, frame: WatchSurfaceFrame, widthDp: Int, paletteId: String? = null): LayoutElementBuilders.LayoutElement {
        val palette = WatchPalette.from(paletteId)
        val compact = widthDp < 210
        val font = if (compact) 10.5f else 13f
        val content = LayoutElementBuilders.Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        fun text(value: String, size: Float, color: Int, maxLines: Int = 1) = LayoutElementBuilders.Text.Builder()
            .setText(value).setMaxLines(maxLines).setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(ColorBuilders.argb(color)).build()).build()
        content.addContent(text(frame.title, if (compact) 18f else 22f, palette.accent.toArgb()))
        content.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5f)).build())
        val expired = frame.validUntil == null
        val supporting = when {
            expired -> frame.lines.take(2)
            frame.route == WatchSurfaceRoute.SUMMARY -> frame.lines.drop(1).take(1)
            frame.route == WatchSurfaceRoute.DAILY -> frame.lines.filter { it.startsWith("Yürü/koş") }.take(1)
                .ifEmpty { frame.lines.take(1) }
            else -> frame.lines.take(1)
        }
        supporting.forEach { line -> content.addContent(text(line, if (compact) 13f else 16f, palette.text.toArgb(), 2)) }
        content.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5f)).build())
        content.addContent(text(if (expired) "Ayrıntıları aç" else "Güncel · Ayrıntıları aç", font, palette.accent.toArgb()))
        val open = ActionBuilders.LaunchAction.Builder().setAndroidActivity(ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName).setClassName(WatchActivity::class.java.name)
            .addKeyToExtraMapping("surface", ActionBuilders.AndroidStringExtra.Builder().setValue(frame.route.name).build()).build()).build()
        return LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(palette.background.toArgb())).build())
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(if (compact) 20f else 26f))
                    .setEnd(dp(if (compact) 20f else 26f)).setTop(dp(if (compact) 12f else 20f)).setBottom(dp(if (compact) 12f else 20f)).build())
                .setClickable(ModifiersBuilders.Clickable.Builder().setId("open-${frame.route.name}").setOnClick(open).build())
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription((listOf(frame.title) + frame.lines).joinToString(". ")).build())
                .build()).addContent(content.build()).build()
    }
}
