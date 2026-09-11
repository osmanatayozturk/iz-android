@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.atay.iz.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atay.iz.integration.osm.OsmAuthManager
import com.atay.iz.osmcommunity.*

@Composable
internal fun OsmCommunityScreen(onClose: () -> Unit, initialMessageId: Long? = null,
    initialAccountId: Long? = null, onOpenSettings: () -> Unit = onClose,
    vm: OsmCommunityViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val auth = remember { OsmAuthManager.get(context) }
    val authState by auth.state.collectAsStateWithLifecycle()
    val community by vm.community.collectAsStateWithLifecycle()
    val rawUi by vm.ui.collectAsStateWithLifecycle()
    // Room/auth may emit before the VM observer: never render another account's detail for even one frame.
    val ui = if (rawUi.accountId == community.accountId && rawUi.cacheIdentity == community.cacheIdentity) rawUi
        else CommunityUiState(accountId = community.accountId, cacheIdentity = community.cacheIdentity)
    val notificationStatus = remember { CommunityNotifications(context) }
    var systemAllowed by remember { mutableStateOf(notificationStatus.permissionGranted()) }
    val permissionPreferences = remember { context.getSharedPreferences("osm-community-ui", Context.MODE_PRIVATE) }
    var notificationHandled by rememberSaveable(initialMessageId, initialAccountId) { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        systemAllowed = notificationStatus.permissionGranted()
        vm.repository.refreshNotificationSchedule()
    }
    fun close() { vm.persistEditor(); onClose() }
    fun login() {
        val activity = context.findActivity()
        if (activity == null) vm.reportError("OSM giriş ekranı açılamadı.")
        else auth.startCommunityLogin(activity)
    }
    fun openLink(value: String) {
        val safe = CommunityLinks.safeExternalUrl(value) ?: return
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(safe)) }
            .onFailure { vm.reportError("Bağlantı açılamadı. Telefonda bir tarayıcı olduğundan emin ol.") }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        systemAllowed = notificationStatus.permissionGranted()
        vm.repository.refreshNotificationSchedule()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.persistEditor() }
    DisposableEffect(vm) { onDispose { vm.persistEditor() } }
    LaunchedEffect(community.accountId, community.canConsume, community.cacheIdentity) {
        if (community.accountId != null && community.ready && initialMessageId == null) vm.refresh()
        if (community.accountId != null && community.canConsume && community.notificationsEnabled &&
            Build.VERSION.SDK_INT >= 33 && !systemAllowed &&
            !permissionPreferences.getBoolean("notification-permission-requested", false)) {
            permissionPreferences.edit().putBoolean("notification-permission-requested", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(initialMessageId, initialAccountId, community.accountId, community.ready, community.cacheIdentity, authState.user?.id, ui.busy) {
        if (initialMessageId != null && !notificationHandled && !ui.busy) {
            // On cold start the persisted session can precede the private repository state.
            if (community.accountId == null && authState.user?.id == initialAccountId) return@LaunchedEffect
            if (community.accountId == initialAccountId && !community.ready) return@LaunchedEffect
            notificationHandled = true
            vm.openNotification(initialMessageId, initialAccountId)
        }
    }
    BackHandler { if (!vm.back()) close() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!vm.back()) close() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "OSM topluluğundan geri dön") }
            Column(Modifier.weight(1f)) {
                Text("OSM Topluluğu", style = MaterialTheme.typography.titleLarge)
                Text(authState.user?.displayName ?: "OpenStreetMap insanlarıyla bağlantı kur", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            IconButton(onClick = vm::refresh, enabled = community.accountId != null && !ui.busy) { Icon(Icons.Outlined.Refresh, "Topluluğu yenile") }
        }
        TabRow(selectedTabIndex = ui.section.ordinal) {
            listOf("Mesajlar", "Kişiler", "Hesabım").forEachIndexed { index, label ->
                Tab(ui.section.ordinal == index, onClick = { vm.section(CommunitySection.entries[index]) }, text = { Text(label) })
            }
        }
        if (ui.busy || authState.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val error = ui.error ?: authState.error
        if (error != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { vm.clearError(); auth.clearError() }) { Icon(Icons.Outlined.Close, "Hata bilgisini kapat") }
            }
        }
        ui.notice?.let { Text(it, Modifier.padding(16.dp), color = Forest) }
        when {
            community.accountId != null && !community.ready -> Text("Kayıtlı topluluk bilgileri yükleniyor…", Modifier.padding(20.dp), color = Muted)
            ui.editor != null && community.accountId != null -> CommunityComposer(ui.editor, community.canSend, ui.busy,
                vm::changeDraft, { vm.back() }, vm::send, vm::deleteDraft,
                { vm.folder(CommunityFolder.OUTBOX); vm.refresh() }, vm::copyUncertainDraft)
            ui.detail != null && community.accountId != null -> CommunityMessageView(ui.detail, community.accountId!!, ui.busy,
                { vm.back() }, vm::reply, vm::markRead, vm::deleteMessage, vm::openProfile, ::openLink)
            ui.person != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                CommunityProfileView(ui.person, ui.person.id == community.accountId, community.accountId != null && !ui.busy,
                    { vm.compose(ui.person.id) }, ::openLink, { vm.back() })
            }
            ui.section == CommunitySection.ACCOUNT -> CommunityAccount(community, systemAllowed, ui.busy || authState.busy,
                authState.clientIdConfigured, authState.user?.displayName, ::login, { vm.persistEditor(); auth.disconnect() },
                onOpenSettings, vm::notifications, {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    context.startActivity(intent)
                }, ::openLink)
            community.accountId == null -> CommunityConnect(authState.clientIdConfigured, authState.busy, ::login, onOpenSettings)
            ui.section == CommunitySection.CONTACTS -> CommunityContacts(community.contacts, ui.busy,
                vm::openProfile, {
                    runCatching { CommunityLinks.profile(it) }.onSuccess(::openLink)
                        .onFailure { vm.reportError("Geçerli bir OSM kullanıcı adı yaz.") }
                }, ::openLink)
            else -> CommunityMailboxes(community, ui, vm::folder, vm::openMessage, vm::editDraft, { vm.compose() }, vm::loadMore, ::login)
        }
    }
}

