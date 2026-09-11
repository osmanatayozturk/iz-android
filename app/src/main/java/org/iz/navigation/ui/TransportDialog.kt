package org.iz.navigation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.Transport

@Composable
internal fun TransportDialog(
    initial: Transport,
    title: String,
    onDismiss: () -> Unit,
    onReject: (() -> Unit)? = null,
    onChoose: (Transport) -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf(if (initial == Transport.UNKNOWN) Transport.CAR.name else initial.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("transport_options"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nasıl gidiyorsun?", color = Muted)
                transportDisplayOrder.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { chosen = mode.name }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(chosen == mode.name, { chosen = mode.name })
                        Icon(mode.icon(), null, tint = mode.accentColor())
                        Spacer(Modifier.width(12.dp))
                        Text(mode.label())
                    }
                }
                if (onReject != null) TextButton(onClick = onReject) {
                    Text("Geçici kaydı reddet ve sil", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(Transport.valueOf(chosen)) }) { Text("Kaydı başlat / sakla") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
