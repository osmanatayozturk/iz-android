# OpenStreetMap uygulama mimarisi

İz 0.8.1 çevrimiçi harita, yer arama, yakındaki nesneleri bulma ve kullanıcı onaylı OSM Notes katkıları için OpenStreetMap ekosistemini kullanır. Google Maps/Places ve yorum ya da puan gönderme akışları uygulamada yoktur. Google Play konum ve hareket servisleri ile Wear OS Data Layer bağımlılıkları devam eder.

## Servisler

- MapLibre, çevrimiçi raster haritayı görünür OpenStreetMap kaynak gösterimiyle çizer. Çevrimdışı bölge indirme veya toplu karo ön yükleme yapılmaz.
- Nominatim yalnız kullanıcı **Ara** düğmesine bastığında sorgulanır. İlk sayfada 1–9 geçerli sonuç ve en az bir OSM referansı varsa, mevcut referanslar `exclude_place_ids` ile dışlanarak en fazla bir devam isteği yapılır. İlk sayfa boşsa, tam 10 sonuç içeriyorsa veya referans yoksa devam edilmez. Her sayfa en fazla 10, birleşik liste en fazla 20 sonuçla sınırlıdır; bu sınır eksiksiz yer keşfi garantisi değildir.
- Mevcut yapılandırılabilir sağlayıcı, `format=jsonv2`, `limit=10`, `addressdetails=0` ve Türkçe dil tercihi korunur. Ham yanıtlar endpoint, sorgu, seçenekler, dil ve dışlama listesiyle ayrılan v2 sayfa anahtarlarında önbelleğe alınır. İkinci sayfa başarısız olursa ilk sayfa korunur; mevcut istek hızı, yanıt boyutu, bekleme ve iptal sınırları sürer.
- Arama sonuçlarında `name` başlık olarak kalır; mevcut yanıttaki `display_name` ayrı bir sunum alanıyla telefon listesine taşınır. Boş veya yalnız başlığı tekrarlayan açıklama için “Adres bilgisi yok” kullanılır. Yapılandırılmış adres alanı istenmez, virgülden mahalle/sokak türü çıkarılmaz ve adres için ek sorgu yapılmaz. Bu sunum alanı günlük, rota veya yedek veri biçimine eklenmez.
- Overpass küçük, görünür harita alanlarında yakındaki OSM nesnelerini bulur. Yanıt boyutu ve günlük kullanım sınırlandırılır; servis kota yanıtı verdiğinde yeniden deneme geciktirilir.
- OSM Notes yazma, yalnız kullanıcı metni ve konumu önizleyip açıkça onayladıktan sonra gerçekleşir. Özel günlük notları, fotoğraflar ve sağlık verileri gönderilmez.

Servis adresleri uygulamanın gelişmiş ayarlarından değiştirilebilir. Bir dağıtımı çok sayıda kullanıcıya açmadan önce seçilen karo, Nominatim ve Overpass sunucularının kullanım politikaları ve kapasitesi değerlendirilmelidir.

## Kimlik doğrulama ve veri güvenliği

OAuth 2.0 public istemci, PKCE S256 ve `state` doğrulaması kullanılır. `OSM_CLIENT_ID` public bir yapılandırmadır; istemci sırrı veya OSM parolası kaynak koduna ve uygulamaya girilmez. Kimlik tanımlı değilse hesap gerektirmeyen harita, arama, yerel günlük, GPX ve katkı taslağı özellikleri çalışmaya devam eder.

OAuth oturumu Android Keystore ile korunan, yedek dışı yerel depoda saklanır. Bir katkının HTTP sonucu belirsiz kaldığında otomatik ikinci gönderim yapılmaz; kullanıcı önce uzak OSM durumunu kontrol eder. GPX dışa aktarma yalnız kullanıcı tarafından seçilen tamamlanmış rotayı yazar ve kendiliğinden OSM'ye yüklemez.

## Sınırlar ve kaynak gösterimi

Uygulama doğrudan OSM harita geometrisini düzenlemez; OSM Notes yayımlar. Harita ve yol verisi © OpenStreetMap katkıcılarıdır. Uygulama ve türev dağıtımlar [OSM telif ve lisans bildirimini](https://www.openstreetmap.org/copyright) görünür tutmalıdır.

Davranışsal kontroller yerel sahte HTTP sunucuları ve Android testleriyle yapılabilir. Otomatik testler gerçek hesaba not veya özel mesaj göndermemelidir; gerçek hesapla yayınlama ayrı, açık kullanıcı işlemi olmalıdır.