@Composable
private fun CommunityConnect(configured: Boolean, busy: Boolean, onLogin: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Outlined.Forum, null, Modifier.size(48.dp), tint = Forest)
        Text("Topluluğunla konuş", style = MaterialTheme.typography.headlineMedium)
        Text("OSM hesabını bağla; mesajlarını oku, yanıtla ve birlikte harita yaptığın kişilerin profillerini keşfet.", color = Muted)
        if (configured) Button(onClick = onLogin, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("OSM hesabını bağla") }
        else {
            Text("OSM uygulama bağlantısı önce Ayarlar bölümünde yapılandırılmalı.", color = Muted)
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("OSM bağlantı ayarlarını aç") }
        }
        Text("Mesajlar ve taslaklar günlük yedeğine eklenmez. Hesap bağlantısını kesince bu cihazdaki topluluk verileri temizlenir.", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun CommunityMailboxes(state: CommunityState, ui: CommunityUiState,
    onFolder: (CommunityFolder) -> Unit, onMessage: (Long) -> Unit, onDraft: (CommunityDraft) -> Unit,
    onCompose: () -> Unit, onMore: () -> Unit, onLogin: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Gelen", "Gönderilen", "Taslaklar").forEachIndexed { index, label ->
                FilterChip(ui.folder.ordinal == index, { onFolder(CommunityFolder.entries[index]) }, { Text(label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (ui.folder) { CommunityFolder.INBOX -> "${state.inbox.count { !it.read }} okunmamış"; CommunityFolder.OUTBOX -> "Gönderdiğin mesajlar"; CommunityFolder.DRAFTS -> "Bu cihazdaki taslaklar" },
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Muted)
            TextButton(onClick = onCompose, enabled = !ui.busy) { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Yeni mesaj") }
        }
        if (!state.canConsume && ui.folder != CommunityFolder.DRAFTS) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mesajlarını görmek için OSM mesaj okuma izni ver.", color = Muted)
                OutlinedButton(onClick = onLogin, enabled = !ui.busy) { Text("Mesaj izinlerini ver") }
            }
        }
        val messages = if (ui.folder == CommunityFolder.OUTBOX) state.outbox else state.inbox
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.folder == CommunityFolder.DRAFTS) {
                if (state.drafts.isEmpty()) item { Text("Henüz taslağın yok. Yeni mesaj yazarak başlayabilirsin.", color = Muted) }
                items(state.drafts, key = { it.id }) { draft ->
                    Surface(onClick = { onDraft(draft) }, enabled = !ui.busy, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(draft.title.ifBlank { "Konusuz taslak" }, style = MaterialTheme.typography.titleMedium)
                            Text(draft.recipientId?.let { "Alıcı kimliği: $it" } ?: draft.recipientName.ifBlank { "Alıcı seçilmedi" }, style = MaterialTheme.typography.bodySmall)
                            Text(when (draft.status) { CommunityDraftStatus.DRAFT -> "Taslak"; CommunityDraftStatus.SENDING -> "Gönderiliyor"; CommunityDraftStatus.UNKNOWN -> "Gönderim belirsiz · Kontrol gerekli"; CommunityDraftStatus.FAILED -> "Gönderilemedi" },
                                color = if (draft.status == CommunityDraftStatus.UNKNOWN) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                if (messages.isEmpty() && state.canConsume) item {
                    Text(if (ui.busy) "Mesajlar yükleniyor…" else if (ui.folder == CommunityFolder.INBOX) "Gelen kutun boş." else "Henüz gönderilmiş mesajın yok.", color = Muted)
                }
                items(messages, key = { it.id }) { message ->
                    Surface(onClick = { onMessage(message.id) }, enabled = !ui.busy, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (ui.folder == CommunityFolder.OUTBOX) message.toName else message.fromName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                if (ui.folder == CommunityFolder.INBOX && !message.read) Text("Yeni", color = Forest, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(message.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (ui.folder == CommunityFolder.INBOX && !message.read) FontWeight.Bold else FontWeight.Normal)
                            Text(communityDate(message.sentAt), style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                }
                val more = if (ui.folder == CommunityFolder.OUTBOX) state.nextOutboxFromId else state.nextInboxFromId
                if (more != null) item { OutlinedButton(onClick = onMore, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Daha eski mesajları yükle") } }
            }
        }
    }
}

@Composable
private fun CommunityContacts(contacts: List<OsmCommunityProfile>, busy: Boolean,
    onProfile: (Long) -> Unit, onName: (String) -> Unit, onLink: (String) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var byId by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Bir haritacı bul", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!byId, { byId = false; search = "" }, { Text("Kullanıcı adı") })
                FilterChip(byId, { byId = true; search = "" }, { Text("Kullanıcı kimliği") })
            }
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text(if (byId) "OSM kullanıcı kimliği" else "OSM kullanıcı adı") }, singleLine = true)
            OutlinedButton(onClick = { if (byId) search.toLongOrNull()?.let(onProfile) else onName(search.trim()) },
                enabled = !busy && if (byId) (search.toLongOrNull() ?: 0) > 0 else search.isNotBlank()) {
                Text(if (byId) "Profili göster" else "Profili OSM sitesinde aç")
            }
        }
        item {
            OutlinedButton(onClick = { onLink(CommunityLinks.following()) }) { Text("Takip ettiklerim · OSM sitesinde aç") }
            Text("Mesajlaştığın kişiler", style = MaterialTheme.typography.titleMedium)
            Text("Bu liste mesaj kutularından oluşur. Takip listesi OSM sitesinde yönetilir.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (contacts.isEmpty()) item { Text("Mesaj kutularını yeniledikçe kişiler burada görünür.", color = Muted) }
        items(contacts, key = { it.id }) { person ->
            ListItem(headlineContent = { Text(person.displayName) }, supportingContent = { Text("OSM ${person.id}") },
                leadingContent = { Icon(Icons.Outlined.Person, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                modifier = Modifier.clickable(enabled = !busy) { onProfile(person.id) })
        }
    }
}

@Composable
private fun CommunityAccount(state: CommunityState, systemAllowed: Boolean, busy: Boolean,
    configured: Boolean, displayName: String?, onLogin: () -> Unit, onDisconnect: () -> Unit,
    onSettings: () -> Unit, onNotifications: (Boolean) -> Unit, onSystemSettings: () -> Unit, onLink: (String) -> Unit,
) {
    if (state.accountId == null) { CommunityConnect(configured, busy, onLogin, onSettings); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val profile = state.profile
        if (profile != null) CommunityProfileView(profile, true, false, {}, onLink)
        else {
            Text(displayName ?: "OSM hesabın", style = MaterialTheme.typography.titleLarge)
            Text(if (busy) "Profil yükleniyor…" else "Profil bilgilerini almak için yenile.", color = Muted)
        }
        if (!state.canConsume || !state.canSend) {
            Text("Mesajları okumak ve göndermek için ek OSM izni gerekiyor.", color = Muted)
            Button(onClick = onLogin, enabled = !busy) { Text("Mesaj izinlerini ver") }
        }
        OutlinedButton(onClick = { onLink(CommunityLinks.following()) }) { Text("Takip ettiklerim · OSM sitesinde aç") }
        TextButton(onClick = { onLink(CommunityLinks.mutedInbox()) }) { Text("Sessize alınanlar · OSM sitesinde aç") }
        HorizontalDivider()
        CommunityNotificationSettings(state.notificationsEnabled, systemAllowed, state.canConsume, onNotifications, onSystemSettings)
        HorizontalDivider()
        Text("Hesap bağlantısını kesince bu cihazdaki mesajlar, kişiler ve taslaklar temizlenir. Günlüğün korunur.", style = MaterialTheme.typography.bodySmall, color = Muted)
        TextButton(onClick = onDisconnect, enabled = !busy) { Text("Hesap bağlantısını kes") }
        TextButton(onClick = onSettings) { Text("OSM bağlantı ayarlarını aç") }
    }
}
