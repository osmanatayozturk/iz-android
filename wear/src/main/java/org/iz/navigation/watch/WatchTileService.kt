package org.iz.navigation.watch

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
                                .setRoot(WatchTileLayout.build(packageName, value, requestParams.deviceConfiguration.screenWidthDp)).build()).build())
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
    fun build(packageName: String, frame: WatchSurfaceFrame, widthDp: Int): LayoutElementBuilders.LayoutElement {
        val compact = widthDp < 210
        val font = if (compact) 10.5f else 13f
        val content = LayoutElementBuilders.Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        fun text(value: String, size: Float, color: Int, maxLines: Int = 1) = LayoutElementBuilders.Text.Builder()
            .setText(value).setMaxLines(maxLines).setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(ColorBuilders.argb(color)).build()).build()
        content.addContent(text(frame.title, if (compact) 18f else 22f, 0xFFB0D998.toInt()))
        content.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5f)).build())
        frame.lines.take(7).forEachIndexed { index, line ->
            content.addContent(text(line, font, 0xFFF0F4DF.toInt(), if (frame.route == WatchSurfaceRoute.NAVIGATION && index == 0) 2 else 1))
            content.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(if (compact) 2f else 3f)).build())
        }
        content.addContent(text("Ayrıntıları aç", 11f, 0xFFB0D998.toInt()))
        val open = ActionBuilders.LaunchAction.Builder().setAndroidActivity(ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName).setClassName(WatchActivity::class.java.name)
            .addKeyToExtraMapping("surface", ActionBuilders.AndroidStringExtra.Builder().setValue(frame.route.name).build()).build()).build()
        return LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(0xFF091B15.toInt())).build())
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(if (compact) 20f else 26f))
                    .setEnd(dp(if (compact) 20f else 26f)).setTop(dp(if (compact) 12f else 20f)).setBottom(dp(if (compact) 12f else 20f)).build())
                .setClickable(ModifiersBuilders.Clickable.Builder().setId("open-${frame.route.name}").setOnClick(open).build())
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription((listOf(frame.title) + frame.lines).joinToString(". ")).build())
                .build()).addContent(content.build()).build()
    }
}
