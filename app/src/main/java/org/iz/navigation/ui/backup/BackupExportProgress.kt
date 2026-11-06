package org.iz.navigation.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun BackupExportProgress(phase: BackupExportPhase, cancel: () -> Unit) {
    val title = when (phase) {
        BackupExportPhase.IDLE -> return
        BackupExportPhase.PREPARING, BackupExportPhase.READY -> "Yedek hazırlanıyor"
        BackupExportPhase.PICKER -> "Kaydedilecek konumu seç"
        BackupExportPhase.SAVING -> "Yedek kaydediliyor ve doğrulanıyor"
        BackupExportPhase.CLEANING -> "Temizlik tamamlanıyor"
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text("İşlem tamamlanana kadar lütfen bekle.")
            }
        },
        confirmButton = {},
        dismissButton = {
            if (phase in BackupExportFlow.cancellablePhases) {
                TextButton(onClick = cancel) { Text("Vazgeç") }
            }
        },
    )
}

internal fun BackupExportNotice.message(): String = buildList<String> {
    val message = when (result) {
        BackupExportResult.SAVED -> "Yedek kaydedildi ve doğrulandı."
        BackupExportResult.CANCELLED -> "Yedekleme iptal edildi."
        BackupExportResult.INTERRUPTED -> "Yedekleme yarıda kaldı. Yeniden başlatabilirsin."
        BackupExportResult.PREPARATION_FAILED -> "Yedek hazırlanamadı."
        BackupExportResult.PICKER_FAILED -> "Dosya seçici açılamadı."
        BackupExportResult.DESTINATION_UNCONFIRMED ->
            "Yedek kaydedilemedi. Önerilen adı koruyarak başka bir konumda yeni bir dosya oluştur."
        BackupExportResult.WRITE_FAILED -> "Yedek kaydedilemedi"
        BackupExportResult.VERIFY_FAILED -> "Yedek doğrulanamadı"
        null -> null
    }
    message?.let(::add)
    preparationDetail?.let(::add)
    if (destinationMayRemain) {
        add("Seçtiğin konumda dosya kalmış olabilir. Bu dosyayı yedek olarak kullanma.")
    }
    if (privateCleanupFailed) add("Uygulamanın geçici yedek dosyası temizlenemedi.")
}.joinToString("\n")
