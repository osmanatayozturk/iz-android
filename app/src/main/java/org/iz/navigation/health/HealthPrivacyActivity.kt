package org.iz.navigation.health

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.health.connect.client.HealthConnectClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.iz.navigation.ui.IzTheme

class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IzTheme {
            Surface(Modifier.fillMaxSize().testTag("privacy")) {
                Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Sağlık verileri ve gizlilik", style = MaterialTheme.typography.headlineSmall)
                    Text("Okunan ölçümler", style = MaterialTheme.typography.titleMedium)
                    Text("İz, izin verirsen Samsung Health'in Health Connect'e aktardığı saat kaynaklı nabız, toplam kalori ve adım ölçümlerini yolculuk zamanlarıyla eşleştirir.")
                    Text("İlk bağlantıda erişilebilen son 30 gündeki yolculuklar ve sonrasında kaydettiğin yolculuklar okunur. Veriler bu telefonda saklanır; bağlı saatte ilgili yolculuğun özeti gösterilir. Yalnız bugünün Samsung Health adımları, yürüyüş ve koşu egzersiz süreleri ve mesafeleri ayrıca okunur. Günlük adımlar saat veya telefon kaynaklı olabilir. Günlük özet yalnız özel önbellekte saklanır, saatte gösterilir; dosya veya bulut yedeğine eklenmez ve günlük sağlık arşivi tutulmaz.")
                    Text("Yalnız okuma izni kullanılır. İz, Samsung Health veya Health Connect kayıtlarını değiştirmez; egzersiz başlatmaz. Arka plan izni destekleniyor ve verilmişse dönemsel eşitleme yapılır; aksi durumda uygulama açıkken okunur.")
                    Text("Saat bilgisi kaynak uygulamanın belirttiği cihaz bilgisidir; saatin bilekte olduğu süreyi doğrulamaz. Eksik veya geciken ölçümler tamamlanmaz ve tıbbi değerlendirme üretilmez.")
                    HorizontalDivider()
                    Text("Saklama ve yedekleme", style = MaterialTheme.typography.titleMedium)
                    Text("Sağlık verileri OSM'ye, GPX dosyalarına veya paylaşılan fotoğraflara eklenmez. Dosya yedeğine yalnız açıkça seçersen eklenir. Otomatik bulut yedeği kapalıdır.")
                    Text("Ayarlar'dan bağlantıyı kesebilir veya yerel sağlık verilerini temizleyebilirsin. Yolculuğu silmek o yolculuğun sağlık ölçümlerini de siler. İzinlerini Health Connect'ten her zaman kaldırabilirsin.")
                    Button(onClick = ::openHealthPermissions, modifier = Modifier.fillMaxWidth()) { Text("İzinleri yönet") }
                    TextButton(onClick = { finish() }) { Text("Kapat") }
                }
            }
        } }
    }

    private fun openHealthPermissions() {
        val healthSettings = if (Build.VERSION.SDK_INT >= 34) {
            Intent(android.health.connect.HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
        } else {
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).setPackage("com.google.android.apps.healthdata")
        }
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        for (intent in listOf(healthSettings, appDetails)) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Some devices do not have a Health Connect settings activity.
            } catch (_: SecurityException) {
                // An OEM may restrict access to this settings page.
            }
        }
        Toast.makeText(this, "İzin ayarları açılamadı.", Toast.LENGTH_LONG).show()
    }
}
