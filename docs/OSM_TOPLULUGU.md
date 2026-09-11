# İz 0.8.1 — OSM Topluluğu

**Menü → OSM Topluluğu** veya Ayarlar'daki aynı adlı kısayol bölümü açar. Topluluk bölümünün içinde **Mesajlar**, **Kişiler** ve **Hesabım** bulunur.

## Kullanım

- **Mesajlar:** gelen/gönderilen kutusu, okunmamış işareti, ayrıntı, yeni mesaj, yanıt, okundu/okunmadı ve silme. Silme OSM'de yalnız kendi kutunuzu etkiler. Yanıt, alıcısı ve `Re:` konusu hazırlanmış yeni mesajdır; sohbet zinciri veya canlı teslim bilgisi gösterilmez.
- **Taslaklar:** cihazda tutulur. Gönderim sırasında yanıt kaybolursa **Gönderim sonucu belirsiz** durumu korunur. Uygulama mesajı kendiliğinden yeniden göndermez. Önce giden kutusunu kontrol edin; yeniden gönderme gerekirse uyarıyı okuyarak yeni bir taslak kopyası oluşturun.
- **Kişiler:** mesaj kutularından bulunan kişiler ve kullanıcı kimliği bilinen profiller uygulamada açılır. Kullanıcı adıyla profil açmak resmî OSM sitesine gider.
- **Hesabım:** profil adı/görseli/açıklaması, katılım tarihi, katkı sayısı, mesaj izinleri, bildirim tercihi ve hesap bağlantısı. Bağlantıyı kesmek bu cihazdaki özel mesaj önbelleğini, taslakları, bildirimleri ve bekleyen mesaj kontrollerini temizler. Yolculuk günlüğü korunur.

Takip etme, takibi bırakma, takip listesi, profil düzenleme ve sessize alma **OSM sitesinde aç** düğmeleriyle Android Custom Tabs içinde yönetilir. İz doğrulayamadığı bir takip durumunu başarılı olarak göstermez. Tarayıcı hesabı uygulamadaki OAuth hesabından ayrı olabilir; site tekrar giriş isteyebilir.

Mesajlar düz metin gösterilir. İçerikteki HTML veya JavaScript çalıştırılmaz. Açılabilir bağlantılar HTTP/HTTPS ile sınırlıdır.

## OSM uygulamasını hazırlama

OSM hesap bağlantısı isteğe bağlıdır. Public istemci kimliği tanımlanmadan harita, arama, yerel günlük ve katkı taslakları kullanılabilir; hesap girişi, not yayımlama ve özel mesaj işlemleri devre dışı kalır.

1. Yeni uygulama kimliğiyle ilk hesap girişinden önce [OSM uygulamalarınız](https://www.openstreetmap.org/oauth2/applications) sayfasında bu uygulama için public bir istemci oluşturun. Eski ve yeni kurulum bir süre birlikte kullanılacaksa her biri için ayrı public istemci önerilir.
2. `read_prefs`, `write_notes`, `write_api`, `consume_messages` ve `send_messages` izinlerini verin. Dönüş adresi `org.iz.navigation:/oauth2redirect`, istemci türü public olmalıdır; istemci sırrı kullanılmaz.
3. Telefonda **OSM Topluluğu → Hesabım → Mesaj izinlerini ver** akışını tamamlayın. Hesap girişi ve OAuth erişim onayı hesap sahibi tarafından yapılır.

İzin yükseltmesini iptal etmek çalışan eski hesabı bozmaz. Mevcut katkı izinleri sonraki normal girişlerde veya yeni izin yükseltmelerinde kaybedilmez.

## Bildirimler

Mesaj okuma izni, Android bildirim izni ve bölümdeki bildirim tercihi birlikte açıksa ağ bağlantısı gerektiren 15 dakikalık WorkManager kontrolü kullanılır. Android'in güç yönetimi kontrolü geciktirebilir; anlık mesajlaşma hizmeti değildir. Bölüm açıldığında ve **Yenile** seçildiğinde de eşitleme yapılır.

İlk eşitleme önceki mesajlar için bildirim oluşturmaz. Sonraki yeni, okunmamış mesajlar hesap ve mesaj kimliğiyle tekrar önlenerek bildirilir. Önizleme gönderici, konu ve mesaj metnini göstermez. Bildirime dokunmak doğru hesaptaki mesajı açar; başka hesaba ait bildirim özel içerik açmaz.

## Veri ve uyumluluk

Özel mesaj verileri günlük veritabanından ayrı Room deposunda, uygulamanın yedek dışı dizininde saklanır. Anahtarlar hesap kimliğini içerir. Günlük ZIP/GPX dışa aktarımları mesajları veya taslakları içermez. Hesap değişimi ve çıkış, gecikmiş ağ yanıtlarının yeni hesaba yazmasını engeller.

Telefon sürümü **0.8.1 / code 13**'tür. Günlük şeması **5**, ZIP veri biçimi **5** ve telefon-saat protokolü korunur. **Wear OS 0.5.2 / code 9** kullanılır.

## Teknik kaynaklar

- [Resmî mesaj işlemleri](https://github.com/openstreetmap/openstreetmap-website/blob/master/app/controllers/api/messages_controller.rb): okuma/gönderme/okunma durumu/silme.
- [Posta kutusu sayfalaması](https://github.com/openstreetmap/openstreetmap-website/blob/master/app/controllers/api/messages/mailboxes_controller.rb): `order`, kapsayıcı `from_id`, en fazla 100 kayıt. Silinmiş satırlar sayfa ilerlemesinde hesaba katılır.
- [OSM site yolları](https://github.com/openstreetmap/openstreetmap-website/blob/master/config/routes.rb): API dışında kalan profil ve takip işlemleri.
- [OAuth izinleri](https://wiki.openstreetmap.org/wiki/OAuth#OAuth_2.0) ve [Android periyodik iş zamanlaması](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest).

Uygulama katmanları `osmcommunity` altında HTTP istemcisi, modeller, ayrı Room deposu, repository ve bildirim worker'ıdır; `ui/OsmCommunity*` ekran ve ViewModel katmanıdır. Yazma testleri sahte sunucu/gateway kullanır; gerçek kişilere test mesajı gönderilmez.
