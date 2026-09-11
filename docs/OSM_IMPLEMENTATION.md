# OpenStreetMap uygulama mimarisi

İz 0.8.0 çevrimiçi harita, yer arama, yakındaki nesneleri bulma ve kullanıcı onaylı OSM Notes katkıları için OpenStreetMap ekosistemini kullanır. Google Maps/Places ve yorum ya da puan gönderme akışları uygulamada yoktur. Google Play konum ve hareket servisleri ile Wear OS Data Layer bağımlılıkları devam eder.

## Servisler

- MapLibre, çevrimiçi raster haritayı görünür OpenStreetMap kaynak gösterimiyle çizer. Çevrimdışı bölge indirme veya toplu karo ön yükleme yapılmaz.
- Nominatim yalnız kullanıcı **Ara** düğmesine bastığında sorgulanır. Sonuçlar önbelleğe alınır ve uygulama genelindeki istek hızı sınırlandırılır.
- Overpass küçük, görünür harita alanlarında yakındaki OSM nesnelerini bulur. Yanıt boyutu ve günlük kullanım sınırlandırılır; servis kota yanıtı verdiğinde yeniden deneme geciktirilir.
- OSM Notes yazma, yalnız kullanıcı metni ve konumu önizleyip açıkça onayladıktan sonra gerçekleşir. Özel günlük notları, fotoğraflar ve sağlık verileri gönderilmez.

Servis adresleri uygulamanın gelişmiş ayarlarından değiştirilebilir. Bir dağıtımı çok sayıda kullanıcıya açmadan önce seçilen karo, Nominatim ve Overpass sunucularının kullanım politikaları ve kapasitesi değerlendirilmelidir.

## Kimlik doğrulama ve veri güvenliği

OAuth 2.0 public istemci, PKCE S256 ve `state` doğrulaması kullanılır. `OSM_CLIENT_ID` public bir yapılandırmadır; istemci sırrı veya OSM parolası kaynak koduna ve uygulamaya girilmez. Kimlik tanımlı değilse hesap gerektirmeyen harita, arama, yerel günlük, GPX ve katkı taslağı özellikleri çalışmaya devam eder.

OAuth oturumu Android Keystore ile korunan, yedek dışı yerel depoda saklanır. Bir katkının HTTP sonucu belirsiz kaldığında otomatik ikinci gönderim yapılmaz; kullanıcı önce uzak OSM durumunu kontrol eder. GPX dışa aktarma yalnız kullanıcı tarafından seçilen tamamlanmış rotayı yazar ve kendiliğinden OSM'ye yüklemez.

## Sınırlar ve kaynak gösterimi

Uygulama doğrudan OSM harita geometrisini düzenlemez; OSM Notes yayımlar. Harita ve yol verisi © OpenStreetMap katkıcılarıdır. Uygulama ve türev dağıtımlar [OSM telif ve lisans bildirimini](https://www.openstreetmap.org/copyright) görünür tutmalıdır.

Davranışsal kontroller yerel sahte HTTP sunucuları ve Android testleriyle yapılabilir. Otomatik testler gerçek hesaba not veya özel mesaj göndermemelidir; gerçek hesapla yayınlama ayrı, açık kullanıcı işlemi olmalıdır.
