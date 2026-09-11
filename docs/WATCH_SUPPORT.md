# Galaxy Watch8 Classic desteği

İz, telefona bağlı bir Wear OS uygulaması içerir. Telefon sürümü 0.8.0 / code 12, saat sürümü 0.5.1 / code 8'dir. Saat modülü `wear`, telefon modülü `app`, ortak iletişim kodu `wear-protocol` dizinindedir.

## Saatte kullanım

- Araba, motosiklet, bisiklet, yürüyüş, koşu ve yolcu modlarından biri seçilir.
- Başlat komutu telefonda bir yolculuk açar. Telefon arka plandaysa, saatin gösterdiği yönlendirmeyle telefondaki İz bildirimine dokunup **Başlat** seçilir. Bildirim izni kapalıysa telefonda İz'i açmak bekleyen isteği gösterir. İstek iki dakika geçerlidir.
- Aktif kaydın mesafe, süre, son ölçülen hız, ortalama ve en yüksek hızı görülür. Yürüyüşte telefonun ölçtüğü adım sayısı gösterilir; sensör veya izin yoksa ölçüm bilinmiyor olarak kalır.
- Geçici otomatik yolculukta 500 metre ilerlemesi ve 15 dakikalık pencerenin kalan süresi izlenir. Zaman dolduğunda telefondaki yeni ölçüm penceresi saate yansır.
- Bitir seçeneği mevcut yolculuk için onay ister. Telefon işlemi doğrulamadan başarı gösterilmez.
- Döner çerçeve ve dokunarak kaydırma ile yuvarlak ekrandaki bilgi ve kontrollere erişilir.

Telefon konum kaydının kaynağıdır. Saat bağlantısı kesilince telefondaki takip sürer; saatte son verinin zamanı ve bağlantı durumu gösterilir. Otuz saniyeden eski özet canlı kabul edilmez. Çevrimdışı komutlar bağlantı gelince kendiliğinden çalıştırılmak üzere saklanmaz.

Bu sürüm telefon olmadan saat GPS'inden bağımsız rota veya saat sensöründen adım kaydetmez. Rota arşivi, OSM haritası, ısı haritası, özel ziyaret notları, fotoğraflar, GPX dışa aktarma ve OSM bildirimleri telefon ekranında kullanılır. OSM bildirimi, kullanıcının ayrıca yazıp önizlediği metin ve konumla, açık gönderim onayından sonra yayımlanır; özel günlük notları ve fotoğraflar buna eklenmez.

Google Maps, Places ve Google yorum/puan gönderme ekranları kaldırılmıştır. Eski günlük ve yedek verileri korunur; Google Play konum, hareket algılama ve Wear OS hizmetleri kullanılmaya devam eder.

## Kurulum

1. Kaynak koddan telefon ve saat uygulamalarını aynı imzayla derleyin.
2. Telefon paketini telefona, Wear OS paketini eşleşmiş saate kurun.
3. Telefon–saat eşleşmesinin çalıştığını doğrulayın. İki cihazda İz'i açın; telefonda gerekli konum izinlerini verin. Saatte telefon bulununca durum yenilenir.

İki uygulama aynı `com.atay.iz` uygulama kimliği ve aynı imzayla derlenir; farklı cihazlara kurulurlar. Saat paketini telefona kurmayın. Bu kaynak yayını hazır APK veya Play Store sürümü içermez. Mevcut telefon uygulamasını kaldırmak veri kaybına yol açabilir; güncelleme aynı imzayla üzerine kurulmalıdır.

Saat için geliştirici seçenekleri ve kablosuz hata ayıklama etkinleştirildiğinde, saatin gösterdiği geçici eşleştirme bilgileriyle ADB kurulumu yapılabilir. Bu bilgileri belgelerde, hata kayıtlarında veya herkese açık ekran görüntülerinde paylaşmayın.

## Teknik davranış

Wear OS Data Layer kullanılır. Aynı uygulama kimliği ve imza platformun eşleştirme koşuludur. Telefon `iz_phone_v1`, saat `iz_watch_v1` yeteneğini ilan eder. Saat yalnız bu yeteneğe sahip seçilmiş telefonun mesajlarını ve özetlerini işler. [Android Data Layer](https://developer.android.com/training/wearables/data/overview)

Sürüm ve boyut kontrollü ikili mesajlar yalnız komutları ve yolculuk özetlerini taşır; rota koordinatları, fotoğraflar veya özel yorumlar saate gönderilmez. Telefon istek kimliği ve sonucu kalıcı olarak saklar. Aynı başlatma isteği ikinci bir yolculuk açamaz; eski yolculuğun bitirme isteği yeni kaydı kapatamaz. Kayıt oluşturma, servisin başlaması ve sonucun saklanması ekranın kapanmasıyla yarıda bırakılmaz.

Android'de Data Layer mesajı tek başına arka plandan konum servisi başlatma yetkisi vermez. Uygulama öndeyken uygun izinlerle başlatılır; diğer durumda kullanıcının açtığı, dış uygulamalara kapalı telefon onay ekranı kullanılır. [Ön plan servisi kısıtları](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

## Doğrulama sınırı

Yuvarlak Wear OS emülatörü Samsung'un One UI Watch arayüzü veya gerçek döner çerçevesi değildir. Gerçek telefon–Watch8 Classic eşleşmesi, bağlantı kopup dönmesi, ekran kapalıyken başlatma yönlendirmesi ve pil etkisi fiziksel cihazda ayrıca denenmelidir. Emülatörde durum ve arayüz testlerinin geçmesi gerçek Data Layer bağlantısının kurulmuş olduğu anlamına gelmez.
