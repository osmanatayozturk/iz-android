# OpenStreetMap uygulama mimarisi

İz çevrimiçi harita, yer arama, yakındaki nesneleri bulma ve kullanıcı onaylı katkılar için OpenStreetMap ekosistemini kullanır. Katkı Merkezi harita notlarını, mekân düzenlemelerini ve GPS izlerini bir araya getirir.

## Servisler

- MapLibre, çevrimiçi raster haritayı görünür OpenStreetMap kaynak gösterimiyle çizer. Çevrimdışı bölge indirme veya toplu karo ön yükleme yapılmaz.
- Nominatim yalnız kullanıcı açıkça arama başlattığında sorgulanır. İlk sayfada 1–9 geçerli sonuç ve en az bir OSM referansı varsa, mevcut referanslar `exclude_place_ids` ile dışlanarak en fazla bir devam isteği yapılır. İlk sayfa boşsa, tam 10 sonuç içeriyorsa veya referans yoksa devam edilmez. Her sayfa en fazla 10, birleşik liste en fazla 20 sonuçla sınırlıdır. Bu sınır, tüm eşleşmelerin dönmesini garanti etmez.
- Yapılandırılabilir sağlayıcı, `format=jsonv2`, `limit=10`, `addressdetails=0` ve Türkçe dil tercihi korunur. **Nereye?** aramasında her ham yanıt Türkçe dil tercihi ve tam istek URL’siyle v3 sayfa önbelleğine yazılır. Diğer aramalar v2 anahtarlarını kullanır. İkinci sayfa başarısız olursa ilk sayfa korunur. Mevcut istek hızı, yanıt boyutu, bekleme ve iptal sınırları sürer.
- **Nereye?** sonuçlarında `name` başlık olarak kalır. Mevcut yanıttaki `display_name` ayrı bir satıra yazılır ve başta yinelenen yer adı çıkarılır. Adres satırı boş kalırsa “Adres bilgisi yok” gösterilir. Adres için ek sorgu yapılmaz. Bu sunum alanı günlük, rota veya yedek veri biçimine eklenmez.
- Overpass küçük, görünür harita alanlarında yakındaki OSM nesnelerini bulur. Yanıt boyutu ve günlük kullanım sınırlandırılır. Servis kota yanıtı verdiğinde yeniden deneme geciktirilir.
- Harita notu, kullanıcı metni ve konumu önizleyip onayladığında OSM’ye gönderilir. Özel günlük notları, fotoğraflar ve sağlık verileri gönderilmez. [OSM Notes](https://wiki.openstreetmap.org/wiki/Notes)

Servis adresleri uygulamanın gelişmiş ayarlarından değiştirilebilir. Bir dağıtımı çok sayıda kullanıcıya açmadan önce seçilen karo, Nominatim ve Overpass sunucularının kullanım politikaları ve kapasitesi değerlendirilmelidir.

### Terimler

- **Yakınlık önceliği** — Telefonda **Nereye?** aramasında **Ara** düğmesi veya klavyenin **Ara** eylemiyle başlar. Hazır ve izinli konum en fazla 60 saniye eski olabilir. Hazır konum yoksa ve izin varsa yaklaşık konum için en fazla 5 saniye beklenir. Gelen sonuçlar cihazda en yakından en uzağa sıralanır. Eşit mesafede veya konum alınamadığında servis sırası korunur.
- **Yaklaşık arama alanı** — Nominatim’e gönderilen, merkezi 0,1 dereceye yuvarlanmış `viewbox`. Alan merkezden enlemde 0,20°, boylamda 0,25° uzanır. `bounded=0` ile alan dışındaki eşleşmeler de dönebilir. [Nominatim arama belgesi](https://nominatim.org/release-docs/latest/api/Search/)
- **Harita notu** — Bir konuma bağlı, haritacıların incelemesine açık gözlem.
- **Mekân düzenlemesi** — OSM’ye yeni bir nokta ekleme veya mevcut mekânın yerinde doğrulanmış bilgilerini düzeltme.
- **GPS izi** — OSM ile paylaşılan sıralı konum noktaları ve bu noktaların zaman damgaları.

## Katkı Merkezi

Merkez, cihazdaki taslakları, bağlı hesabın gönderimlerini ve GPS geçmişini yeniden eskiye gösterir. GPS geçmişi alınamazsa not ve mekân kayıtları erişilebilir kalır. **Yüklenenler** ayrı GPS ayrıntılarını açar.

Paylaşılacak GPS izinin her ucundan en az 1 km çıkarılır. Önizleme gönderilecek GPX’ten üretilir. Toplu onay bağlı hesaba, seçili izlere ve görünürlüğe bağlıdır. Varsayılan **Tanımlanabilir** seçeneği izi hesapla ilişkilendirir ve herkese açık iz listesinde gösterir. **Takip Edilebilir** seçeneğinde bu bağlantı ve liste kaydı yoktur. Her iki seçenekte de noktalar ve zaman damgaları OSM API’si üzerinden erişilebilir. [OSM GPS görünürlük kuralları](https://wiki.openstreetmap.org/wiki/Visibility_of_GPS_traces)

Yerinde doğrulanan bir noktanın veya tek kapalı çizgiyle tanımlanmış alanın `name`, `addr:street` ve `addr:housenumber` alanları güncellenebilir. Boş alan mevcut değeri korur. Koordinatlar, sıralı düğüm referansları ve diğer etiketler korunur. Sürüm değişikliği veya HTTP 409, güncel değerlerle yeniden önizleme gerektirir. Belirsiz gönderim, aynı değişiklik kümesindeki nesne ve düzenleme kanıtından çözülür. [OSM düzenleme API’si](https://wiki.openstreetmap.org/wiki/API_v0.6) · [Yerinde doğrulama](https://wiki.openstreetmap.org/wiki/Good_practice)

## Kimlik doğrulama ve veri güvenliği

OAuth 2.0 public istemci, PKCE S256 ve `state` doğrulaması kullanılır. `OSM_CLIENT_ID` public bir yapılandırmadır; istemci sırrı veya OSM parolası kaynak koduna ve uygulamaya girilmez. Kimlik tanımlı değilse hesap gerektirmeyen harita, arama, yerel günlük, GPX ve katkı taslağı özellikleri çalışmaya devam eder.

OAuth oturumu Android Keystore ile korunan, yedek dışı yerel depoda saklanır. Bir katkının HTTP sonucu belirsiz kaldığında otomatik ikinci gönderim yapılmaz; kullanıcı önce uzak OSM durumunu kontrol eder. GPX dışa aktarma yalnız kullanıcı tarafından seçilen tamamlanmış rotayı yazar ve kendiliğinden OSM'ye yüklemez.

## Sınırlar ve kaynak gösterimi

Harita ve yol verisi © OpenStreetMap katkıcılarıdır. Uygulama ve türev dağıtımlar telif ve lisans bildirimini görünür tutmalıdır. [OSM telif ve lisans bildirimi](https://www.openstreetmap.org/copyright)

Davranışsal kontroller yerel sahte HTTP sunucuları ve Android testleriyle yapılabilir. Otomatik testler gerçek hesaba not veya özel mesaj göndermemelidir; gerçek hesapla yayınlama ayrı, açık kullanıcı işlemi olmalıdır.

## Taslak — planlanan çevrimdışı OpenFreeMap hazırlığı

Bu bölüm Telefon 0.12.0 / code 28 için planlanan çevrimdışı akışın S3 sınırlarını kaydeder. Mevcut/yayımlanmış 0.11.5 uygulaması çevrimiçi OSM raster katmanını kullanır ve çevrimdışı bölge indirmez; 0.12.0 adayının kaynak kodunda çevrimdışı harita kaynak adaptörü bulunur. Services kaynakları font bundle ve notices manifestini 265 tam eşleşen asset olarak içerir; sınırlı bilgisayar tarafı kaynak/derleme kontrolleri geçmiştir. Final adayın birim testleri, lint ve debug-assemble sonuçları beklemededir; bu durum hosted/runtime resource closure, native yükleme veya etiket okunabilirliği kabulü sağlamaz.

Planlanan yol, MapLibre Native ile OpenFreeMap'in Liberty stilinden seçilmiş rota koridorunu hazırlamaktır. OpenFreeMap mobil kullanımında çevrimdışı indirmeye izin verir; sağlayıcı sınırı indirme başına 3.000 karo **veya** 50 MB'dır. Proje daha sıkı biçimde her indirmeyi **hem 3.000 karo hem 50.000.000 bayt** ile sınırlandırmayı, en çok dört eşzamanlı istek kullanmayı ve uygulamayı tanımlayan sabit bir User-Agent göndermeyi öngörür. Proje sayaçları tüm eşzamanlı kaynak isteklerini ve duraklatılmış işten devam etmeyi kapsamalıdır. Servis “olduğu gibi” sunulur, kullanılabilirlik veya doğruluk garantisi vermez; koşullar değişebilir. [OpenFreeMap hızlı başlangıç](https://openfreemap.org/quick_start/) · [Kullanım koşulları](https://openfreemap.org/tos/).

OpenStreetMap `tile.openstreetmap.org` standart raster karo servisi çevrimdışı ön indirme/toplu çekime izin vermez; bu özellik için kullanılmayacaktır. [OSMF Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/). Haritada görünür atıf ve ODbL lisansına erişim korunmalıdır; çevrimdışı haritada da atıf ve lisans bilgisi erişilebilir olmalıdır. Örnek kaynak gösterimi: **© OpenMapTiles © OpenStreetMap contributors**; [OpenStreetMap telif ve lisansı](https://www.openstreetmap.org/copyright).

Seçilen azaltılmış Liberty türevinde görünür atıf **OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors** olmalıdır. Bu profil sprite/pattern, Natural Earth raster ve hosted Noto glyph'lerini dışarıda bırakır; etiketli vektör yol/yer metinleri için Carlito Regular'dan türetilen yerel glyph kaynağını seçer. Çevrimdışı kullanımda atıf ve geniş lisans/kaynak bildirimi erişilebilir kalmalıdır. [Çevrimdışı harita lisans bildirimi](OFFLINE_MAP_LICENSES.md) plain-text notice taslağını, seçili profil kaynaklarını ve upstream lisans envanterini içerir. MapLibre Native SDK lisansı harita verisi, karo, stil veya yazı tipi içerik lisanslarını kapsamaz.

### Uygulama öncesi açık lisans ve kaynak kapanışı

- Hosted Liberty stilinin sürümlenmiş gerçek JSON/TileJSON yanıtı ve `__TILEJSON_DOMAIN__` yerine geçen gerçek servis alanı henüz bu taslakta sabitlenmedi.
- Hosted stil/TileJSON'ın o anki kaynak kökenleri ve servis yanıtı saptanmadı; depo pinleri sunulan baytları sabitlemez. Carlito Regular seçilmiş sabit kaynaktır; mühürlü `iz.offline-font.bundle.v1` manifest SHA-256 değeri `C19E8462BC293B89A780AA1A0D246F71D07FE30DD07E8E61ABF4F2F6CE0AEDC0` olup 256 BMP PBF aralığı, 2.117 glyph, 232 geçerli boş PBF ve 807.207 toplam PBF baytı kaydeder. Manifestteki 260 girdi bağımsız olarak yeniden hash'lenmiştir; Services kaynakları toplam 265 tam eşleşen asseti içerir. Notices manifesti SHA-256 `D3CFB87CC1BACE916DBF128158E3AE1FA8062904DEEA9C36009F184B0E42AC94` (847 bayt) olarak doğrulanmıştır. Nihai adayın birim testleri, lint ve debug-assemble sonuçları beklemededir; hosted/runtime resource closure, native yükleme ve okunabilirlik kabulü doğrulanmamıştır. Sprite kaynakları ve lisans kapanışı [lisans belgesinde](OFFLINE_MAP_LICENSES.md) açıkça ayrılmıştır.
- Bu kaynaklar ve indirme/yeniden deneme sınırlarının tümü manifest, izin listesi, boyut/karo sayaçları ve devam etme davranışıyla eşleştirilmeden indirme “hazır” sayılamaz. Üst proje lisans listesi tek başına her barındırılan varlığın lisansını kanıtlamaz.
- Rota geometrisinin hazır oluşu, bütün harita kaynaklarının hazır oluşundan ayrıdır. Harita indirmesi yeni rota hesabı, offline reroute veya canlı navigasyon güvencesi vermez.
