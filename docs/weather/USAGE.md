# İz 0.9.0 · Yolculuk havası

Harita ekranındaki **Yolculuk havası** düğmesi rota planını ve rota üzerindeki tahmini hava koşullarını açar. Araba, motosiklet, bisiklet, yürüyüş, koşu ve yolcu için kullanılabilir. Açık bir yolculuk varsa ekran ilk açılışta onun türünü seçer; başka bir türün planını da hazırlayabilirsin.

1. **Yolculuk türü** bölümünden altı türden birini seç. Türü değiştirince başlangıç, hedef ve kalkış zamanı korunur; rota ve hava sonuçları yeniden hesaplanmalıdır.
2. Başlangıç ve hedef seç. İstersen üç ara durak ekle; arama, kayıtlı yerler veya haritadan seçim kullanılabilir.
3. Kalkış saatini seç ve rotanın havasını hesapla. Trafik açıksa uygun araç türlerinde TomTom kullanılır. Bir temel rotadan yedi yaklaşık hava seçeneği çıkarılır; dokunduğun başka kalkış için rota ve hava birlikte yeniden hesaplanır. Hava önerisi otomatik seçilmez. [Matrix durak sırası ve yeni planlama kuralları](../IZ_090_YENILIKLER.md).
4. Haritadaki hava noktaları, o noktaya tahmini varış saatindeki tahmini gösterir. Yağış yüzdesi, rota örneklerindeki en yüksek olasılıktır. Haritaya dokunarak tam ekran açabilir, haritayı kapatmadan kaydırıp yakınlaştırabilirsin; nokta bilgileri ve seçimler açılır pencerelerde gösterilir.
5. **[Seçilen tür] yolculuğunu şimdi başlat**, güncel konumunu alır ve şimdi başlayacağın yolculuk için rotayı yeniden hesaplar. Aynı türde mevcut ve onaylanmış bir kayda bağlanır; açık kayıt yoksa seçtiğin türde yeni kayıt başlatır. Başka türde açık bir kayıt varsa ona farklı türün planını bağlamaz.
6. Canlı kartta yolculuk türü, kalan mesafe, varış saati ve hava durumu görünür. Hava takibini durdurmak, yolculuk günlüğünün GPS kaydını bitirmez. Başka tür için plan hazırlarken o türün rota önizlemesi gösterilir; açık yolculuğun canlı kartında kendi türü yazmaya devam eder.

## Her türe ayrı ayarlar

Tür seçiminin altındaki **[Tür] · Hava ayarları** düğmesini aç. Yağış olasılığı, yağış miktarı, rüzgâr, hamle, soğuk ve sıcak eşikleri her tür için ayrı kaydedilir. Bir türün ayarını değiştirmek diğerlerini değiştirmez. Önceki sürümdeki motosiklet ayarların motosiklet için korunur; diğer türlere kopyalanmaz.

Başlangıç eşikleri yolculuk türüne göre değişir:

| Tür | Yağış olasılığı | Yağış | Rüzgâr | Hamle | Soğuk | Sıcak |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Araba / yolcu | %70 | 2,0 mm/sa | 50 km/sa | 70 km/sa | 0 °C | 38 °C |
| Motosiklet | %50 | 0,2 mm/sa | 30 km/sa | 50 km/sa | 5 °C | 35 °C |
| Bisiklet | %40 | 0,2 mm/sa | 20 km/sa | 35 km/sa | 5 °C | 32 °C |
| Yürüyüş | %50 | 0,5 mm/sa | 30 km/sa | 45 km/sa | 0 °C | 32 °C |
| Koşu | %40 | 0,2 mm/sa | 25 km/sa | 40 km/sa | 5 °C | 28 °C |

Bunlar kişisel bildirim eşikleridir; yolun yolculuğa uygunluğunu garanti etmez.

