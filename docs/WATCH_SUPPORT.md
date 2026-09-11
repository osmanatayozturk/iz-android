# Galaxy Watch8 Classic desteği

İz, telefona bağlı bir Wear OS uygulaması içerir. Telefon sürümü 0.8.1 / code 13, saat sürümü 0.5.2 / code 9'dur. Saat modülü `wear`, telefon modülü `app`, ortak iletişim kodu `wear-protocol` dizinindedir.

## Saatte kullanım

- Araba, motosiklet, bisiklet, yürüyüş, koşu ve yolcu modlarından biri seçilir.
- Başlat komutu telefonda bir yolculuk açar. Telefon arka plandaysa, saatin gösterdiği yönlendirmeyle telefondaki İz bildirimine dokunup **Başlat** seçilir. Bildirim izni kapalıysa telefonda İz'i açmak bekleyen isteği gösterir. İstek iki dakika geçerlidir.
- Aktif kaydın mesafe, süre, son ölçülen hız, ortalama ve en yüksek hızı görülür. Yürüyüşte telefonun ölçtüğü adım sayısı gösterilir; sensör veya izin yoksa ölçüm bilinmiyor olarak kalır.
- Geçici otomatik yolculukta 500 metre ilerlemesi ve 15 dakikalık pencerenin kalan süresi izlenir. Zaman dolduğunda telefondaki yeni ölçüm penceresi saate yansır.
- Bitir seçeneği mevcut yolculuk için onay ister. Telefon işlemi doğrulamadan başarı gösterilmez.
- Döner çerçeve ve dokunarak kaydırma ile yuvarlak ekrandaki bilgi ve kontrollere erişilir.

Telefon konum kaydının kaynağıdır. Saat bağlantısı kesilince telefondaki takip sürer; saatte son verinin zamanı ve bağlantı durumu gösterilir. Otuz saniyeden eski özet canlı kabul edilmez. Çevrimdışı komutlar bağlantı gelince kendiliğinden çalıştırılmak üzere saklanmaz.

Bu sürüm telefon olmadan saat GPS'inden bağımsız rota veya saat sensöründen adım kaydetmez. Rota arşivi, OSM haritası, ısı haritası, özel ziyaret notları, fotoğraflar, GPX dışa aktarma ve OSM bildirimleri telefon ekranında kullanılır. OSM bildirimi, kullanıcının ayrıca yazıp önizlediği metin ve konumla, açık gönderim onayından sonra yayımlanır; özel günlük notları ve fotoğraflar buna eklenmez.

Google Maps, Places ve Google yorum/puan gönderme ekranları kaldırılmıştır. Eski günlük yedekleri okunabilir; Google Play konum, hareket algılama ve Wear OS hizmetleri kullanılmaya devam eder.

## Kurulum

1. Kaynak koddan telefon ve saat uygulamalarını aynı imzayla derleyin.
2. Telefon paketini telefona, Wear OS paketini eşleşmiş saate kurun.
3. Telefon–saat eşleşmesinin çalıştığını doğrulayın. İki cihazda İz'i açın; telefonda gerekli konum izinlerini verin. Saatte telefon bulununca durum yenilenir.

Telefon ve saat uygulamaları aynı `org.iz.navigation` uygulama kimliği ve aynı imzayla derlenir; farklı cihazlara kurulurlar. Telefon namespace'i `org.iz.navigation`, saat namespace'i `org.iz.navigation.watch`, ortak protokol namespace'i `org.iz.navigation.wearprotocol`'dür. Saat paketini telefona kurmayın. Bu kaynak yayını hazır APK veya Play Store sürümü içermez.

Saat için geliştirici seçenekleri ve kablosuz hata ayıklama etkinleştirildiğinde, saatin gösterdiği geçici eşleştirme bilgileriyle ADB kurulumu yapılabilir. Bu bilgileri belgelerde, hata kayıtlarında veya herkese açık ekran görüntülerinde paylaşmayın.

## Önceki uygulama kimliğinden geçiş

Yeni uygulama kimliği Android açısından ayrı bir uygulamadır; önceki kurulumun uygulamaya özel verileri kendiliğinden taşınmaz. Geçiş tamamlanana kadar eski uygulamayı ve verisini saklayın.

