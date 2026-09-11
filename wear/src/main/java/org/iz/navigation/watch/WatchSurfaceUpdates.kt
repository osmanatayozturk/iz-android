package org.iz.navigation.watch

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.io.File

internal object WatchSurfaceUpdates {
    @Synchronized fun request(context: Context, transition: String? = null, forceTile: Boolean = false) {
        val now = System.currentTimeMillis()
        val file = File(context.noBackupFilesDir, "surface_updates.txt")
        val saved = runCatching { file.readLines() }.getOrDefault(emptyList())
        val tileAt = saved.getOrNull(0)?.toLongOrNull() ?: 0
        val complicationAt = saved.getOrNull(1)?.toLongOrNull() ?: 0
        val previous = saved.getOrNull(2).orEmpty()
        val key = transition ?: previous
        val changed = key != previous
        val tile = forceTile || changed || now - tileAt !in 0..19_999
        val complication = changed || now - complicationAt !in 0..299_999
        if (!tile && !complication) return
        if (tile) runCatching { TileService.getUpdater(context).requestUpdate(WatchTileService::class.java) }
        if (complication) runCatching { ComplicationDataSourceUpdateRequester.create(context,
            ComponentName(context, WatchComplicationService::class.java)).requestUpdateAll() }
        runCatching { file.writeText("${if (tile) now else tileAt}\n${if (complication) now else complicationAt}\n$key") }
    }
}
