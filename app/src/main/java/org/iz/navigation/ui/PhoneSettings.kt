package org.iz.navigation.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.BuildConfig
import org.iz.navigation.IzApplication
import androidx.health.connect.client.HealthConnectClient
import org.iz.navigation.integration.osm.OsmAuthManager
import org.iz.navigation.lean.LeanMount
import org.iz.navigation.lean.LeanSettingsStore
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationVoiceSettings
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.ui.history.LocalUiExitGuard
import org.iz.navigation.ui.history.LocalUiVoiceSettingsAction
import org.iz.navigation.weather.WeatherSettingsStore

private enum class Section(val id: String, val label: String, val icon: ImageVector) {
    THEME("theme", "Görünüm", Icons.Outlined.Palette),
    VOICE("voice", "Ses, trafik ve harita", Icons.Outlined.VolumeUp),
    DETECTION("detection", "Hareket algılama", Icons.Outlined.MyLocation),
    CALIBRATE("calibrate", "Motosiklet montajı ve kalibrasyon", Icons.Outlined.TwoWheeler),
    HEALTH("health", "Saat ve sağlık", Icons.Outlined.FavoriteBorder),
    OSM("osmsettings", "OSM ve harita servisleri", Icons.Outlined.Public),
    BACKUP("backup", "Yedekleme ve geri yükleme", Icons.Outlined.Backup),
    LICENSES("licenses", "Sürüm ve lisanslar", Icons.Outlined.Info),
}

