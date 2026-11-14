# Yol tarifi ve trafik

Motosiklet sağlayıcısı ve durak sırası anlatımı yayımlanmamış 0.13.4 kaynak adayını kapsar; [kabul durumu sürüm planındadır](SURUM_PLANI.md). Aşağıdaki 0.9.0 uyumluluk kaydı tarihseldir.

İz 0.9.0, ana haritada **Nereye?** ile yol tarifi sunar. Yeni planın başlangıcı mevcut konumdur; başlangıcı ve varışı yer araması, kayıtlı yer veya harita ile değiştirebilirsin. Haritada A başlangıcı, B varışı gösterir. Önizleme yolculuk kaydı başlatmaz.

**Rotayı başlat**, güncel konumla rotayı hesaplar ve sesli yönlendirmeyi yolculuk kaydıyla birlikte başlatır. Özel başlangıç 100 metreden uzaktaysa önce oraya gidilir; ardından seçilen durak sırası izlenir. Aynı türde açık kayıt varsa kullanılır. Sayfayı kapatmak veya yönlendirmeyi durdurmak kaydı bitirmez; bunun için **Yolculuğu bitir** kullanılır.

## Trafik kurulumu

TomTom bağlantısı kaynak yayınında yapılandırılmamıştır ve temel kullanım için gerekli değildir. Varsayılan Valhalla rotası, navigasyon ve yerel günlük kişisel anahtar olmadan çalışır. Otomobil/yolcu için trafik tahminini kullanmak isteyen kişi aşağıdaki isteğe bağlı kurulumu kendi hesabıyla tamamlar. Motosiklet rotası ve durak sırası TomTom anahtarı veya trafik onayı gerektirmez.

1. [TomTom geliştirici hesabını](https://my.tomtom.com/) aç. Hesaba giriş ve kullanım koşullarını kabul etme işlemini kendin tamamla.
2. [Güncel ücretsiz kotayı](https://docs.tomtom.com/pricing) ve hesap planını kontrol et. Bu sürüm yalnız ücretsiz kullanım için hazırlanmıştır; ücretli plan, ödeme yöntemi veya otomatik ödeme açma. Başka uygulamalarının aynı hesaptaki tüketimi de kotayı etkileyebilir.
3. Routing API erişimi olan kişisel anahtar oluştur. Otomobil/yolcu Matrix durak sırası için Matrix Routing v2 API, isteğe bağlı hız sınırı için Reverse Geocoding API yetkilerini ayrıca etkinleştir. Anahtarı sohbetlere, ekran görüntülerine veya kaynak koduna koyma.
4. İz → **Yol tarifi → Trafik ayarları** ekranında anahtarı gir, ücretsiz planını kontrol ettiğini onayla ve trafiği aç.
5. Araba veya yolcu rotası hesapla. Başarılı yanıtta sağlayıcı, trafik dahil süre, gecikme ve güncelleme zamanı görünür. Trafik sonucu alınamadığında neden belirtilir ve Valhalla ile trafik hariç rota hesaplanır.

Bu ayar TomTom hesabının ödeme durumunu değiştirmez. Hesabın ücretsiz plan koşullarını ve üçüncü taraf harita üzerinde yönlendirme kullanımını [güncel şartlardan](https://docs.tomtom.com/legal/terms-and-conditions) kontrol et. Uygulama içinde ücretli abonelik veya satın alma işlemi bulunmaz.

Otomobil/yolcuda trafik açıkken hesaplama için başlangıç, varış, ara durak koordinatları ve ulaşım türü TomTom'a gönderilir. Motosiklet rota istekleri Valhalla'ya gider. Anahtar AndroidKeyStore ile cihazda şifrelenir; günlük yedeklerine veya APK'ya eklenmez. **Anahtarı sil ve trafiği kapat**, bu bağlantıyı kaldırır. TomTom rota yanıtları yalnız oturum belleğinde tutulur.

## Hesaplanan süre

- Araba/yolcu: trafik açık ve bağlantı hazırsa TomTom otomobil rotası.
- Motosiklet: mevcut Valhalla motosiklet beta profili; **Valhalla · Trafik hariç**. Günlük sürüş içindir; yol tercihleri kesin yüzey/otoyol dışlama garantisi değildir. Hata halinde mevcut rota ve seçimler korunur, elle yeniden denenir; TomTom'a geçilmez.
- Bisiklet/yürüyüş/koşu: Valhalla ve ilgili ulaşım türünün mevcut hız ayarları.
- Hava ekranı: yapılandırılmış sağlayıcıdan bir temel rota ve yedi yaklaşık hava seçeneği. Açıkça seçilen kalkışta rota ve hava örnekleri yeniden hesaplanır; motosiklet süresi trafik içermez. [0.9.0'ın tarihsel Matrix ve hız sınırı davranışı](IZ_090_YENILIKLER.md).

Çizilen güzergâh, dönüşler ve süre aynı sağlayıcının aynı yanıtından alınır. Rota alınamadığında iki nokta arasına sahte düz yol çizilmez. Otomobil/yolcuda rutin trafik yenilemesi aktif yönlendirmede iki dakikadan sık yapılmaz; beş dakikadan eski trafik bilgisi güncel olarak gösterilmez. Motosiklette dönemsel trafik yenilemesi yoktur; sapma sonrası Valhalla yeniden hesaplaması korunur. Yeni hedef ve rotadan sapma ayrı rota hesaplama işlemleridir. Ağ kesilmesi yüklenmiş rotayı veya GPS kaydını silmez; eski trafik verisi belirtilir.

## Motosiklette durak sırası ve hız sınırı

4–20 planlı durakta deterministik yakın komşu ve sınırlı 2-opt önerisi kullanılır; kısa motosiklet planı da TomTom Matrix'e gitmez. Sıra değişmiyorsa rota isteği yapılmaz. Değişen öneri ve mevcut sıra aynı Valhalla uç noktası, tercihler ve karşılaştırma zamanı ile hesaplanır; yalnız hesaplanan süre kısalırsa öneri gösterilir, kullanıcı açıkça uygular. Başlangıç/bitiş, durak kimlikleri, tekrarlar ve molalar korunur; kesin optimum iddiası yoktur.

TomTom ayarları ve hız sınırı yardımcılarının erişim koşulları korunur; ancak yeni motosiklet Valhalla rotalarında TomTom rota kesimlerinden gelen hız sınırları bulunmaz. Mevcut OSM desteği aynı kapsamı garanti etmez. Yönlendirme sırasında yeni ters sorgular eklenmez.

## Uyumluluk

Telefon sürümü 0.9.0 / code 15'tir. Günlük ve ZIP yedek sürümü 6, kalıcı yer sırası ekler; eski 1–5 yedekleri okunur. Saat 0.6.0 / code 10 ile v5 iletişimi korunur.

Gerçek trafik verisine erişim kişisel anahtarın, kotanın ve kapsamanın durumuna bağlıdır. Sahte sunucu testleri gerçek yoldaki trafik tahmininin doğruluğunu kanıtlamaz; canlı trafik fiziksel yolculukta ayrıca doğrulanmalıdır.
