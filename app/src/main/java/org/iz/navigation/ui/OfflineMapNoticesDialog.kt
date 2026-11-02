package org.iz.navigation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.offline.OfflineCorridorSession
import org.iz.navigation.offline.OfflinePack
import org.iz.navigation.offline.OfflinePackStatus

/** Progress bytes do not change a profile; route/style/generation changes retire a pending read. */
private data class OfflineNoticeOwner(
    val routeId: String?, val routeFingerprint: String?, val styleUrl: String?,
    val styleFingerprint: String?, val regionId: Long?, val generation: Long?,
    val status: OfflinePackStatus?, val closureComplete: Boolean?,
)
private fun offlineNoticeOwner(pack: OfflinePack?) = OfflineNoticeOwner(
    pack?.request?.routeId, pack?.request?.sourceFingerprint, pack?.request?.styleUrl,
    pack?.closure?.styleSha256, pack?.regionId, pack?.generation, pack?.status, pack?.closure?.complete,
)
private data class OfflineNoticeRead(
    val maps: OfflineCorridorSession, val owner: OfflineNoticeOwner, val text: String?,
)

/** Displays only the producer's verified local corpus. Opening it never starts an SDK or network job. */
@Composable
internal fun OfflineMapNoticesDialog(maps: OfflineCorridorSession, onDismiss: () -> Unit) {
    val pack by maps.state.collectAsStateWithLifecycle()
    val owner = offlineNoticeOwner(pack)
    val loaded by produceState<OfflineNoticeRead?>(null, maps, owner) {
        val text = try {
            withContext(Dispatchers.IO) { maps.noticeText() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (offlineNoticeOwner(maps.state.value) == owner) value = OfflineNoticeRead(maps, owner, text)
    }
    // produceState retains its previous value on key changes; provenance is checked at rendering too.
    val current = loaded?.takeIf {
        it.maps === maps && it.owner == owner && offlineNoticeOwner(maps.state.value) == owner
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Harita ve yazı tipi lisansları") },
        text = {
            key(maps, owner) {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    when {
                        current == null -> Text("Yerel lisans metni yükleniyor.")
                        current.text.isNullOrBlank() -> Text("Bu harita profiline ait doğrulanmış yerel lisans metni kullanılamıyor.")
                        else -> SelectionContainer { Text(requireNotNull(current.text)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}
