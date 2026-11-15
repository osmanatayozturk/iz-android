package org.iz.navigation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.iz.navigation.integration.NearbyDiagnostic
import org.iz.navigation.integration.NearbyLimit
import org.iz.navigation.integration.NearbyStage

/** Only the latest safe value object is rendered. Expansion and countdown remain in memory. */
@Composable
internal fun NearbyDiagnosticDetails(diagnostic: NearbyDiagnostic, expanded: Boolean, onToggle: () -> Unit) {
    var now by remember(diagnostic) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expanded, diagnostic) {
        if (expanded) {
            now = System.currentTimeMillis()
            while (diagnostic.remainingWaitMillis(now) > 0) {
                delay(1_000)
                now = System.currentTimeMillis()
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)
            .testTag("nearby-diagnostics-toggle").semantics { stateDescription = if (expanded) "Açık" else "Kapalı" }) {
            Text("Teknik ayrıntı")
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("nearby-diagnostics-details")) {
            Detail("Hata kodu", diagnostic.code.name)
            Detail("Aşama", diagnostic.stage.label())
            Detail("Geçen süre", "${seconds(diagnostic.elapsedMillis)} sn")
            diagnostic.httpStatus?.let { Detail("HTTP durumu", it.toString()) }
            diagnostic.waitUntilMillis?.let { Detail("Tekrar için bekleme", "${seconds(diagnostic.remainingWaitMillis(now))} sn") }
            if (diagnostic.limits.isNotEmpty()) Detail("Dolmuş sınır", diagnostic.limits.joinToString { it.label() })
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

private fun seconds(millis: Long): Long = millis.coerceAtLeast(0).let { it / 1_000 + if (it % 1_000 > 0) 1 else 0 }
private fun NearbyStage.label(): String = when (this) {
    NearbyStage.PREPARATION -> "Hazırlık"
    NearbyStage.LIMITS -> "Yerel sınır denetimi"
    NearbyStage.NETWORK -> "Ağ bağlantısı"
    NearbyStage.HTTP -> "HTTP yanıtı"
    NearbyStage.RESPONSE -> "Yanıt okuma"
    NearbyStage.PARSER -> "Yanıt çözümleme"
    NearbyStage.CATALOGUE -> "Katalog"
    NearbyStage.DISCOVERY -> "Yakındaki yerler isteği"
}
private fun NearbyLimit.label(): String = when (this) {
    NearbyLimit.QUERY_COUNT -> "Günlük sorgu sayısı"
    NearbyLimit.DAILY_BYTES -> "Günlük veri miktarı"
    NearbyLimit.SERVICE_WAIT -> "Servis bekleme süresi"
    NearbyLimit.RESPONSE_BYTES -> "Tek yanıt boyutu"
    NearbyLimit.OBJECT_COUNT -> "Yanıttaki nesne sayısı"
}
