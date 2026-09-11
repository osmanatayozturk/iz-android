# Yol tarifi ve trafik

İz 0.8.1, ana haritada **Nereye?** ile yol tarifi sunar. Yeni planın başlangıcı mevcut konumdur; başlangıcı ve varışı yer araması, kayıtlı yer veya harita ile değiştirebilirsin. Haritada A başlangıcı, B varışı gösterir. Önizleme yolculuk kaydı başlatmaz.

**Rotayı başlat**, güncel konumla rotayı hesaplar ve sesli yönlendirmeyi yolculuk kaydıyla birlikte başlatır. Özel başlangıç 100 metreden uzaktaysa önce oraya gidilir; ardından seçilen durak sırası izlenir. Aynı türde açık kayıt varsa kullanılır. Sayfayı kapatmak veya yönlendirmeyi durdurmak kaydı bitirmez; bunun için **Yolculuğu bitir** kullanılır.

## Trafik kurulumu

TomTom bağlantısı kaynak yayınında yapılandırılmamıştır ve temel kullanım için gerekli değildir. Varsayılan Valhalla rotası, navigasyon ve yerel günlük kişisel anahtar olmadan çalışır. Trafik tahminini kullanmak isteyen kişi aşağıdaki isteğe bağlı kurulumu kendi hesabıyla tamamlar.

1. [TomTom geliştirici hesabını](https://my.tomtom.com/) aç. Hesaba giriş ve kullanım koşullarını kabul etme işlemini kendin tamamla.
2. [Güncel ücretsiz kotayı](https://docs.tomtom.com/pricing) ve hesap planını kontrol et. Bu sürüm yalnız ücretsiz kullanım için hazırlanmıştır; ücretli plan, ödeme yöntemi veya otomatik ödeme açma. Başka uygulamalarının aynı hesaptaki tüketimi de kotayı etkileyebilir.
3. Routing API erişimi olan kişisel anahtar oluştur. Anahtarı sohbetlere, ekran görüntülerine veya kaynak koduna koyma.
4. İz → **Yol tarifi → Trafik ayarları** ekranında anahtarı gir, ücretsiz planını kontrol ettiğini onayla ve trafiği aç.
5. Araba veya motosiklet rotası hesapla. Başarılı yanıtta sağlayıcı, trafik dahil süre, gecikme ve güncelleme zamanı görünür. Trafik sonucu alınamadığında neden belirtilir ve Valhalla ile trafik hariç rota hesaplanır.

Bu ayar TomTom hesabının ödeme durumunu değiştirmez. Hesabın ücretsiz plan koşullarını ve üçüncü taraf harita üzerinde yönlendirme kullanımını [güncel şartlardan](https://docs.tomtom.com/legal/terms-and-conditions) kontrol et. Uygulama içinde ücretli abonelik veya satın alma işlemi bulunmaz.

Trafik açıkken hesaplama için başlangıç, varış, ara durak koordinatları ve ulaşım türü TomTom'a gönderilir. Anahtar AndroidKeyStore ile cihazda şifrelenir; günlük yedeklerine veya APK'ya eklenmez. **Anahtarı sil ve trafiği kapat**, bu bağlantıyı kaldırır. TomTom rota yanıtları yalnız oturum belleğinde tutulur.

## Hesaplanan süre

- Araba/yolcu: trafik açık ve bağlantı hazırsa TomTom otomobil rotası.
- Motosiklet: TomTom'un beta motosiklet profili, deneysel olarak etiketlenir; otomobil profiline sessizce geçilmez.
- Bisiklet/yürüyüş/koşu: Valhalla ve ilgili ulaşım türünün mevcut hız ayarları.
- Hava ekranındaki yedi kalkış karşılaştırması: trafik hariç Valhalla rotası. Başlatmada trafikli rota alınırsa canlı hava örneklemesi yeni güzergâhı kullanır.

Çizilen güzergâh, dönüşler ve süre aynı sağlayıcının aynı yanıtından alınır. Rota alınamadığında iki nokta arasına sahte düz yol çizilmez. Rutin trafik yenilemesi aktif yönlendirmede iki dakikadan sık yapılmaz; beş dakikadan eski trafik bilgisi güncel olarak gösterilmez. Yeni hedef ve rotadan sapma ayrı rota hesaplama işlemleridir. Ağ kesilmesi yüklenmiş rotayı veya GPS kaydını silmez; eski trafik verisi belirtilir.

## Uyumluluk

Telefon sürümü 0.8.1 / code 13'tür. OSM Topluluğu, Android Auto, günlük veritabanı ve yedek biçimi korunur. Saat 0.5.2 / code 9 ile aynı iletişim biçimi kullanılır.

Gerçek trafik verisine erişim kişisel anahtarın, kotanın ve kapsamanın durumuna bağlıdır. Sahte sunucu testleri gerçek yoldaki trafik tahmininin doğruluğunu kanıtlamaz; canlı trafik fiziksel yolculukta ayrıca doğrulanmalıdır.