**Hava bildirimleri** her tür için ayrı açılıp kapatılabilir. Kapalı olduğunda rota tahminini görmeye devam edersin; o türün hava bildirimi ve sesli uyarısı verilmez. **Türkçe sesli uyarı** tercihi de her türe özeldir ve başlangıçta kapalıdır. **Sesi test et** ile telefonun mevcut ses çıkışını kontrol edebilirsin. Türkçe Android seslendirmesi ve geçici ses odağı kullanılır. Her türün hava bildirimleri Android üzerinde ayrı bir kanaldan gelir; mevcut motosiklet kanalının tercihleri korunur.

Yürüyüş, koşu ve bisiklette **Planlama hızı (km/sa)** tahmini rota sürelerini ve bu sürelere göre seçilen hava saatlerini etkiler:

| Tür | Başlangıç hızı | Ayarlanabilir aralık |
| --- | ---: | ---: |
| Yürüyüş | 5,1 km/sa | 0,5–25 km/sa |
| Koşu | 10 km/sa | 0,5–25 km/sa |
| Bisiklet | 18 km/sa | 5–60 km/sa |

Koşu ve yürüyüş yaya yollarını, bisiklet bisiklet rotasını, motosiklet motosiklet rotasını kullanır. Araba ve yolcu otomobil için karayolu rotası kullanır; süreyi rota servisi hesaplar. **Yolcu** seçimi toplu taşıma tarifesi, tren veya uçuş planlaması yapmaz; başkasının aracında yapılan karayolu yolculuğunu ayrı kaydetmek içindir.

Planlama hızını değiştirmek sonraki rota hesaplamalarını etkiler. Devam eden hava oturumu, rota oluşturulurken kullanılan hızını korur; rota dışına çıkıldığında yeniden hesaplama da bu hızla yapılır.

## Canlı takip ve saat

Konum 90 saniyeden, tahmin bir saatten eskiyse konuma bağlı yeni uyarılar duraklatılır. Son tahminin alınma zamanı korunur. Yolculuk bittiğinde, kaydın ulaşım türü değiştiğinde veya kayıt servisi kapandığında hava oturumu kapanır. Kaydın türünü değiştirdiysen yeni tür için rota havasını yeniden hesaplayıp başlatmalısın. Uygulama yeniden başlatıldığında son plan, türüyle birlikte düzenleme için saklanır; hava takibi kendiliğinden başlamaz.

Telefonla bağlantılı Wear OS kartı bütün desteklenen yolculuk türlerinde kalan rota havasını, tahmini varışı ve verinin zamanını gösterir. Saat bağlantısının kesilmesi ile hava tahmininin eskimesi ayrı durumlardır. Saat kendi hava isteğini göndermez. Eski telefon/saat sürümlerinin v1 ve v2 mesaj biçimleri korunur.

## Veri ve servisler

Plan, türe özel tercihler ve hava önbelleği telefonda ayrı saklanır. Hesaplanan rota çizgisi günlüğe GPS noktası olarak yazılmaz. Günlüğün Room 6 ve yedek 6 biçimleri kalıcı yer sırasını içerir; hava planı mevcut günlük yedeğine eklenmez.

Rota hesaplanırken seçilen duraklar etkin sağlayıcıya (uygun araçlarda TomTom veya [Valhalla](https://valhalla.github.io/valhalla/api/route/api-reference/)), hava alınırken örnek konumlar [Open-Meteo](https://open-meteo.com/en/docs) servisine gönderilir. Yol verisi © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright). Varsayılan servisler kişisel kullanım için seçilmiştir; ileri ayarlarda her türün HTTPS servis adresleri değiştirilebilir. [Open-Meteo kullanım koşulları](https://open-meteo.com/en/pricing) ticari kullanım için farklıdır.

Rota süreleri tahmindir. Yağış ve rüzgâr hamlesinin saatlik aralık anlamı korunur; eksik hava alanları sıfır kabul edilmez. Kalkış önerisi, verisi tam olan seçeneklerde eşik aşılan tahmini süreyi karşılaştırır.

Gerçek rota, hava kapsamı, bildirim zamanı ve pil etkisi fiziksel cihazda ayrıca doğrulanmalıdır.
