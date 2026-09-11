# Yolculuk havası uygulama mimarisi

İz 0.8.1, araba, motosiklet, bisiklet, yürüyüş, koşu ve yolcu türlerinde aynı rota havası altyapısını kullanır. Özellik telefon uygulamasındadır; Wear OS uygulaması telefondan gelen özet durumu gösterir. Hava planı günlük Room 5 şemasına veya ZIP yedek 5 biçimine eklenmez.

Telefon akışı: **Yolculuk havası** ekranında mevcut konum, harita, kayıtlı yer veya gönderilmiş OSM aramasıyla başlangıç ve hedef seçilir; en fazla üç ara durak eklenir. Seçilen kalkış ve sonraki üç saatteki yarım saatlik seçenekler aynı rota üzerinde karşılaştırılır. Harita örnek noktalarının tahmini varış anındaki hava değerlerini gösterir. **Şimdi başlat**, başlangıcı güncel konumla yeniden hesaplar ve aynı türdeki açık kayda bağlanır ya da yeni kayıt başlatır.

Sağlayıcılar: Valhalla rota geometrisi ve kümülatif manevra sürelerini; Open-Meteo saatlik sıcaklık, yağış olasılığı/miktarı, rüzgâr ve hamle değerlerini sağlar. OSM, Valhalla ve Open-Meteo kaynak gösterimleri korunur. HTTPS adresleri yapılandırılabilir; tanımlayıcı User-Agent, uygulama genelinde hız sınırı, sınırlı yanıt boyutu ve `Retry-After` desteği kullanılır. Varsayılan servisler hesap veya anahtar satın almayı gerektirmez; kullanım ve kapasite koşulları dağıtımdan önce ayrıca değerlendirilmelidir.

Örnekleme: rota uçları dâhil edilir; yaklaşık 900 saniyede bir ve en fazla 48 nokta seçilir, uzun rotalarda aralık büyür. Manevra süreleri kümülatiftir; manevra içi süre mesafeye göre dağıtılır. Zamanlar içeride UTC epoch olarak taşınır. Eksik hava değeri sıfır sayılmaz, farklı noktalardaki olasılıklar tek bir uydurma rota olasılığına dönüştürülmez ve eksik aday önerilmez.

Kullanıcı tarafından değiştirilebilir eşikler yolculuk türüne göre ayrı saklanır. Motosiklet başlangıcı yağış olasılığı %50, yağış 0,2 mm/saat, rüzgâr 30 km/sa, hamle 50 km/sa, soğuk 5 °C ve sıcak 35 °C'dir. Araba/yolcu, bisiklet, yürüyüş ve koşu kendi daha uygun başlangıç profillerini kullanır; kesin değerler [kullanım belgesinde](USAGE.md#her-türe-ayrı-ayarlar) yer alır. Bunlar kişisel bildirim tercihleridir; resmî güvenlik sınıfları değildir.

Canlı takip: kabul edilmiş GPS noktaları mevcut kayıt servisinden alınır. Kalan süreler 60 saniyede bir hesaplanır, hava normalde 15 dakikada bir yenilenir. Rota ilerlemesi tekrarlanan geçişleri hesaba katar. Rotadan 250 metreden fazla uzak üç doğru konum en az 15 saniyeye yayılırsa, ziyaret edilmemiş duraklar korunarak rota en fazla dakikada bir yeniden hesaplanır. 90 saniyeden eski GPS ve bir saatten eski tahmin yeni uyarıları durdurur. Ağ veya izin hatası GPS günlüğünü etkilemez; uygulama süreci yeniden açıldığında hava oturumu kendiliğinden başlamaz.

Uyarılar: sonraki 60 dakikada ilk kez aşılan eşik bildirilir; aynı tür uyarıda 30 dakikalık bekleme uygulanır. Türkçe Android TTS tercihi başlangıçta kapalıdır. Ses odağı veya Türkçe ses yoksa yalnız bildirim kullanılır; mikrofon izni gerekmez.

Saat: v1 ve v2 uyumluluğu korunurken rota havası v3 özetine isteğe bağlı olarak eklenir. Kart yükleniyor, hazır ve hata durumlarını; kalan mesafeyi, yaklaşık karşılaşma zamanını, varışı ve tahmin zamanını gösterir. Bağlantı kesilmesi ile tahminin eskimesi ayrı durumlardır. Saat ağ isteği, hava seslendirmesi, koordinat veya tam rota taşımaz.

Saklama: son plan, tür ayarları ve hava önbelleği telefonda ayrı tutulur. Planlanan geometri GPS günlük noktası olarak yazılmaz; hava verisi yedek ve dışa aktarmalara eklenmez.

Doğrulama; rota zamanı ve saat dilimi, örnekleme, eksik değerler, sıralama, uyarı bekleme süresi, rotadan sapma, HTTP kota/iptal/geç yanıtları, telefon-saat protokol uyumluluğu ve Compose arayüzlerini kapsamalıdır. Gerçek servis kapsamı, Türkçe ses, kilitli ekran davranışı ve fiziksel saat bağlantısı ayrıca denenmelidir.
