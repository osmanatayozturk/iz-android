package org.iz.navigation.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.iz.navigation.ui.IzTheme

class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IzTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Sağlık verileri ve gizlilik", style = MaterialTheme.typography.headlineSmall)
                    Text("İz, izin verirsen Samsung Health'in Health Connect'e aktardığı saat kaynaklı nabız, toplam kalori ve adım ölçümlerini yolculuk zamanlarıyla eşleştirir.")
                    Text("İlk bağlantıda erişilebilen son 30 gündeki yolculuklar ve sonrasında kaydettiğin yolculuklar okunur. Veriler bu telefonda saklanır; bağlı saatte ilgili yolculuğun özeti gösterilir. Tüm gün sağlık arşivi tutulmaz.")
                    Text("Yalnız okuma izni kullanılır. İz, Samsung Health veya Health Connect kayıtlarını değiştirmez; egzersiz başlatmaz. Arka plan izni destekleniyor ve verilmişse dönemsel eşitleme yapılır; aksi durumda uygulama açıkken okunur.")
                    Text("Saat bilgisi kaynak uygulamanın belirttiği cihaz bilgisidir; saatin bilekte olduğu süreyi doğrulamaz. Eksik veya geciken ölçümler tamamlanmaz ve tıbbi değerlendirme üretilmez.")
                    Text("Sağlık verileri OSM'ye, GPX dosyalarına veya paylaşılan fotoğraflara eklenmez. Dosya yedeğine yalnız açıkça seçersen eklenir. Otomatik bulut yedeği kapalıdır.")
                    Text("Ayarlar'dan bağlantıyı kesebilir veya yerel sağlık verilerini temizleyebilirsin. Yolculuğu silmek o yolculuğun sağlık ölçümlerini de siler. İzinlerini Health Connect'ten her zaman kaldırabilirsin.")
                    TextButton(onClick = { finish() }) { Text("Kapat") }
                }
            }
        } }
    }
}
