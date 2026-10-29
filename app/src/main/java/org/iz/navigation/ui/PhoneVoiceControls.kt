package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.iz.navigation.navigation.SpeechStatus

@Composable
internal fun PhoneVoiceControls(status: SpeechStatus, muted: Boolean, notice: String?,
    onTest: () -> Unit, onRetry: () -> Unit, onSettings: () -> Unit) {
    val text = phoneVoicePresentation(status)
    Column(Modifier.fillMaxWidth().testTag("navigation-voice-controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Seslendirme", style = MaterialTheme.typography.titleMedium)
        Text(if (muted) "Navigasyon sesi kapalı." else "Navigasyon sesi açık.")
        Text(text.readiness)
        text.requestFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it) }
        OutlinedButton(onClick = onTest, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("navigation-test-voice")) { Text("Sesi test et") }
        OutlinedButton(onClick = onRetry, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("navigation-retry-voice")) { Text("Tekrar dene") }
        TextButton(onClick = onSettings, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("navigation-voice-settings")) { Text("Seslendirme ayarları") }
    }
}
