package com.atay.iz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.atay.iz.weather.TrafficSettings
import com.atay.iz.weather.TrafficSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TrafficSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val store = remember(context) { TrafficSettingsStore(context) }
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(TrafficSettings()) }
    var enabled by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    // Never rememberSaveable: the key must not enter saved-instance state or backups.
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        current = withContext(Dispatchers.IO) { store.read() }
        enabled = current.enabled
        acknowledged = current.freePlanAcknowledged
        busy = false
    }
    AlertDialog(
        onDismissRequest = { if (!busy) { key = ""; onDismiss() } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Trafik ayarları") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TomTom ile otomobil ve yolcu rotalarında trafik hesaba katılır. Motosiklet yönlendirmesi beta; bazı bölgelerde kısıtlama bilgisi eksik olabilir.")
                Text("Yürüyüş, koşu, bisiklet ve yedi hareket saati karşılaştırması Valhalla ile trafiksiz hesaplanır.")
                Text("Trafik açıkken başlangıç, varış ve ara rota noktaları TomTom’a gönderilir.")
                TextButton(onClick = { uriHandler.openUri("https://docs.tomtom.com/pricing") }) { Text("Ücretsiz plan ve güncel sınırlar") }
                TextButton(onClick = { uriHandler.openUri("https://my.tomtom.com/") }) { Text("TomTom hesabı ve anahtarlar") }
                TextButton(onClick = { uriHandler.openUri("https://docs.tomtom.com/legal/terms-and-conditions") }) { Text("TomTom kullanım koşulları") }
                OutlinedTextField(
                    value = key, onValueChange = { key = it.take(256) }, enabled = !busy,
                    label = { Text(if (current.hasKey) "Yeni kişisel anahtar (isteğe bağlı)" else "Kişisel TomTom API anahtarı") },
                    supportingText = { Text(if (current.hasKey) "Anahtar güvenli biçimde kayıtlı. Boş bırakırsan korunur." else "Anahtar bu cihazda şifrelenir ve yedeklemeye katılmaz.") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(acknowledged, { acknowledged = it }, enabled = !busy)
                    Text("TomTom hesabımda ücretsiz planı ve kullanım sınırlarını kontrol ettim; ücretli kullanımı etkinleştirmedim. Yalnızca ücretsiz kullanım istiyorum.", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Trafiği kullan", Modifier.weight(1f))
                    Switch(enabled, { enabled = it }, enabled = !busy)
                }
                Text("İz hesap veya ödeme ayarlarını değiştirmez. Anahtar yoksa, kota dolarsa ya da servis yanıt vermezse nedenini gösterip trafiksiz rotaya döner.")
                if (current.hasKey) TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.clear() }
                            key = ""; enabled = false; acknowledged = false
                            current = TrafficSettings()
                            error = null
                        } catch (_: Exception) { error = "Anahtar silinemedi." }
                        finally { busy = false }
                    }
                }) { Text("Anahtarı sil ve trafiği kapat") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (!enabled || acknowledged && (key.isNotBlank() || current.hasKey)), onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.save(key.takeIf { it.isNotBlank() }, enabled, acknowledged) }
                        key = ""
                        onDismiss()
                    } catch (_: Exception) { error = "Ayarlar kaydedilemedi. Anahtarı ve ücretsiz kullanım onayını kontrol et." }
                    finally { busy = false }
                }
            }) { Text("Kaydet") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { key = ""; onDismiss() }) { Text("Kapat") } },
    )
}
