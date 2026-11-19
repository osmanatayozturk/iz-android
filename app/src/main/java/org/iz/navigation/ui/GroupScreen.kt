package org.iz.navigation.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.iz.navigation.data.Transport
import org.iz.navigation.group.GroupCoordinator
import org.iz.navigation.group.GroupUiState
import org.iz.navigation.ui.history.rememberUiEditExit
import org.iz.navigation.weather.RouteStop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(group: GroupCoordinator, onClose: () -> Unit, onOpenRoute: (List<RouteStop>, Transport) -> Unit) {
    val state by group.state.collectAsStateWithLifecycle()
    DisposableEffect(group) {
        group.setScreenVisible(true)
        onDispose { group.setScreenVisible(false) }
    }
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable(state.inviteInput) { mutableStateOf(state.inviteInput) }
    val working = { if (group.state.value.groupId == null) listOf(name, code) else listOf("", group.state.value.inviteInput) }
    val back = rememberUiEditExit("group-input", working(), listOf("", state.inviteInput), state.busy,
        currentWorking = working, currentBusy = { group.state.value.busy },
        discardUnsaved = { name = ""; code = group.state.value.inviteInput }, exit = onClose)

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (state.groupId == null) "Grup oluştur veya katıl" else "Grup yolculuğu") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Geri") } },
            actions = { IconButton(onClick = group::refresh, enabled = state.configured && !state.busy) {
                Icon(Icons.Outlined.Refresh, "Grubu yenile")
            } })
    }) { padding ->
        key(state.groupId) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)
                .testTag(if (state.groupId == null) "group" else "groupactive"),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!state.configured) Text("Bu kurulumda grup sunucusu yapılandırılmamış. Harita, navigasyon ve kayıt kullanılabilir.")
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.groupId == null) {
                    GroupEntry(state, name, { name = it.take(40) }, code,
                        { code = it.uppercase().filter(Char::isLetterOrDigit).take(8) },
                        { group.create(name) }, { group.join(name, code) })
                } else {
                    ActiveGroup(state, group, onOpenRoute)
                }
            }
        }
    }
}