1. Eski telefonda açık yolculuk kaydını bitirin. Onaylanmış etkin bir kayıt ZIP'e girebilse de geri yüklemede kapatılacağı için önce bitirmek daha güvenlidir; geçici otomatik adaylar yedeklenmez.
2. Saatte bekleyen veri sayısının **0** olmasını bekleyin. Eski telefonda otomatik hareket algılamayı, eski saatte otomatik sağlık ölçümünü kapatın.
3. Eski telefondan manuel ZIP yedeği alın. Sağlık ölçümlerini taşımak istiyorsanız dışa aktarırken sağlık verisini ayrıca seçin ve ZIP'i uygulama dışında güvenli bir yerde tutun.
4. Eski kurulumu kaldırmadan, yeni telefon ve saat APK'larını kurun. Yeni telefon ve saat paketleri aynı `org.iz.navigation` uygulama kimliğini ve aynı imzalama sertifikasını kullanmalıdır.
5. ZIP'i yeni telefon uygulamasına aktarın; rotaları, fotoğrafları ve seçtiyseniz sağlık verilerini kontrol edin.
6. Konum, hareket, bildirim ve sağlık izinlerini yeniden verin. Uygulama ayarlarını yeniden yapın, OSM hesabını yeniden bağlayın ve gerekiyorsa grup oturumunu yeniden kurun. OSM girişinden önce `org.iz.navigation:/oauth2redirect` dönüş adresli public istemci kaydedilmiş olmalıdır; eski ve yeni kurulum birlikte kalacaksa ayrı public istemciler önerilir.

ZIP; hesap veya servis kimlik bilgilerini, uygulama ayarlarını, grup oturumunu, OSM Topluluğu taslaklarını ve önbelleğini ya da saatin henüz telefona göndermediği sağlık kuyruğunu taşımaz. Yeni dışa aktarımlar `org.iz.navigation.backup` imzasını kullanır; içe aktarıcı geriye dönük uyumluluk için eski `com.atay.iz.backup` imzasını da kabul eder. Yedek veri sürümü 5 olarak kalır ve sürüm 1–5 okunur.

Eski ve yeni uygulama kimliklerine sahip karma telefon-saat çiftleri Data Layer üzerinden iletişim kurmaz. İki kurulum birlikteyken iki İz girişi ve `iz://group` bağlantısı için birden fazla uygulama işleyicisi görünebilir; doğrulama bittikten sonra eski uygulamayı ne zaman kaldıracağınıza kullanıcı karar verir.

## Teknik davranış

Wear OS Data Layer kullanılır. Aynı uygulama kimliği ve imza platformun eşleştirme koşuludur. Telefon `iz_phone_v1`, saat `iz_watch_v1` yeteneğini ilan eder. Saat yalnız bu yeteneğe sahip seçilmiş telefonun mesajlarını ve özetlerini işler. Mevcut Data Layer yolları, yetenek adları ve `iz://group` bağlantı biçimi kimlik geçişinde değişmez. [Android Data Layer](https://developer.android.com/training/wearables/data/overview)

Sürüm ve boyut kontrollü ikili mesajlar yalnız komutları ve yolculuk özetlerini taşır; rota koordinatları, fotoğraflar veya özel yorumlar saate gönderilmez. Telefon istek kimliği ve sonucu kalıcı olarak saklar. Aynı başlatma isteği ikinci bir yolculuk açamaz; eski yolculuğun bitirme isteği yeni kaydı kapatamaz. Kayıt oluşturma, servisin başlaması ve sonucun saklanması ekranın kapanmasıyla yarıda bırakılmaz.

Android'de Data Layer mesajı tek başına arka plandan konum servisi başlatma yetkisi vermez. Uygulama öndeyken uygun izinlerle başlatılır; diğer durumda kullanıcının açtığı, dış uygulamalara kapalı telefon onay ekranı kullanılır. [Ön plan servisi kısıtları](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

## Doğrulama sınırı

Yuvarlak Wear OS emülatörü Samsung'un One UI Watch arayüzü veya gerçek döner çerçevesi değildir. Gerçek telefon–Watch8 Classic eşleşmesi, bağlantı kopup dönmesi, ekran kapalıyken başlatma yönlendirmesi ve pil etkisi fiziksel cihazda ayrıca denenmelidir. Emülatörde durum ve arayüz testlerinin geçmesi gerçek Data Layer bağlantısının kurulmuş olduğu anlamına gelmez.
