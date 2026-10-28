# İz: üç aylık geliştirme yol haritası

Bu belge, onaylı UX-0111 kapsamındaki kişisel kullanım odaklı üç aylık programı küçük, sıralı adaylara ayırır. Haftalık aday ritmi bir planlama hedefidir; takvim, otomatik çalışma veya her haftanın yayımlanacağı sözü değildir. Her aday ayrı teknik tahsis, kanıt ve koordinatör kabulü bekler. Yalnız ilk 0.11.1 adayı aşağıda tanımlıdır; sonraki maddeler backlog'dur, uygulanmış veya test edilmiş özellikler değildir.

## İlk aday — telefon 0.11.1 / code 23

Durum: planlandı; uygulama ve doğrulama bekliyor. Wear OS **0.6.1 / code 11** ile iletişim **v6** değişmeyecek.

1. Aktif dönüşlü yönlendirmede Nereye? hedef/arama başlığını ve boşluğunu gizlemek; dönüş/mesafe, ses, uyarılar, oturum kartı, İşlemler, harita kimliği ve kamera/manuel takip durumunu korumak. Yönlendirme bitince başlığı göstermek; yalnız kayıt, rota önizleme ve dönüşsüz GPX davranışını korumak.
2. Günlük ayrıntısındaki **Bitir** eyleminde mevcut eşleşen yolculuğun etkisini anlatan, iptal edilebilir ve eski oturuma uygulanamayan teyit kullanmak. Yönlendirme, kayıt ve tüm oturum için ayrı bitirme seçeneklerini korumak.
3. Normal boyuttaki ikincil küçük metin/arka plan çiftlerinde en az 4,5:1 karşıtlığı sağlamak; renk değişikliğini ilgili token ve yüzeylerle sınırlayıp İz'in yeşil-krem kimliğini korumak.

## Haftalar 1–4 — temel akış ve açıklamalar

Bu sıradaki küçük adaylar ilk paket kabulünden sonra ele alınır; paralel başlatılmaz:

- **İlk aday:** 0.11.1'deki başlık, günlük bitirme teyidi ve karşıtlık çalışması.
- **Sonraki aday:** Sesli yönlendirme kullanılabilirliğini ve ilgili durum açıklamalarını netleştirmek (F03); liste/ayrıntı ekranları arasında gezinirken üst ekran ve liste bağlamını geri yüklemek veya korumak (F04); bağlantılı açıklama konularını (F05/F09) yalnız kabul edilen kapsamda ele almak.
- **Sonraki aday:** Tek Motosiklet modunu koruyarak daha sade navigasyon ile ayrıntılı gösterge paneli arasındaki sunumu ele almak.
- **Sonraki aday:** Ekran kilitliyken ve interkomla kullanım tutarlılığını incelemek; saati bilgilendirici yüzey olarak tutmak ve Android Auto/Wear metinleriyle ilgili F06–F08 açıklama konularını değerlendirmek.

Bu maddeler ürün davranışı için peşin kabul değildir; sonraki adayların sınırları ve kabul ölçütleri ayrı belirlenir.

## Haftalar 5–8 — hazırlanmış rota ve seçili koridor

Valhalla ile önceden hazırlanmış rotayı ve seçilmiş koridor için indirilebilir OpenFreeMap/MapLibre harita paketini değerlendirecek ayrı adaylar planlanır. İndirilmiş harita koridoru GPS ile kullanılabilir; mevcut dönüş yönergeleri ve yerel Türkçe ses korunur. Paket yeni çevrimdışı rota oluşturma veya yeniden hesaplama sağlamaz; rota önizlemesi ve mevcut çevrimiçi davranış korunur, rota farkı varsa kullanıcıya açıkça gösterilir.

İndirme için kabul sınırları: paket başına en fazla 3.000 karo ve 50 MB; sağlayıcı başına en fazla dört eşzamanlı istek ve ayırt edilebilir User-Agent. Kotayı bölerek aşma yapılmaz. TomTom REST çıktıları için saklama izni kesinleşmeden kalıcı önbellek eklenmez. Hazır olma, devam ettirme, hata ve temizlik davranışları ayrı kanıtlanmadan tamamlanmış sayılmaz.

## Haftalar 9–12 — çevrimiçi rota araçları ve yolculuk bilgisi

Ayrı adaylar halinde çevrimiçi rota kesimi kaçınma/atlama, duraklamayı hesaba katan ETA ve hava durumu, rota yakınındaki yakıt/dinlenme noktaları, kişisel yolculuk karşılaştırmaları ve viraj ayrıntısı ele alınabilir. Gidon yatış ölçümü yalnız uygulanabilirlik araştırmasıdır; doğrulanmamış derece veya doğruluk iddiası kullanılmaz ve navigasyon teslimini engellemez.

## Bütün adaylar için sınırlar

- Mevcut ulaşım türleri, veri ve yedek uyumluluğu, günlük kayıtları ve navigasyon yaşam döngüsü korunur. Saat için yeni bağımsız motor/uzaktan özellik veya ticari dağıtım bu programda yoktur.
- Ağır iz/ısı haritası/istatistik ve saat özetleri aktif kayıt/navigasyon yokken ve pil en az %20 iken ertelenmiş iş olarak kalır. Özel yerel tanılama ve yedek dışı saklama sınırları korunur.
- Canlı servis, ücretli hesap geçişi, otomatik zamanlama, donanım alımı veya geniş cihaz matrisi bu yol haritasından yetki almaz. Maliyetli seçenekler somut ihtiyaç ve ayrı karar gerektirir.
- Her aday için teknik, test, UI/UX, güvenlik ve yayın durumu ayrı raporlanır. Bir plan maddesi, tek başına uygulama, test, güvenlik veya yayın kabulü değildir.
