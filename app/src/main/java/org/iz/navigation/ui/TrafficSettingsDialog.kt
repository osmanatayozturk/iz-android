package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import org.iz.navigation.weather.TrafficSettings
import org.iz.navigation.weather.TrafficSettingsStore
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
    var verified by remember { mutableStateOf(false) }
    var matrix by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(false) }
    // The key must never enter saved instance state or backups.
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        current = withContext(Dispatchers.IO) { store.read() }
        enabled = current.enabled
        acknowledged = current.freePlanAcknowledged
        verified = current.freeAccountVerified
        matrix = current.matrixEnabled
        speed = current.speedFallbackEnabled
        busy = false
    }
    AlertDialog(
        onDismissRequest = { if (!busy) { key = ""; onDismiss() } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Trafik ve hız sınırı") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TomTom otomobil ve yolcu rotalarında trafiği hesaba katar. Motosiklet yönlendirmesi beta; bölgesel kısıtlama bilgisi eksik olabilir.")
                Text("Hareket saati seçenekleri bir temel rotadan yaklaşık hesaplanır. Seçtiğin saatin rotası ayrıca doğrulanır. Yürüyüş, koşu ve bisiklet Valhalla kullanır.")
                TextButton(onClick = { uriHandler.openUri("https://docs.tomtom.com/pricing") }) { Text("Ücretsiz plan ve güncel sınırlar") }
                TextButton(onClick = { uriHandler.openUri("https://my.tomtom.com/") }) { Text("TomTom hesabı ve API erişimi") }
                TextButton(onClick = { uriHandler.openUri("https://docs.tomtom.com/legal/terms-and-conditions") }) { Text("TomTom kullanım koşulları") }
                OutlinedTextField(
                    value = key, onValueChange = {
                        key = it.take(256)
                        if (it.isNotBlank()) { verified = false; matrix = false; speed = false }
                    }, enabled = !busy,
                    label = { Text(if (current.hasKey) "Yeni kişisel anahtar (isteğe bağlı)" else "Kişisel TomTom API anahtarı") },
                    supportingText = { Text(if (current.hasKey) "Kayıtlı anahtar korunur; yeniden girmen gerekmez." else "Anahtar cihazda şifrelenir ve yedeklenmez.") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(acknowledged, {
                        acknowledged = it
                        if (!it) { enabled = false; verified = false; matrix = false; speed = false }
                    }, enabled = !busy)
                    Text("Hesabımda ücretsiz planı ve sınırları kontrol ettim; ücretli kullanımı etkinleştirmedim. Yalnızca ücretsiz kullanım istiyorum.", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Trafiği kullan", Modifier.weight(1f))
                    Switch(enabled, { enabled = it }, enabled = !busy && acknowledged)
                }
                Text("Yeni özellikler için hesap doğrulaması", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(verified, {
                        verified = it
                        if (!it) { matrix = false; speed = false }
                    }, enabled = !busy && acknowledged && (key.isNotBlank() || current.hasKey),
                        modifier = Modifier.testTag("traffic-free-capabilities-verified"))
                    Text("Etkinleştireceğim API'lerin hesabımın ücretsiz kotasında olduğunu, ücretli bakiye veya otomatik yükleme bulunmadığını doğruladım.", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Durak sırası önerisi · Matrix", Modifier.weight(1f))
                    Switch(matrix, { matrix = it }, enabled = !busy && verified,
                        modifier = Modifier.testTag("traffic-matrix-enabled"))
                }
                Text("Öneri düğmesine bastığında duraklar TomTom'a gönderilir. Motosiklet sıralaması otomobil yaklaşımıyla bulunur; sonuç motosiklet rotasıyla karşılaştırılır.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("OSM eksikse TomTom hız sınırı", Modifier.weight(1f))
                    Switch(speed, { speed = it }, enabled = !busy && verified,
                        modifier = Modifier.testTag("traffic-speed-fallback-enabled"))
                }
                Text("Otomobil ve motosiklette önce OSM kullanılır. Yol güvenle eşleşir ama etiket yoksa konum ve yön TomTom'a gönderilebilir. Serbest sürüş sonucu genel yol sınırı olarak gösterilir. Belirsiz veya koşullu sınırlar boş kalır.")
                Text("İz hesap ve ödeme ayarlarını değiştirmez. Kotalar hesap genelindedir; cihazdaki sorgu sınırlaması ücret garantisi değildir. Servis sınırında ek istekler durur ve bulunamayan hız sınırı — görünür.")
                if (current.hasKey) TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.clear() }
                            key = ""; enabled = false; acknowledged = false; verified = false; matrix = false; speed = false
                            current = TrafficSettings(); error = null
                        } catch (_: Exception) { error = "Anahtar silinemedi." }
                        finally { busy = false }
                    }
                }) { Text("Anahtarı sil ve TomTom'u kapat") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (!(enabled || matrix || speed) || acknowledged && (key.isNotBlank() || current.hasKey)), onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            store.save(key.takeIf { it.isNotBlank() }, enabled, acknowledged, verified, matrix, speed)
                        }
                        key = ""; onDismiss()
                    } catch (_: Exception) { error = "Ayarlar kaydedilemedi. Anahtarı ve ücretsiz kullanım onayını kontrol et." }
                    finally { busy = false }
                }
            }) { Text("Kaydet") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { key = ""; onDismiss() }) { Text("Kapat") } },
    )
}