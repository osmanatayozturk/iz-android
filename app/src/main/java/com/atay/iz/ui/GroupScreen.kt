package com.atay.iz.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atay.iz.data.Transport
import com.atay.iz.group.GroupCoordinator
import com.atay.iz.weather.RouteStop
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(group: GroupCoordinator, onClose: () -> Unit, onOpenRoute: (List<RouteStop>, Transport) -> Unit) {
    val state by group.state.collectAsStateWithLifecycle()
    DisposableEffect(group) { group.setScreenVisible(true); onDispose { group.setScreenVisible(false) } }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable(state.inviteInput) { mutableStateOf(state.inviteInput) }
    var consentDialog by remember { mutableStateOf(false) }
    var endDialog by remember { mutableStateOf(false) }
    var transport by rememberSaveable { mutableStateOf(Transport.MOTORCYCLE) }
    var modeMenu by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf(false) }
    val modes=listOf(Transport.CAR to "Araba",Transport.MOTORCYCLE to "Motosiklet",Transport.BICYCLE to "Bisiklet",Transport.WALK to "Yürüyüş",Transport.RUN to "Koşu",Transport.PASSENGER to "Yolcu")
    Scaffold(topBar={ TopAppBar(title={ Text("Grup yolculuğu") },navigationIcon={ TextButton(onClick=onClose) { Text("Kapat") } },actions={ TextButton(onClick=group::refresh,enabled=state.configured && !state.busy) { Text("Yenile") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal=20.dp).fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Ortak hedef, herkesin kendi rotası",style=MaterialTheme.typography.titleLarge)
            Text("En fazla 10 kişi · En fazla 24 saat. Gruba katılmak konum paylaşımını açmaz.")
            if (!state.configured) {
                Card { Text("Grup sunucusu henüz yapılandırılmadı. Harita, navigasyon ve kayıt kullanılabilir.",Modifier.padding(16.dp)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if (state.groupId==null) {
                OutlinedTextField(value=name,onValueChange={name=it.take(40)},label={Text("Görünen adın")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text("Yeni grup",style=MaterialTheme.typography.titleMedium)
                if (state.preparedStops.isEmpty()) Text("Grup oluşturmak için haritada bir rota planlayıp Grup düğmesine dokun.")
                else state.preparedStops.forEachIndexed { i, stop -> Text("${i+1}. ${stop.label}") }
                Button(onClick={group.create(name)},enabled=state.configured && !state.busy && name.isNotBlank() && state.preparedStops.isNotEmpty()) { Text("Bu hedeflerle grup oluştur") }
                HorizontalDivider()
                Text("Davetle katıl",style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(value=code,onValueChange={code=it.uppercase().filter(Char::isLetterOrDigit).take(8)},label={Text("8 karakterli davet kodu")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Button(onClick={group.join(name,code)},enabled=state.configured && !state.busy && name.isNotBlank() && code.length==8) { Text("Katılma isteği gönder") }
                Text("QR kodunu telefonun kamera uygulamasıyla açabilirsin. Katılım için ayrıca onay vermen gerekir.",style=MaterialTheme.typography.bodySmall)
            } else {
                val expiry=remember(state.expiresAt) { SimpleDateFormat("dd MMM HH:mm",Locale.getDefault()).format(Date(state.expiresAt)) }
                Text("Bitiş: $expiry")
                if (state.selfStatus=="pending") Text("Grup sahibinin onayı bekleniyor. Konum paylaşılmıyor.")
                if (state.isHost) {
                    Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Davet kodu: ${state.inviteCode}",style=MaterialTheme.typography.titleLarge)
                        Text("Davet 15 dakika geçerli. Yenilemek eski kodu kapatır.",style=MaterialTheme.typography.bodySmall)
                        val link="iz://group?code=${state.inviteCode}"
                        val qr=remember(link) { runCatching {
                            val matrix=QRCodeWriter().encode(link,BarcodeFormat.QR_CODE,400,400)
                            Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply { setPixels(IntArray(400*400) { index -> if(matrix[index%400,index/400]) android.graphics.Color.BLACK else android.graphics.Color.WHITE },0,400,0,0,400,400) }.asImageBitmap()
                        }.getOrNull() }
                        qr?.let { Image(it,"Grup davet QR kodu",Modifier.size(200.dp)) }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,"İz grubuma katıl: $link\nDavet kodu: ${state.inviteCode}") },"Grup davetini paylaş"))}) { Text("Daveti paylaş") }
                            TextButton(onClick=group::renewInvite,enabled=!state.busy) { Text("Kodu yenile") }
                        }
                    } }
                }
                if (state.selfStatus=="approved") {
                    Text("Ortak duraklar (bu yolculukta sabit)",style=MaterialTheme.typography.titleMedium)
                    state.stops.forEachIndexed { i, stop -> Text("${i+1}. ${stop.label}") }
                    Box {
                        OutlinedButton(onClick={modeMenu=true}) { Text(modes.firstOrNull { it.first==transport }?.second ?: "Ulaşım") }
                        DropdownMenu(expanded=modeMenu,onDismissRequest={modeMenu=false}) { modes.forEach { (value,label) -> DropdownMenuItem(text={Text(label)},onClick={transport=value;modeMenu=false}) } }
                    }
                    Button(onClick={scope.launch { val stops=group.routeFromCurrentLocation(); if(stops==null) routeError=true else {routeError=false;onOpenRoute(stops,transport)} }},enabled=!state.busy) { Text("Konumumdan rota planla") }
                    if(routeError) Text("Güncel konum bekleniyor. Ana haritadan konumunu bulup tekrar dene.",color=MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text("Canlı konum paylaşımı"); Text(if(state.sharing) "Açık · ${if(state.connected) "Bağlı" else "Bağlanıyor"}" else "Kapalı",style=MaterialTheme.typography.bodySmall) }
                        Switch(checked=state.sharing,onCheckedChange={if(it) consentDialog=true else group.setSharing(false)},enabled=!state.busy && state.pendingExit==null && (state.canShare || state.sharing))
                    }
                    if(!state.canShare) Text("Paylaşmak için navigasyon veya yolculuk başlat. Günlük kaydı zorunlu değil.",style=MaterialTheme.typography.bodySmall)
                    Text("Hareket halinde 10 sn, dururken 30 sn. Geciken işaret 30 sn sonra belirtilir, 5 dk sonra gizlenir. Konum geçmişi saklanmaz.",style=MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text("Üyeler (${state.members.count { it.status=="approved" }}/10)",style=MaterialTheme.typography.titleMedium)
                state.members.forEach { member ->
                    val marker=state.markers.firstOrNull { it.userId==member.userId }
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(member.name+if(member.userId==state.hostId) " · Grup sahibi" else "")
                        Text(when { member.status=="pending" -> "Onay bekliyor"; member.userId==state.selfId -> if(state.sharing) "Konumun paylaşılıyor" else "Paylaşım kapalı"; !member.consent -> "Paylaşım kapalı"; marker==null -> "Çevrimdışı / konum bekleniyor"; marker.delayed -> "Konum gecikmiş"; else -> "Canlı" },style=MaterialTheme.typography.bodySmall)
                        if(state.isHost && member.userId!=state.selfId) Row {
                            if(member.status=="pending") TextButton(onClick={group.approve(member.userId)},enabled=!state.busy) { Text("Onayla") }
                            TextButton(onClick={group.remove(member.userId)},enabled=!state.busy) { Text("Çıkar") }
                        }
                    } }
                }
                OutlinedButton(onClick={if(state.isHost) endDialog=true else group.leave()},enabled=!state.busy) { Text(if(state.isHost) "Grubu bitir" else "Gruptan ayrıl") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if(consentDialog) AlertDialog(onDismissRequest={consentDialog=false},title={Text("Konumunu bu grupla paylaş?")},text={Text("Onaylı ve paylaşımı açık üyeler canlı konumunu görebilir. Bu yolculuk bitince, gruptan ayrılınca veya paylaşımı kapatınca durur. Daha önce teslim edilmiş konumlar geri alınamaz. Konum geçmişi yüklenmez.")},confirmButton={TextButton(onClick={consentDialog=false;group.setSharing(true)}) {Text("Paylaşımı aç")}},dismissButton={TextButton(onClick={consentDialog=false}) {Text("Vazgeç")}})
    if(endDialog) AlertDialog(onDismissRequest={endDialog=false},title={Text("Grubu bitir?")},text={Text("Davetler ve tüm üyelerin paylaşımı kapanır. Yeni yolculuk için yeni grup gerekir.")},confirmButton={TextButton(onClick={endDialog=false;group.end()}) {Text("Grubu bitir")}},dismissButton={TextButton(onClick={endDialog=false}) {Text("Vazgeç")}})
}



