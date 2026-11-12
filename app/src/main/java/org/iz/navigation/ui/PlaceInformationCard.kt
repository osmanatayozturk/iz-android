package org.iz.navigation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.iz.navigation.placeinfo.*
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain public renderer: no private Place, activity, client, navigation or upload dependency. */
@Composable
internal fun PlaceInformationContent(state: PlaceInfoState, key: PlaceInfoKey, fallbackName: String,
    instant: Instant, deviceZone: ZoneId, overrideId: String?, onZoneChange: (String?) -> Unit,
    onRefresh: () -> Unit, onDirections: (() -> Unit)?, onSurvey: (() -> Unit)?, onCorrect: (() -> Unit)?,
    onNote: (() -> Unit)?, onExternal: (PlaceInfoAction, Boolean) -> Unit,
    onClose: (() -> Unit)? = null, modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null, externalMessage: String? = null) {
    val snapshot = state.snapshot?.takeIf { it.key == key }
    val presentation = remember(snapshot) { snapshot?.let(PublicPlaceMapper::map) }
    val unavailable = state.unavailableStatus != null
    val historical = state.stale || state.failed || unavailable
    val zone = PlaceInfoZones.resolve(overrideId, presentation?.timezone, deviceZone)
    val hours = remember(presentation?.openingHours, instant, zone, unavailable) {
        if (unavailable) PlaceHoursResult(PlaceOpeningState.UNKNOWN, presentation?.openingHours, null)
        else BasicPlaceOpeningHours.evaluate(presentation?.openingHours, instant, zone.zone)
    }
    var showHours by remember(key) { mutableStateOf(false) }
    var showZone by remember(key) { mutableStateOf(false) }
    val missing = "OSM'de bilgi yok"
    val waiting = if (state.failed) "Bilgiler alınamadı." else "Bilgiler henüz alınmadı."
    val fetched = snapshot?.let { DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.forLanguageTag("tr-TR"))
        .withZone(deviceZone).format(Instant.ofEpochMilli(it.fetchedAtMillis)) }
    Column(modifier.testTag("place-information-card"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        onClose?.let { TextButton(it, Modifier.heightIn(min = 48.dp).testTag("place-info-close")) { Text("Kapat") } }
        Text(presentation?.name ?: fallbackName, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        if (presentation?.name == null) Text("Seçilen yer adı · OSM adı doğrulanmadı", style = MaterialTheme.typography.bodySmall)
        Text(presentation?.address ?: if (snapshot != null) missing else waiting)
        Text("© OpenStreetMap katkıcıları · ${key.ref.type.name.lowercase(Locale.ROOT)} ${key.ref.id}",
            style = MaterialTheme.typography.bodySmall)
        Text(buildList {
            if (unavailable) add("Bu OSM nesnesi artık kullanılamıyor.")
            if (snapshot != null) add(if (historical) "Kaydedilmiş eski OSM bilgileri · Son alındı: $fetched"
                else "OSM bilgileri · Son alındı: $fetched")
            if (state.loading) add(if (snapshot == null) "OSM bilgileri yükleniyor…" else "Güncelleniyor…")
            if (state.failed) add(if (snapshot == null) "OSM bilgileri alınamadı. Bağlantını kontrol edip yeniden dene." else "Güncel bilgiler alınamadı.")
        }.joinToString("\n"), Modifier.testTag("place-info-status").semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onRefresh, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("place-info-refresh")) {
            Text(if (state.failed) "Yeniden dene" else "Yenile")
        }
        Button(onClick = { onDirections?.invoke() }, enabled = onDirections != null,
            modifier = Modifier.heightIn(min = 48.dp).testTag("place-info-route")) { Text("Yol tarifi") }
        if (onDirections == null) Text("Yol tarifi için bu yerin geçerli hedef konumu yok.")
        onSave?.let { OutlinedButton(it, Modifier.heightIn(min = 48.dp).testTag("place-info-save-personal")) { Text("Bu yeri günlüğüme kaydet") } }
        externalMessage?.let { Text(it, Modifier.testTag("place-info-action-error").semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }

        PublicSectionHeading("İletişim", "place-info-contact")
        Text("Telefon", style = MaterialTheme.typography.titleSmall)
        if (presentation?.phones.isNullOrEmpty() && presentation?.invalidPhoneValues.isNullOrEmpty()) Text(if (snapshot != null) missing else waiting)
        presentation?.phones?.forEachIndexed { index, action ->
            OutlinedButton({ onExternal(action, true) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("place-info-phone-$index")) {
                Text("Telefon uygulamasını aç · ${action.label}")
            }
        }
        presentation?.invalidPhoneValues?.forEach { Text("Açılamayan telefon değeri: $it") }
        Text("Web sitesi", style = MaterialTheme.typography.titleSmall)
        if (presentation?.websites.isNullOrEmpty() && presentation?.invalidWebsiteValues.isNullOrEmpty()) Text(if (snapshot != null) missing else waiting)
        presentation?.websites?.forEachIndexed { index, action ->
            OutlinedButton({ onExternal(action, false) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("place-info-web-$index")) {
                Text("Web sitesini aç · ${action.label}")
            }
        }
        presentation?.invalidWebsiteValues?.forEach { Text("Açılamayan web sitesi değeri: $it") }

        PublicSectionHeading("Açılış saatleri", "place-info-hours-heading")
        val hoursLabel = if (unavailable) "Güncel saat durumu kullanılamıyor" else if (snapshot == null) waiting else placeOpeningLabel(hours.state, historical)
        OutlinedButton({ showHours = true }, enabled = snapshot != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("place-info-hours")) {
            Text("$hoursLabel\nSaat programını göster")
        }
        Text("Saat dilimi: ${zone.zone.id} · ${when (zone.source) {
            PlaceZoneSource.OVERRIDE -> "Bu yer için seçtin"
            PlaceZoneSource.OSM -> "OSM"
            PlaceZoneSource.DEVICE -> "Cihaz; yerin coğrafi saat dilimi doğrulanmadı"
        }}", Modifier.testTag("place-info-zone"))
        TextButton({ showZone = true }, Modifier.heightIn(min = 48.dp).testTag("place-info-zone-change")) { Text("Saat dilimini değiştir") }

        PublicSectionHeading("Tekerlekli sandalye erişimi", "place-info-wheelchair")
        Text(if (presentation == null) waiting else when (presentation.wheelchair) {
            PlaceTagValue.YES -> "Erişilebilir"
            PlaceTagValue.LIMITED -> "Kısmen erişilebilir"
            PlaceTagValue.NO -> "Erişilebilir değil"
            PlaceTagValue.DESIGNATED -> "Bu kullanım için ayrılmış"
            PlaceTagValue.MISSING -> missing
            else -> "Durum belirlenemedi · ${presentation.wheelchairRaw.orEmpty()}"
        })
        PublicSectionHeading("Ödeme", "place-info-payments")
        if (presentation?.payments.isNullOrEmpty()) Text(if (snapshot != null) missing else waiting)
        presentation?.payments?.forEach { fact ->
            val known = fact.label != fact.key && fact.value in setOf(PlaceTagValue.YES, PlaceTagValue.NO, PlaceTagValue.ONLY)
            Text("${fact.label} · ${when (fact.value) {
                PlaceTagValue.YES -> "Kabul ediliyor"
                PlaceTagValue.NO -> "Kabul edilmiyor"
                PlaceTagValue.ONLY -> "Yalnızca bu yöntem"
                PlaceTagValue.MISSING -> missing
                else -> "Durum belirlenemedi"
            }}" + if (known) "" else "\n${fact.key}=${fact.rawValue}")
        }
        Text("Bu bilgiler OpenStreetMap'ten alınır; değişmiş olabilir.", style = MaterialTheme.typography.bodySmall)
        PublicSectionHeading("Haritaya katkı", "place-info-contribute")
        onSurvey?.let { OutlinedButton(it, enabled = !unavailable, modifier = Modifier.heightIn(min = 48.dp).testTag("place-info-survey")) { Text("Yerinde tamamla") } }
        onCorrect?.let { OutlinedButton(it, enabled = !unavailable, modifier = Modifier.heightIn(min = 48.dp).testTag("place-info-correct")) { Text("OSM mekânını düzelt") } }
        if (unavailable) Text("Kullanılamayan nesnede düzenleme yapılamaz.")
        onNote?.let { OutlinedButton(it, Modifier.heightIn(min = 48.dp).testTag("place-info-note")) { Text("Harita notu yaz") } }
        TextButton({ onExternal(PlaceInfoAction("OSM nesnesi", "https://www.openstreetmap.org/${key.ref.type.name.lowercase(Locale.ROOT)}/${key.ref.id}", emptySet()), false) },
            Modifier.heightIn(min = 48.dp).testTag("place-info-osm")) { Text("OSM'de aç") }
    }
    if (showHours) PlaceInformationHoursDetail(hours, zone, notice = if (historical)
        "${if (unavailable) "Bu OSM nesnesi artık kullanılamıyor. " else ""}Kaydedilmiş eski OSM saatleri · Son alındı: $fetched" else null) { showHours = false }
    if (showZone) PlaceInformationZoneDialog(zone, overrideId, onZoneChange, { showZone = false }, instant)
}

/** The production map-card window, also rendered by isolated component capture fixtures. */
@Composable
internal fun PlaceInformationMapDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun PublicSectionHeading(label: String, tag: String) {
    HorizontalDivider()
    Text(label, Modifier.testTag(tag).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** No package query or ACTION_CALL; mapper output is checked again at the external boundary. */
internal fun openPlaceInformationAction(context: Context, action: PlaceInfoAction, phone: Boolean): String? {
    val uri = runCatching { URI(action.uri) }.getOrNull() ?: return "Bu bağlantı güvenli biçimde açılamıyor."
    val valid = if (phone) uri.scheme == "tel" && uri.rawSchemeSpecificPart.matches(Regex("[+0-9(). -]+")) && uri.rawSchemeSpecificPart.any(Char::isDigit)
    else uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    if (!valid) return "Bu bağlantı güvenli biçimde açılamıyor."
    return try {
        context.startActivity(Intent(if (phone) Intent.ACTION_DIAL else Intent.ACTION_VIEW, Uri.parse(action.uri)).apply {
            if (!phone) addCategory(Intent.CATEGORY_BROWSABLE)
        })
        null
    } catch (_: ActivityNotFoundException) { "Açılacak uygulama bulunamadı." }
    catch (_: SecurityException) { "Açılacak uygulama bulunamadı." }
}
