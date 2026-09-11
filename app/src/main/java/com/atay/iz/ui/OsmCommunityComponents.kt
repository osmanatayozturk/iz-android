@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.atay.iz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.atay.iz.osmcommunity.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun communityDate(value: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(value))

@Composable
internal fun CommunityComposer(
    draft: CommunityDraft, canSend: Boolean, busy: Boolean, onChange: (CommunityDraft) -> Unit,
    onClose: () -> Unit, onSend: () -> Unit, onDelete: () -> Unit, onOutbox: () -> Unit, onCopy: () -> Unit,
) {
    val uncertain = draft.status == CommunityDraftStatus.UNKNOWN
    val editable = !busy && draft.status !in setOf(CommunityDraftStatus.UNKNOWN, CommunityDraftStatus.SENDING)
    var useId by rememberSaveable(draft.id) { mutableStateOf(draft.recipientId != null) }
    var recipientNumber by rememberSaveable(draft.id) { mutableStateOf(draft.recipientId?.toString().orEmpty()) }
    var copyWarning by remember { mutableStateOf(false) }
    var deleteWarning by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Taslağı sakla ve geri dön") }
            Text("Mesaj taslağı", style = MaterialTheme.typography.titleLarge)
        }
        when (draft.status) {
            CommunityDraftStatus.UNKNOWN -> Text("Gönderim sonucu belirsiz. Mesaj ulaşmış olabilir. Önce gönderilen kutunu kontrol et.", color = MaterialTheme.colorScheme.error)
            CommunityDraftStatus.SENDING -> Text("Gönderiliyor… Sonuç alınana kadar bu taslak kilitli.", color = Muted)
            CommunityDraftStatus.FAILED -> Text("Mesaj gönderilemedi. Bilgileri kontrol ederek yeniden gönderebilirsin.", color = MaterialTheme.colorScheme.error)
            else -> Text("Düzenlemelerin bu cihazda taslak olarak saklanır.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!useId, onClick = { useId = false; onChange(draft.copy(recipientId = null, recipientName = "")) },
                label = { Text("Kullanıcı adı") }, enabled = editable)
            FilterChip(useId, onClick = { useId = true; recipientNumber = ""; onChange(draft.copy(recipientId = null, recipientName = "")) },
                label = { Text("Kullanıcı kimliği") }, enabled = editable)
        }
        OutlinedTextField(if (useId) recipientNumber else draft.recipientName, onValueChange = {
            if (useId) { recipientNumber = it; onChange(draft.copy(recipientId = it.toLongOrNull()?.takeIf { id -> id > 0 }, recipientName = "")) }
            else onChange(draft.copy(recipientId = null, recipientName = it))
        }, modifier = Modifier.fillMaxWidth().testTag("community-recipient"), enabled = editable,
            label = { Text(if (useId) "Alıcı OSM kullanıcı kimliği" else "Alıcı OSM kullanıcı adı") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (useId) KeyboardType.Number else KeyboardType.Text))
        OutlinedTextField(draft.title, { onChange(draft.copy(title = it)) }, Modifier.fillMaxWidth().testTag("community-title"),
            enabled = editable, label = { Text("Konu") }, singleLine = true,
            supportingText = { Text("${CommunityText.titleLength(draft.title)}/255") }, isError = CommunityText.titleLength(draft.title) > 255)
        OutlinedTextField(draft.body, { onChange(draft.copy(body = it)) }, Modifier.fillMaxWidth().testTag("community-body"),
            enabled = editable, label = { Text("Mesaj") }, minLines = 5)
        if (uncertain) {
            OutlinedButton(onClick = onOutbox, Modifier.fillMaxWidth(), enabled = !busy) { Text("Gönderilen kutusunu kontrol et") }
            OutlinedButton(onClick = { copyWarning = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Yeni taslağa kopyala") }
        } else {
            if (!canSend) Text("Göndermek için Hesabım bölümünden mesaj izni ver.", color = Muted)
            Button(onClick = onSend, Modifier.fillMaxWidth(), enabled = canSend && editable && communityDraftCanSend(draft)) { Text("Gönder") }
        }
        TextButton(onClick = { deleteWarning = true }, enabled = !busy && draft.status != CommunityDraftStatus.SENDING) { Text("Taslağı sil") }
    }
    if (copyWarning) AlertDialog(onDismissRequest = { copyWarning = false }, title = { Text("Yeni taslak oluşturulsun mu?") },
        text = { Text("İlk mesaj ulaşmış olabilir. Aynı mesaj iki kez gidebilir. Gönderilen kutusunu kontrol ettikten sonra yeni taslak oluştur.") },
        confirmButton = { TextButton(onClick = { copyWarning = false; onCopy() }) { Text("Riski anladım, kopyala") } },
        dismissButton = { TextButton(onClick = { copyWarning = false }) { Text("Vazgeç") } })
    if (deleteWarning) AlertDialog(onDismissRequest = { deleteWarning = false }, title = { Text("Taslak silinsin mi?") },
        text = { Text("Bu cihazdaki taslak silinir. OSM'ye ulaşmış bir mesaj geri alınmaz.") },
        confirmButton = { TextButton(onClick = { deleteWarning = false; onDelete() }) { Text("Sil") } },
        dismissButton = { TextButton(onClick = { deleteWarning = false }) { Text("Vazgeç") } })
}