@Composable
internal fun PhoneSettings(
    settings: TrackerSettings, refresh: Int, busy: Boolean, onDetection: () -> Unit,
    onRepairDetection: () -> Unit, onStopMinutes: (Int) -> Unit, onPermissions: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit, onContributions: () -> Unit,
    onMessage: (String) -> Unit, onCommunity: () -> Unit, listState: LazyListState,
    initialSection: String?, onLayers: () -> Unit, onAccount: () -> Unit,
) {
    var section by rememberSaveable(initialSection) {
        mutableStateOf(Section.entries.firstOrNull { it.id == initialSection })
    }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    val guard = LocalUiExitGuard.current
    val back = {
        val action: () -> Unit = { if (diagnostics) diagnostics = false else section = null }
        if (guard == null) action() else guard.requestExit(action)
    }
    // A direct section entry belongs to the caller's history; only handle steps opened inside Settings.
    BackHandler(enabled = diagnostics || (section != null && section?.id != initialSection), onBack = back)
    val appearance = LocalIzAppearance.current
    val selected = section
    if (selected == null) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("settings"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
            item { Text("Ayarlar", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp)) }
            items(Section.entries.size, key = { Section.entries[it].id }) { index ->
                val destination = Section.entries[index]
                val detail = when (destination) {
                    Section.THEME -> "${appearance.palette.label} · ${appearance.mode.label}"
                    Section.VOICE -> {
                        val app = LocalContext.current.applicationContext as IzApplication
                        val nav = app.navigation.state.collectAsStateWithLifecycle()
                        val muted by remember(nav) { derivedStateOf { nav.value.muted } }
                        if (muted) "Sesli yönlendirme kapalı" else "Sesli yönlendirme açık"
                    }
                    Section.DETECTION -> "${if (settings.enabled) "Açık" else "Kapalı"} · Durak önerisi ${settings.stopMinutes} dk"
                    Section.CALIBRATE -> {
                        val app = LocalContext.current.applicationContext
                        val lean by remember(app) { LeanSettingsStore.get(app) }.state.collectAsStateWithLifecycle()
                        val mount = when (lean.mount) {
                            LeanMount.HANDLEBAR -> "Gidon"
                            LeanMount.BODY -> "Gövde"
                            LeanMount.UNKNOWN -> "Montaj seçilmedi"
                        }
                        if (lean.mount == LeanMount.UNKNOWN) mount
                        else "$mount · ${if (lean.calibration == null) "Kalibrasyon yok" else "Kalibrasyon kayıtlı"}"
                    }
                    Section.HEALTH -> {
                        val app = LocalContext.current.applicationContext as IzApplication
                        val health by app.healthManager.state.collectAsStateWithLifecycle()
                        when {
                            health.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect kullanılamıyor"
                            health.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect kurulum veya güncelleme bekliyor"
                            !health.enabled -> "Health Connect bağlantısı kapalı"
                            health.grantedMetrics.isEmpty() && health.dailyGrantedPermissions.isEmpty() -> "Health Connect okuma izni bekliyor"
                            health.syncing -> "Sağlık verileri eşitleniyor"
                            health.backgroundAllowed -> "Arka planda eşitleme açık"
                            else -> "Uygulama açıkken eşitleme"
                        }
                    }
                    Section.OSM -> {
                        val app = LocalContext.current.applicationContext
                        val osm by remember(app) { OsmAuthManager.get(app) }.state.collectAsStateWithLifecycle()
                        when {
                            osm.user != null -> "OSM hesabı bağlı"
                            !osm.clientIdConfigured -> "OSM uygulama bağlantısı ayarlanmadı"
                            else -> "OSM hesabı bağlı değil"
                        }
                    }
                    Section.BACKUP -> if (busy) "Dosya işlemi sürüyor" else "Dosyaya yedekleme · Otomatik bulut yedeği kapalı"
                    Section.LICENSES -> "İz ${BuildConfig.VERSION_NAME}"
                }
                SettingsLink(destination.label, destination.icon, { section = destination }, detail, destination.id)
            }
        }
        return
    }
    key(selected, diagnostics) {
        val viewTag = "settings-view-" + if (diagnostics) "diags" else selected.id
        val panelHasHeading = selected == Section.CALIBRATE || selected == Section.OSM
        val panelModifier = if (panelHasHeading) Modifier.testTag(viewTag) else Modifier
        Column(panelModifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!panelHasHeading) Text(if (diagnostics) "Algılama tanısı" else selected.label,
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(viewTag))
            when (selected) {
                Section.THEME -> {
                    val context = LocalContext.current
                    AppearanceChoices(appearance, { savePalette(context, it) }, { saveAppearanceMode(context, it) })
                }
                Section.VOICE -> VoiceAndRouteSettings(onLayers, onMessage)
                Section.DETECTION -> if (diagnostics) {
                    TrackingDiagnosticsPanel(settings, refresh, onRepairDetection)
                    settings.lastActivityEvent?.let {
                        Text("Son algılama · " + DateFormat.getDateTimeInstance().format(Date(settings.lastActivityEventAt)))
                        Text(it)
                    }
                    settings.lastDetectionDecision?.let { Text(it) }
                } else {
                    SettingsSwitch("Hareketi fark et", settings.enabled, { onDetection() })
                    Text("15 dakikada 500 metre tamamlanırsa yolculuk kaydı başlar. Süre dolunca geçici ölçüm sıfırlanır. Dur-kalklar ölçülen mesafeyi sıfırlamaz.")
                    Text("Durak önerisi", style = MaterialTheme.typography.titleMedium)
                    Text("Hareketsizlikten sonra hatırlat. Yolculuk kendiliğinden bitmez.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15).forEach { minutes ->
                            FilterChip(settings.stopMinutes == minutes, { onStopMinutes(minutes) }, { Text("$minutes dk") })
                        }
                    }
                    SettingsLink("Android izinlerini yönet", Icons.Outlined.Security, onPermissions)
                    SettingsLink("Algılama tanısı", Icons.Outlined.Troubleshoot, { diagnostics = true })
                }
                Section.CALIBRATE -> LeanSetupPanel(onMessage)
                Section.HEALTH -> HealthSettingsPanel(onMessage)
                Section.OSM -> {
                    OsmSettingsPanel(onContributions, onMessage, onAccount = onAccount)
                    SettingsLink("OSM Topluluğu", Icons.Outlined.Forum, onCommunity)
                }
                Section.BACKUP -> BackupSettings(refresh, busy, onExport, onImport)
                Section.LICENSES -> LicenseSettings()
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SettingsLink(label: String, icon: ImageVector, open: () -> Unit, detail: String? = null, tag: String = label) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = if (detail != null) { { Text(detail) } } else null,
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
        modifier = Modifier.fillMaxWidth().testTag("settings-open-$tag").clickable(role = Role.Button, onClick = open),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked, null)
    }
}

