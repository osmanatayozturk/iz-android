package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun OsmGpsConsentDialog(username: String, count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("osm-gps-consent"),
        title = { Text("$count GPS izini OSM'ye gönder?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bu izler $username hesabınla herkese açık olarak paylaşılır. Kalan konumlar ve kayıt zamanları hesabınla ilişkilendirilir. Başkaları izleri indirebilir.")
                Text("Görünürlük: Tanımlanabilir (Identifiable)")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-consent-confirm")) {
                Text("$count izi OSM'ye gönder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-consent-cancel")) {
                Text("Vazgeç")
            }
        },
    )
}
