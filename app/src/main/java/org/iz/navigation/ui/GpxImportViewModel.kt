package org.iz.navigation.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.gpx.GpxImporter
import org.iz.navigation.gpx.ImportedTrack

internal class GpxImportViewModel(application: Application) : AndroidViewModel(application) {
    private val imports = GpxImportController(viewModelScope)
    val state = imports.state
    fun show(track: ImportedTrack?) = imports.show(track)
    fun read(uri: Uri) = imports.read {
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')?.take(160)
                ?.replace(Regex("(?i)\\.gpx$"), "")?.ifBlank { null } ?: "GPX izi"
            requireNotNull(resolver.openInputStream(uri)) { "GPX dosyası açılamadı." }.use {
                GpxImporter.read(it, name)
            }
        }
    }
}
