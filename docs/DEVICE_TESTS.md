# Gerçek cihaz kabul kontrolleri

Bu liste İz 0.8.1 telefon ve 0.5.2 Wear OS kaynaklarından üretilen yerel geliştirme paketlerinin saha doğrulaması içindir. Maddelerin varlığı testlerin geçtiği anlamına gelmez; sonuçları test tarihi, cihaz modeli ve Android/Wear OS sürümüyle ayrı bir yerel kayıtta tutun. Seri numarası, kablosuz hata ayıklama adresi, hesap bilgisi veya özel konum yayımlamayın.

## Kurulum ve izinler

- Temiz kurulumda hesap gerekmeden uygulama açılır, otomatik algılama kapalıdır.
- Hassas konum reddedilirse elle rota başlatma açıklama gösterir; yer/not kaydı çalışır.
- Yaklaşık konum, bildirim reddi, hareket izni reddi ve arka plan izni eksikliği ayrı denenir.
- Kullanıcı izinleri sonradan iptal ederse uygulama çökmez, eksik kayıt işaretlenir.
- Kişisel API anahtarı ve OSM hesabı olmadan çevrimiçi harita, yer seçimi, Valhalla rotası, Open-Meteo hava durumu ve yerel gözlem taslakları çalışır. OSM hesabı yalnız hesap gerektiren katkı ve topluluk işlemleri için istenir.

## Hareket ve rota

- İzinler tamamken yürüyüş, bisiklet ve araç için ekran kapalı saha testi yapılır.
- Başlangıç algılama gecikmesi, 30–60 dakikalık pil tüketimi ve GPS doğruluğu kaydedilir.
- Bildirimden onaylanan rota başlangıç noktalarını korur; ret verildiğinde silinir.
- Ret sonrası aynı hareket sürerken yeni kayıt açılmaz; yeni hareket geçişinde tekrar önerilir.
- Aktif otomatik aday 15 dakika içinde 500 metreye ulaşınca başlangıç rotasıyla kalıcılaşır; ulaşmazsa mesafe ve ölçüm penceresi sıfırlanır. Aynı hareket sürerken yeni hareket geçişi olmadan ölçüm devam eder.
- Kullanıcının bitirdiği, saklanmamış geçici kayıt 24 saatlik saklama süresi dolunca silinir. Android temizliği geciktirirse uygulama süresi geçmiş kaydı göstermez.
- Araba → yürüyüş değişimi kullanıcı onayına kadar yeni yolculuk açmaz. Onayda iki ayrı yolculuk oluşur.
- Trafik ışığı kısa duruş sayılır; 10 dakikalık duruşta tek öneri gelir; kendiliğinden bitmez.
- GPS kapatma, tünel, bağlantı kopması, uygulama süreci sonlandırma ve telefon yeniden başlatma sonrası eksik rota birleştirilmez.

## Günlük ve paylaşım

- Aynı yere iki ziyaret farklı özel not ve fotoğraflarla saklanır. Yeni ziyaretlerde puan veya Google yorum akışı bulunmaz.
- Fotoğraf kaynağı kamera ve Android fotoğraf seçici ile ayrı denenir.
- EXIF tarihi/konumu olmayan fotoğraf için bilgi uydurulmaz.
- Geçici yolculuğa açıkça ziyaret/fotoğraf ekleme kaydı kalıcılaştırır.
- İnternet veya yer araması kullanılamıyorken kullanıcı adıyla kişisel yer eklenebilir.
- Harita uygulaması kurulu/kurulu değil durumları, fotoğraf paylaşım paneli ve galeriye kaydetme denenir.
- Tamamlanmış ve onaylanmış rota GPX olarak önizlenir; başlangıç/bitiş kırpması dosyaya aynen yansır, GPS boşlukları ayrı bölümler kalır. Özel notlar ve fotoğraflar GPX'e eklenmez; dosya otomatik yayımlanmaz.
- GPX dosya seçicisi açıkken ekran döndürülür veya uygulama yeniden oluşturulur; seçilen dosya hazırlanan rotayla yazılır. Vazgeçildiğinde geçici dışa aktarma dosyası temizlenir.

## Harita ve OSM katkıları

- Nominatim araması yalnızca Ara düğmesiyle çalışır; haritada seçilen alanın yakın yerleri Overpass sınırları içinde alınır. Bağlantı kesilince telefonun konum kaydı sürer.
- OSM bildirimi özel notlardan ve fotoğraflardan ayrı, boş metinle başlar. Önizlemede metin, konum ve herkese açık yayın bilgisi görülür; yalnızca açık gönderim onayı yayımlar.
- Kayıtlı public OAuth istemci kimliğiyle hesap bağlama, iptal etme ve bağlantıyı kesme denenir. Gizli istemci anahtarı veya anonim gönderim kullanılmaz.
- Gözlem yazıp hesap bağlama akışına geçilir; tarayıcı açıkken ekran döndürme veya uygulamanın yeniden oluşturulması metni kaybettirmez ve kendiliğinden göndermez.
- POST yanıtı kaybolursa durum belirsiz kalır; uygulamayı açmak veya durumu kontrol etmek ikinci POST göndermez. Kullanıcı, aynı hesapla sonucu kontrol edip tek eşleşen uzak nota bağlayabilir.
- OSM notunun açık/çözülmüş durumu yenilenir. Yerel katkıyı silmek OSM'deki herkese açık notu silmez.

## Yedek ve silme

- Onaylanmış yolculuk, iki ziyaret, özel not, fotoğraf, OSM yer eşlemesi ve katkı durumlarıyla sürüm 5 ZIP alınır; geri yüklenince içerik karşılaştırılır.
- Sürüm 1–4 yedekleri veri kaybı olmadan okunur; eski Google yer kimliği, puan ve yorum taslaklarından OSM bildirimi oluşturulmaz. Yedekte OAuth oturumu veya erişim belirteci bulunmaz.
- Önceki uygulama kimliğindeki açık kayıt bitirilip saat kuyruğu sıfırlandıktan sonra alınan ZIP, yeni `org.iz.navigation` kurulumuna aktarılır; rotalar, fotoğraflar ve seçilmiş sağlık verileri karşılaştırılır. Eski uygulama doğrulama bitene kadar kaldırılmaz.
- Kimlik bilgileri, ayarlar, grup oturumu, OSM Topluluğu taslak/önbelleği ve gönderilmemiş saat kuyruğunun taşınmadığı; izinlerin, OSM bağlantısının ve ayarların yeniden kurulması gerektiği doğrulanır.
- Gönderiliyor durumunda yedeklenen katkı geri yüklenince belirsiz duruma geçer; kendiliğinden yeniden gönderilmez.
- Bozuk ZIP, bilinmeyen sürüm, eksik fotoğraf, path traversal ve aşırı büyük girişler mevcut veriyi değiştirmeden reddedilir.
- Başarılı geri yüklemede eski konum servisi durur; doğrulama veya veritabanı değiştirme hatasında mevcut yolculuk korunur. Yedekten gelen kayıt kendiliğinden takip başlatmaz.
- Yolculuk silinince ziyaret geçmişi korunur; yer silinince bağlı ziyaretler ve dosyalar temizlenir.
- Android bulut yedeğinde ve cihaz transferinde uygulama verileri bulunmaz.
