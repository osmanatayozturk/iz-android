package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.BuildConfig
import org.iz.navigation.offline.OfflineLabelledProfile

private enum class License(val label: String, val assets: List<String>) {
    MAP("Harita ve yazı tipi lisansları", listOf(
        "${OfflineLabelledProfile.ASSET_ROOT}/notices/2f54d8afce21896c863cb48c6d8b1f0ac1b786b94e63e81b2cfa554c1630da48.txt",
        "${OfflineLabelledProfile.ASSET_ROOT}/notices/fonts/carlito/OFL.txt",
        "${OfflineLabelledProfile.ASSET_ROOT}/notices/fonts/carlito/AUTHORS.txt",
    )),
    CATALOGUE("OpenStreetMap düzenleme kataloğu", listOf("licenses/NOTICE.id-tagging-schema.txt", "licenses/LICENSE.id-tagging-schema.md")),
    HOURS("Açılış saatleri ayrıştırıcısı", listOf("licenses/OpeningHoursParser-0.29.0-MIT.txt")),
}

@Composable
internal fun LicenseSettings() {
    var selected by rememberSaveable { mutableStateOf<License?>(null) }
    Text("İz ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
    Text("GNU GPL-3.0-only · Google Play Services için ek bağlama izni")
    HorizontalDivider()
    Text("© OpenStreetMap katkıcıları", style = MaterialTheme.typography.titleMedium)
    Text("Harita verisi Open Database License (ODbL) kapsamında sunulur.")
    License.entries.forEach { license ->
        OutlinedButton(onClick = { selected = license }, modifier = Modifier.fillMaxWidth()) { Text(license.label) }
    }
    selected?.let { license ->
        key(license) {
            val context = LocalContext.current
            val body by produceState<String?>(null, context, license) {
                value = withContext(Dispatchers.IO) {
                    try {
                        license.assets.joinToString("\n\n") { path ->
                            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        }
                    } catch (_: java.io.IOException) {
                        "Yerel lisans metni okunamadı."
                    }
                }
            }
            AlertDialog(onDismissRequest = { selected = null }, title = { Text(license.label) }, text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(body ?: "Lisans metni okunuyor…", style = MaterialTheme.typography.bodySmall) }
                }
            }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Kapat") } })
        }
    }
}