@Composable
private fun AppearanceChoices(appearance: IzAppearance, onPalette: (IzPalette) -> Unit, onMode: (AppearanceMode) -> Unit) {
    Text("Renk paleti", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        IzPalette.entries.forEach { palette ->
            val selected = palette == appearance.palette
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton) { onPalette(palette) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioButton(selected, null)
                Text(palette.label, Modifier.weight(1f))
                listOf(palette.day.brand, palette.day.route, palette.night.route).forEach {
                    Box(Modifier.size(18.dp).background(it, RoundedCornerShape(6.dp)))
                }
            }
        }
    }
    Text("Ekran modu", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        AppearanceMode.entries.forEach { mode ->
            val selected = mode == appearance.mode
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton) { onMode(mode) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioButton(selected, null)
                Text(mode.label)
            }
        }
    }
    Text("Önizleme", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Gündüz" to appearance.palette.day, "Gece" to appearance.palette.night).forEach { (title, colors) ->
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = colors.soft, contentColor = colors.ink) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Box(Modifier.fillMaxWidth().height(4.dp).background(colors.route, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

@Composable
private fun VoiceAndRouteSettings(onLayers: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val navigation = (context.applicationContext as IzApplication).navigation
    val nav by navigation.state.collectAsStateWithLifecycle()
    val openVoiceSettings = LocalUiVoiceSettingsAction.current
    var traffic by rememberSaveable { mutableStateOf(false) }
    var showPreferences by rememberSaveable { mutableStateOf(false) }
    SettingsSwitch("Sesli yönlendirme", !nav.muted, { navigation.setMuted(!it) })
    SettingsLink("Ses seçenekleri", Icons.Outlined.RecordVoiceOver, {
        if (openVoiceSettings != null) openVoiceSettings() else NavigationVoiceSettings.open(context) { context.startActivity(it) }
    })
    SettingsLink("Vasıtaya göre rota tercihleri", Icons.Outlined.Route, { showPreferences = true })
    SettingsLink("Trafik ve hız sınırı", Icons.Outlined.Speed, { traffic = true })
    SettingsLink("Harita katmanları", Icons.Outlined.Layers, onLayers)
    if (traffic) TrafficSettingsDialog { traffic = false }
    if (showPreferences) RoutePreferenceSettings(onDismiss = { showPreferences = false }, onMessage = onMessage)
}

@Composable
private fun RoutePreferenceSettings(onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { WeatherSettingsStore(context) }
    var mode by rememberSaveable { mutableStateOf(Transport.MOTORCYCLE) }
    var expanded by remember { mutableStateOf(false) }
    var choices by remember(mode) { mutableStateOf(store.read(mode).preferences) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rota tercihleri") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(mode.label()) }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    transportDisplayOrder.forEach { option -> DropdownMenuItem(text = { Text(option.label()) },
                        onClick = { mode = option; expanded = false }) }
                }
            }
            QuickRoutePreference.entries.forEach { option ->
                val avoid = when (option) {
                    QuickRoutePreference.HIGHWAYS -> choices.avoidHighways
                    QuickRoutePreference.TOLLS -> choices.avoidTolls
                    QuickRoutePreference.FERRIES -> choices.avoidFerries
                    QuickRoutePreference.UNPAVED -> choices.avoidUnpaved
                }
                if (option.supports(mode)) SettingsSwitch("${option.label} kullanma", avoid, { choices = option.applyTo(choices, it) })
                else Text("${option.label} · Bu vasıtada desteklenmiyor", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { store.save(store.read(mode).copy(preferences = choices), mode) }
            .onSuccess { onDismiss() }.onFailure { onMessage("Rota tercihleri kaydedilemedi.") }
    }) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
private fun BackupSettings(refresh: Int, busy: Boolean, onExport: () -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val photoBytes by produceState<Long?>(null, context, refresh) {
        value = withContext(Dispatchers.IO) {
            File(context.filesDir, "photos").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }
    Text("Yedek içeriği", style = MaterialTheme.typography.titleMedium)
    Text("Yolculuklar, konum izleri, yerler, özel notlar ve fotoğraflar dosyaya eklenir. Sağlık ölçümlerini yedeği hazırlarken seçebilirsin.")
    photoBytes?.let { Text("Telefondaki fotoğraflar · " + Formatter.formatFileSize(context, it), style = MaterialTheme.typography.bodyMedium) }
    Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Yedek dosyası oluştur") }
    HorizontalDivider()
    Text("Geri yükleme", style = MaterialTheme.typography.titleMedium)
    Text("Dosyanı seçtikten sonra adını, bildirilen boyutunu ve mevcut günlüğe etkisini inceleyebilirsin.")
    OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Yedekten geri yükle") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

private data class DocumentInfo(val name: String?, val size: Long?)

@Composable
internal fun RestoreConfirmation(uri: Uri, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val context = LocalContext.current
    val info by produceState<DocumentInfo?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                    DocumentInfo(name.takeIf { it >= 0 }?.let(cursor::getString), size.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)?.takeIf { it >= 0 })
                } ?: DocumentInfo(null, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DocumentInfo(null, null)
            }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Yedeği geri yükle") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(info?.name?.takeIf(String::isNotBlank) ?: if (info == null) "Dosya bilgisi okunuyor…" else "Dosya adı bildirilmedi", style = MaterialTheme.typography.titleMedium)
            info?.let { Text(it.size?.let { size -> Formatter.formatFileSize(context, size) } ?: "Dosya boyutu bildirilmedi") }
            Text("Dosya doğrulanırsa mevcut günlük bu yedeğin içeriğiyle değiştirilir. Aktif yolculuk kaydı durur. Mevcut kayıtlarını saklamak için önce yedek alabilirsin.")
        }
    }, confirmButton = { TextButton(enabled = !busy && info != null, onClick = onConfirm) { Text("Geri yükle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}