@Composable
private fun GroupEntry(state: GroupUiState, name: String, onName: (String) -> Unit, code: String,
    onCode: (String) -> Unit, create: () -> Unit, join: () -> Unit) {
    var joining by rememberSaveable(state.inviteInput) { mutableStateOf(state.inviteInput.isNotBlank()) }
    Text("En fazla 10 kişi · 24 saat", style = MaterialTheme.typography.titleMedium)
    Text("Gruba katılınca konum paylaşımı kapalı kalır.", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(value = name, onValueChange = onName, label = { Text("Görünen adın") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!joining, { joining = false }, { Text("Yeni grup") }, enabled = !state.busy)
        FilterChip(joining, { joining = true }, { Text("Davetle katıl") }, enabled = !state.busy)
    }
    if (joining) {
        OutlinedTextField(value = code, onValueChange = onCode, label = { Text("8 karakterli davet kodu") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
        Text("QR kodunu telefonun kamerasıyla açabilirsin. Katılım isteğini grup sahibi onaylar.")
        Button(onClick = join, enabled = state.configured && !state.busy && name.isNotBlank() && code.length == 8,
            modifier = Modifier.fillMaxWidth()) { Text("Katılma isteği gönder") }
    } else {
        Text("Ortak duraklar", style = MaterialTheme.typography.titleMedium)
        if (state.preparedStops.isEmpty()) Text("Haritada bir rota planlayıp Grup düğmesine dokun.")
        else GroupStops(state.preparedStops)
        Button(onClick = create, enabled = state.configured && !state.busy && name.isNotBlank() && state.preparedStops.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()) { Text("Bu hedeflerle grup oluştur") }
    }
}

private enum class GroupSection(val title: String) { ROUTE("Ortak duraklar"), MEMBERS("Üyeler"), INVITE("Davet"), SHARING("Konum paylaşımı") }

@Composable
private fun ActiveGroup(state: GroupUiState, group: GroupCoordinator, onOpenRoute: (List<RouteStop>, Transport) -> Unit) {
    var section by rememberSaveable { mutableStateOf<GroupSection?>(null) }
    var consent by remember { mutableStateOf(false) }
    var exit by remember { mutableStateOf(false) }
    Text("Bitiş · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.expiresAt)))
    if (state.selfStatus == "pending") Text("Grup sahibinin onayı bekleniyor. Konum paylaşımı kapalı.")
    val approved = state.selfStatus == "approved"
    val visible = GroupSection.entries.filter { when (it) {
        GroupSection.INVITE -> state.isHost
        GroupSection.SHARING, GroupSection.ROUTE -> approved
        GroupSection.MEMBERS -> true
    } }
    visible.forEach { item ->
        val expanded = section == item
        val label = when (item) {
            GroupSection.MEMBERS -> "${item.title} · ${state.members.count { it.status == "approved" }}/10"
            GroupSection.SHARING -> "${item.title} · ${if (state.sharing) "Açık" else "Kapalı"}"
            GroupSection.ROUTE -> "${item.title} · ${state.stops.size}"
            else -> item.title
        }
        Surface(onClick = { section = if (expanded) null else item }, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "Kapat" else "Aç", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (expanded) when (item) {
            GroupSection.MEMBERS -> GroupMembers(state, group)
            GroupSection.ROUTE -> GroupRoute(state, group, onOpenRoute)
            GroupSection.INVITE -> GroupInvite(state, group::renewInvite)
            GroupSection.SHARING -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.sharing && state.connected) "Canlı konum bağlı" else if (state.sharing) "Bağlanıyor…" else "Canlı konumu paylaş", Modifier.weight(1f))
                    Switch(state.sharing, { if (it) consent = true else group.setSharing(false) },
                        enabled = !state.busy && state.pendingExit == null && (state.canShare || state.sharing))
                }
                if (!state.canShare) Text("Paylaşmak için navigasyon veya yolculuk başlat. Günlük kaydı isteğe bağlıdır.")
                Text("Konum hareket halinde 10, dururken 30 saniyede bir gönderilir. İşaret 30 saniye sonra gecikmiş görünür, 5 dakika sonra gizlenir. Konum geçmişi saklanmaz.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    OutlinedButton(onClick = { exit = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.isHost) "Grubu bitir" else "Gruptan ayrıl")
    }
    if (consent && approved && state.pendingExit == null) AlertDialog(onDismissRequest = { consent = false },
        title = { Text("Konumunu bu grupla paylaş?") },
        text = { Text("Onaylı ve paylaşımı açık üyeler canlı konumunu görebilir. Yolculuk bitince, gruptan ayrılınca veya paylaşımı kapatınca aktarım durur. Daha önce teslim edilmiş konumlar geri alınamaz.") },
        confirmButton = { TextButton(enabled = state.canShare && !state.busy, onClick = { consent = false; group.setSharing(true) }) { Text("Paylaşımı aç") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Vazgeç") } })
    if (exit) AlertDialog(onDismissRequest = { exit = false }, title = { Text(if (state.isHost) "Grubu bitir?" else "Gruptan ayrıl?") },
        text = { Text(if (state.isHost) "Davetler ve tüm üyelerin paylaşımı kapanır. Yeni yolculuk için yeni grup gerekir." else "Grup üyeliğin sona erer ve konum paylaşımın kapanır.") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { exit = false; if (state.isHost) group.end() else group.leave() }) { Text(if (state.isHost) "Grubu bitir" else "Ayrıl") } },
        dismissButton = { TextButton(onClick = { exit = false }) { Text("Vazgeç") } })
}

@Composable
private fun GroupStops(stops: List<RouteStop>) {
    stops.forEachIndexed { index, stop ->
        Text("${index + 1}. ${stop.label}", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GroupRoute(state: GroupUiState, group: GroupCoordinator, onOpenRoute: (List<RouteStop>, Transport) -> Unit) {
    val scope = rememberCoroutineScope()
    var transport by rememberSaveable { mutableStateOf(state.transport.takeIf { it != Transport.UNKNOWN } ?: Transport.MOTORCYCLE) }
    var modeMenu by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf(false) }
    GroupStops(state.stops)
    Box {
        OutlinedButton(onClick = { modeMenu = true }) { Text(transport.label()) }
        DropdownMenu(modeMenu, onDismissRequest = { modeMenu = false }) {
            transportDisplayOrder.forEach { item -> DropdownMenuItem(text = { Text(item.label()) }, onClick = { transport = item; modeMenu = false }) }
        }
    }
    Button(onClick = {
        scope.launch {
            val stops = group.routeFromCurrentLocation()
            routeError = stops == null
            if (stops != null && group.state.value.groupId == state.groupId) onOpenRoute(stops, transport)
        }
    }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Konumumdan rota planla") }
    if (routeError) Text("Güncel konum bekleniyor. Ana haritadan konumunu bulup tekrar dene.", color = MaterialTheme.colorScheme.error)
}

@Composable
private fun GroupMembers(state: GroupUiState, group: GroupCoordinator) {
    state.members.forEach { member ->
        val marker = state.markers.firstOrNull { it.userId == member.userId }
        val status = when {
            member.status == "pending" -> "Onay bekliyor"
            member.userId == state.selfId -> if (state.sharing) "Konumun paylaşılıyor" else "Paylaşım kapalı"
            !member.consent -> "Paylaşım kapalı"
            marker == null -> "Çevrimdışı / konum bekleniyor"
            marker.delayed -> "Konum gecikmiş"
            else -> "Canlı"
        }
        Text(member.name + if (member.userId == state.hostId) " · Grup sahibi" else "", style = MaterialTheme.typography.titleSmall)
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (state.isHost && member.userId != state.selfId) Row {
            if (member.status == "pending") TextButton(onClick = { group.approve(member.userId) }, enabled = !state.busy) { Text("Onayla") }
            TextButton(onClick = { group.remove(member.userId) }, enabled = !state.busy) { Text("Çıkar") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun GroupInvite(state: GroupUiState, renew: () -> Unit) {
    val context = LocalContext.current
    val valid by produceState(false, state.inviteCode, state.inviteExpiresAt) {
        val remaining = state.inviteExpiresAt - System.currentTimeMillis()
        value = state.inviteCode.isNotBlank() && remaining > 0
        if (value) { delay(remaining); value = false }
    }
    if (valid) {
        val link = "iz://group?code=${state.inviteCode}"
        Text(state.inviteCode, style = MaterialTheme.typography.headlineMedium)
        Text("Geçerlilik · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.inviteExpiresAt)))
        val qr = remember(link) {
            val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 240, 240)
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        Image(qr, "Grup davet QR kodu", Modifier.size(200.dp))
        OutlinedButton(onClick = {
            if (state.inviteExpiresAt > System.currentTimeMillis()) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "İz grubuma katıl: $link\nDavet kodu: ${state.inviteCode}")
                context.startActivity(Intent.createChooser(send, "Grup davetini paylaş"))
            }
        }) { Text("Daveti paylaş") }
    } else Text("Geçerli davet kodu yok.")
    Text("Yeni kod 15 dakika geçerlidir. Yenilemek eski kodu kapatır.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = renew, enabled = !state.busy) { Text("Kodu yenile") }
}