@Composable
internal fun CommunityMessageView(
    detail: OsmMessageDetail, accountId: Long, busy: Boolean, onClose: () -> Unit,
    onReply: () -> Unit, onRead: () -> Unit, onDelete: () -> Unit,
    onProfile: (Long) -> Unit, onLink: (String) -> Unit,
) {
    val summary = detail.summary
    var deleteWarning by remember(detail.summary.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Mesaj listesine dön") }
            Text(summary.title, style = MaterialTheme.typography.titleLarge)
        }
        TextButton(onClick = { onProfile(summary.fromId) }, enabled = !busy) { Text("Gönderen: ${summary.fromName}") }
        TextButton(onClick = { onProfile(summary.toId) }, enabled = !busy) { Text("Alıcı: ${summary.toName}") }
        Text(communityDate(summary.sentAt), style = MaterialTheme.typography.bodySmall, color = Muted)
        HorizontalDivider()
        CommunityPlainText(detail.body, onLink)
        HorizontalDivider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onReply, enabled = !busy) { Text(if (summary.fromId == accountId) "Yeni mesaj" else "Yanıtla") }
            if (summary.toId == accountId) OutlinedButton(onClick = onRead, enabled = !busy) {
                Text(if (summary.read) "Okunmadı işaretle" else "Okundu işaretle")
            }
        }
        TextButton(onClick = { onLink(CommunityLinks.mute(if (summary.fromId == accountId) summary.toName else summary.fromName)) }) {
            Text("Sessize alma · OSM sitesinde aç")
        }
        TextButton(onClick = { deleteWarning = true }, enabled = !busy) { Text("Mesajı sil") }
    }
    if (deleteWarning) AlertDialog(onDismissRequest = { deleteWarning = false }, title = { Text("Mesaj silinsin mi?") },
        text = { Text("Yalnızca senin posta kutundan silinir. Karşı tarafın kopyası korunur.") },
        confirmButton = { TextButton(onClick = { deleteWarning = false; onDelete() }) { Text("Sil") } },
        dismissButton = { TextButton(onClick = { deleteWarning = false }) { Text("Vazgeç") } })
}

@Composable
internal fun CommunityPlainText(value: String, onLink: (String) -> Unit) {
    SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    val links = remember(value) {
        Regex("https?://[^\\s<>()]+", RegexOption.IGNORE_CASE).findAll(value)
            .mapNotNull { CommunityLinks.safeExternalUrl(it.value.trimEnd('.', ',', ';', '!')) }.distinct().take(30).toList()
    }
    links.forEach { url ->
        TextButton(onClick = { onLink(url) }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun CommunityProfileView(profile: OsmCommunityProfile, own: Boolean, canMessage: Boolean,
    onMessage: () -> Unit, onLink: (String) -> Unit, onClose: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        onClose?.let { TextButton(onClick = it) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Text("Geri") } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val photo = CommunityLinks.safeImageUrl(profile.imageUrl)
            if (photo != null) AsyncImage(photo, "${profile.displayName} profil fotoğrafı", Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            else Icon(Icons.Outlined.Person, null, Modifier.size(60.dp), tint = Forest)
            Column {
                Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
                Text("OSM kullanıcı kimliği: ${profile.id}", style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("${profile.changesetCount} değişiklik kümesi", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        profile.accountCreatedAt?.let { Text("Üyelik: ${communityDate(it)}", style = MaterialTheme.typography.bodySmall, color = Muted) }
        if (profile.description.isNotBlank()) CommunityPlainText(profile.description, onLink)
        else Text("Profil açıklaması eklenmemiş.", color = Muted)
        if (!own) Button(onClick = onMessage, enabled = canMessage) { Text("Mesaj yaz") }
        OutlinedButton(onClick = { onLink(CommunityLinks.profile(profile.displayName)) }) { Text("Profil · OSM sitesinde aç") }
        if (own) OutlinedButton(onClick = { onLink(CommunityLinks.editProfile()) }) { Text("Profili düzenle · OSM sitesinde aç") }
        else {
            OutlinedButton(onClick = { onLink(CommunityLinks.follow(profile.displayName)) }) { Text("Takip et · OSM sitesinde aç") }
            TextButton(onClick = { onLink(CommunityLinks.mute(profile.displayName)) }) { Text("Sessize alma · OSM sitesinde aç") }
        }
    }
}

@Composable
internal fun CommunityNotificationSettings(enabled: Boolean, systemAllowed: Boolean, canConsume: Boolean,
    onEnabled: (Boolean) -> Unit, onSystemSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Yeni mesaj bildirimleri", style = MaterialTheme.typography.titleMedium)
                Text("Arka planda yaklaşık 15 dakikada bir kontrol edilir. Android geciktirebilir.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Switch(enabled, onEnabled, modifier = Modifier.testTag("community-notifications"))
        }
        Text(when {
            !enabled -> "Yeni mesaj kontrolü kapalı."
            !systemAllowed -> "Android bildirim izni kapalı. Tercihin açık, ancak bildirim gönderilemiyor."
            !canConsume -> "Kontrolü başlatmak için OSM mesaj okuma izni gerekiyor."
            else -> "Yeni okunmamış mesajlar için kontrol açık. Bildirimde mesaj metni gösterilmez."
        }, style = MaterialTheme.typography.bodySmall, color = Muted)
        if (!systemAllowed) TextButton(onClick = onSystemSettings) { Text("Android bildirim ayarlarını aç") }
    }
}
