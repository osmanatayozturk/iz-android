# Çevrimdışı harita: lisans ve kaynak bildirimi

**Taslak kaynak/bildirim belgesidir; uygulamaya gömülmüş veya dağıtılmış içerik bildirimi değildir.** Telefon 0.12.0 / code 28 için seçilen azaltılmış profil Liberty-türevi etiketli vektör haritadır: sprite/pattern ve Natural Earth raster kaynakları dışarıda bırakılır, hosted Noto glyph yerine Carlito Regular'dan üretilen yerel `Iz Offline Latin Regular` stack'i seçilmiştir. Font bundle ve hukuki bildirimler Services kaynaklarına 265 tam eşleşen asset olarak entegre edilmiştir. Font bundle SHA-256 `C19E8462BC293B89A780AA1A0D246F71D07FE30DD07E8E61ABF4F2F6CE0AEDC0`; notices manifest SHA-256 `D3CFB87CC1BACE916DBF128158E3AE1FA8062904DEEA9C36009F184B0E42AC94` (847 bayt). Sınırlı bilgisayar tarafı kaynak/derleme kontrolleri geçti. Nihai adayın birim testleri, lint ve debug-assemble sonuçları beklemededir; native yükleme ve okunabilirlik kabulü yoktur. Gerçek hosted kaynak ve çalışma zamanı resource closure doğrulanmamıştır. Bu belge seçili profile uygun düz metin notice kaynağını ve upstream referanslarını verir.

## Haritada görünür atıf

**OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors**

Bağlantılar: [OpenFreeMap](https://openfreemap.org/) · [OpenMapTiles](https://openmaptiles.org/) · [OpenStreetMap telif ve ODbL lisansı](https://www.openstreetmap.org/copyright) (`https://www.openstreetmap.org/copyright`).

Bu kısa atıf haritanın üzerinde görünür olmalı. Uygulama içindeki çevrimdışı kaynak/lisans sayfası aşağıdaki geniş bildirimleri ve lisans adlarıyla tam URL'lerini ayrıca içermelidir; kullanıcı bu bildirimi ağ bağlantısı olmadan okuyabilmelidir. Varlıklarla dağıtılması gereken lisans/notice metinleri yerel kopyada bulunmalı; yalnız URL'ye bağımlı kalınmamalıdır. Bu dosya halen ürün içine kopyalanmış değildir. OSMF'nin standart `tile.openstreetmap.org` raster hizmeti ön indirme/çevrimdışı bölge indirmeyi yasaklar; OpenFreeMap sağlayıcı izni bu ayrı hizmetin yerine geçmez.

## Kaynak ve lisans bildirimi

- **İz'in Liberty türevi:** OpenFreeMap Styles deposunun [MIT lisansı](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/LICENSE.md); depoda görünen telif bildirimi `Copyright (c) 2023 Zsolt Ero`. İz'de kullanılan türev/uyarlama açıkça tanımlanmalı ve upstream kaynak gösterilmelidir. Bu MIT bildirimi, iç içe tasarım, kod veya harita verisi lisanslarını ikame etmez.
- **Liberty/Bright stil kodu ve tasarımı:** Liberty, OSM Bright/Mapbox Open Styles kökenini korur. Upstream BSD bildirimi KlokanTech.com, OpenMapTiles contributors (2016) ve Mapbox (2014) telif adlarını içerir; BSD 3-Clause koşulları ve feragat metni kopyayla korunmalıdır. Stil tasarımı hem CC BY 3.0 OSM Bright/Mapbox Open Styles kökenini hem CC BY 4.0 OpenMapTiles tasarımını taşır. Atıf/uyarlama kaydında kaynak, lisans ve İz değişiklikleri belirtilmelidir. [Liberty lisans bildirimi](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/styles/liberty/LICENSE.md) · [Bright lisans bildirimi](https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/styles/bright/LICENSE.md) · [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) · [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- **OpenMapTiles:** Vektör karo/şema bileşenleri için ilgili BSD 3-Clause kod bildirimleri ve CC BY 4.0 tasarım atfı korunur. Harita atfı `© OpenMapTiles` olmalıdır. OSM'den gelen verinin lisansı ayrıca ODbL'dir. Bir SDK veya uygulama lisansı bu içerikleri yeniden lisanslamaz.
- **OpenStreetMap verisi:** © OpenStreetMap contributors; [ODbL 1.0](https://www.openstreetmap.org/copyright). Verinin dağıtılması veya türetilmiş veritabanı dağıtımında ODbL yükümlülükleri ayrıca değerlendirilmelidir. Karo verisini uygulama lisansının kapsamındaymış gibi sunmayın.
- **Atlanmış upstream kaynaklar:** İncelenen tam Liberty upstream sprite atlası Liberty ve Bright görsellerini birleştirir; Maki CC0 alt kümesi tüm atlası CC0 yapmaz. Seçilen azaltılmış profilde sprite/pattern referansları çıkarıldığından runtime notice'a Maki, ok simgesi veya `spritezero-cli` lisansı eklenmez. Natural Earth raster kaynağı profilden çıkarılır; runtime notice'a Natural Earth atfı eklenmez. Bunlar aşağıdaki sabitlenmiş kaynak envanterinde tam Liberty araştırma girdileri olarak korunur.
- **Carlito yerel glyph fontu (Services kaynaklarına entegre; nihai modül/APK beklemede):** Kaynak [Carlito Regular static TTF](https://github.com/googlefonts/carlito/blob/3a810cab78ebd6e2e4eed42af9e8453c4f9b850a/fonts/ttf/Carlito-Regular.ttf), `googlefonts/carlito` revizyonu `3a810cab78ebd6e2e4eed42af9e8453c4f9b850a`. Kaynak TTF 628032 bayt, Git blob kimliği `427b95989fee609ab683d800ad00e22a4b14ecad`; bu değer SHA-256 değildir. Tam orijinal [OFL.txt](https://github.com/googlefonts/carlito/blob/3a810cab78ebd6e2e4eed42af9e8453c4f9b850a/OFL.txt) (blob `8d6b170e4d9a5580a34471dc4ac3e8fc5fbbd712`) ile [AUTHORS.txt](https://github.com/googlefonts/carlito/blob/3a810cab78ebd6e2e4eed42af9e8453c4f9b850a/AUTHORS.txt) (blob `8b620756ad3daa04ec42dfc95195894fa8409bb1`) aynı revizyondadır. Orijinal OFL bildirimi: **Copyright 2013 The Carlito Project Authors** (`https://github.com/googlefonts/carlito`), Reserved Font Name **Carlito**; AUTHORS dosyasında Łukasz Dziedzic listelenir. Kaynak OFL ve AUTHORS dosyaları çıktı ile byte-korunarak dağıtılmalı; [SIL OFL 1.1](https://openfontlicense.org/open-font-license-official-text/) metni yalnız bağlantı olarak bırakılmamalıdır.
- **Carlito türevi ve adlandırma:** OFL, biçim dönüştürmeyi değiştirilmiş font sürümü sayar. Üretilen glyph stack ayrı ad kullanır: **Iz Offline Latin Regular**; türev OFL 1.1 altında kalır, yeni Carlito sürümü veya Noto değildir, uygulamanın GPL lisansını değiştirmez. Yalnız tek Regular yüzü bütün etiket katmanlarına uygulanacağından Liberty'nin mevcut Regular/Bold/Italic hiyerarşisi basitleşir; bu okunabilirlik kabulü değildir. Services kaynaklarındaki OFL/AUTHORS varlıkları upstream dosyalarıyla byte-exact eşleşir. Mühürlü font bundle manifesti `iz.offline-font.bundle.v1` SHA-256 `C19E8462BC293B89A780AA1A0D246F71D07FE30DD07E8E61ABF4F2F6CE0AEDC0` değerindedir; bundle 261 dosya kaydeder ve 260 manifest girdisi bağımsız olarak yeniden hash'lenmiştir. Services profile 265 tam eşleşen asset entegre edilmiştir. Notices manifesti `D3CFB87CC1BACE916DBF128158E3AE1FA8062904DEEA9C36009F184B0E42AC94` SHA-256 ve 847 bayttır. Sınırlı bilgisayar tarafı kaynak/derleme kontrolleri geçti; nihai adayın birim testleri, lint ve debug-assemble sonuçları, native yükleme ve okunabilirlik kabulü beklemededir.
- **Glyph kapsamı ve tamamlanma:** Carlito metadata'sında Latin/Latin Extended beyanı Türkçe/Latin adayını destekler; Türkçe `Ç/ç, Ö/ö, Ü/ü, Ğ/ğ, İ/ı, Ş/ş` harflerinin TTF cmap, etiket kullanımındaki glyph kimlikleri ve gerçek render ile doğrulanması gerekir. Mühürlü bundle tüm 256 BMP PBF aralığını içerir; bu aralık envanteri tek başına her yol/yer adı için doğru glyph kapsamını veya çizim sonucunu kanıtlamaz. Bu seçim Arapça, İbranice, CJK, Indic/karmaşık yazılar, emoji veya ek düzlem karakter kapsamı vaat etmez. Mühürlü bundle 256 BMP PBF aralığı, toplam 2.117 glyph, 232 geçerli boş PBF ve toplam 807.207 PBF baytı içerir. Bu envanter tek başına eksik glyph davranışını, gerçek Türkçe etiket örneklerini, okunabilirliği veya MapLibre Native kabulünü kanıtlamaz; Services asset entegrasyonu tamamdır; nihai modül/APK derlemesi ve native yükleme/okunabilirlik kabulü beklemededir.
- **MapLibre Native:** Uygulamada kullanılan SDK 13.6.1'in [BSD 2-Clause lisansı](https://github.com/maplibre/maplibre-native/blob/android-v13.6.1/LICENSE.md) SDK koduna ilişkindir; yukarıdaki verilerin, stilin, sprite'ın veya fontun lisansını sağlamaz. Projenin GPL ve mevcut üçüncü taraf bildirimleri bağımsız olarak korunur.

## Seçili profile uygun düz metin notice

Aşağıdaki metin Services'in yerel `noticeText()` ve offline notice okuyucusu için kaynak olarak hazırlanmıştır. Tam upstream lisans metinlerini taklit etmez; bunlara bağlantı verir. Carlito'nun orijinal OFL/AUTHORS dosyaları ayrı ve byte-korunmuş yerel varlıklar olarak eşlik etmelidir. Metin henüz Services kaynak koduna veya uygulamaya aktarılmış değildir.

```text
İz çevrimdışı harita: kaynaklar ve lisanslar

Harita atfı
OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors
OpenFreeMap: https://openfreemap.org/
OpenMapTiles: https://openmaptiles.org/
OpenStreetMap telif ve ODbL lisansı: https://www.openstreetmap.org/copyright
OpenFreeMap hizmet koşulları: https://openfreemap.org/tos/

Harita stili
Bu İz haritası, OpenFreeMap Liberty stilinden uyarlanmış etiketli vektör stilidir. Kaynak kodu MIT lisanslı OpenFreeMap Styles deposundandır (Copyright (c) 2023 Zsolt Ero); iç içe stil kodu BSD 3-Clause bildirimlerini, tasarım ise OSM Bright / Mapbox Open Styles CC BY 3.0 kökeni ile OpenMapTiles CC BY 4.0 atfını korur. İz'in yerel uyarlama kaydı ve ilgili lisans bildirimleri kaynak belgelerinde yer alır.
OpenFreeMap Styles kaynak ve lisansları: https://github.com/hyperknot/openfreemap-styles/tree/006ea5796803ba2df4330888836cb0a8f17473b4
OpenFreeMap Styles MIT notice: https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/LICENSE.md
Liberty lisans bildirimi: https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/styles/liberty/LICENSE.md
Bright lisans bildirimi: https://github.com/hyperknot/openfreemap-styles/blob/006ea5796803ba2df4330888836cb0a8f17473b4/styles/bright/LICENSE.md
CC BY 3.0: https://creativecommons.org/licenses/by/3.0/
CC BY 4.0: https://creativecommons.org/licenses/by/4.0/

Harita verisi
© OpenStreetMap contributors. OpenStreetMap verisi Open Database License (ODbL) kapsamındadır: https://www.openstreetmap.org/copyright
OpenMapTiles kod bildirimi BSD 3-Clause; OpenMapTiles tasarım atfı CC BY 4.0.
OpenMapTiles atfı ve tasarım bildirimi: https://openmaptiles.org/

Çevrimdışı Latin glyph fontu
Carlito Regular. Copyright 2013 The Carlito Project Authors: https://github.com/googlefonts/carlito
Orijinal font SIL Open Font License 1.1 kapsamındadır; Reserved Font Name: Carlito. Dönüştürülmüş SDF glyph stack adı: Iz Offline Latin Regular. Dönüştürülmüş font OFL 1.1 lisansı altında kalır.
Tam ve değişmemiş OFL.txt ile AUTHORS.txt dosyaları glyph varlıklarıyla birlikte dağıtılmalıdır. AUTHORS.txt Łukasz Dziedzic'i listeler. Sabit kaynak revizyonu: https://github.com/googlefonts/carlito/tree/3a810cab78ebd6e2e4eed42af9e8453c4f9b850a
OFL.txt: https://github.com/googlefonts/carlito/blob/3a810cab78ebd6e2e4eed42af9e8453c4f9b850a/OFL.txt
AUTHORS.txt: https://github.com/googlefonts/carlito/blob/3a810cab78ebd6e2e4eed42af9e8453c4f9b850a/AUTHORS.txt
OFL 1.1 metni: https://openfontlicense.org/open-font-license-official-text/

Harita motoru
MapLibre Native Android 13.6.1, BSD 2-Clause lisanslıdır: https://github.com/maplibre/maplibre-native/blob/android-v13.6.1/LICENSE.md
SDK lisansı harita verisi, stil veya font lisanslarının yerini almaz.

Profil kapsamı
Bu profilde etiketli vektör yol/yer katmanları ve yukarıda adı geçen Carlito tabanlı glyph stack'i bulunur. Glyph üretici yalnız build-time aracıdır; uygulama paketine eklenmez. Uygulamanın GPL ve diğer bağımlılık bildirimleri ayrıca geçerlidir.
```

Bu metin ancak gerçek seçilmiş style/font corpus manifestiyle eşleştiğinde asset olarak kullanılmalıdır. Font bundle ve notice manifesti Services kaynaklarına entegre edilmiştir; sınırlı bilgisayar tarafı kontrolleri geçmiştir. Nihai modül/APK derlemesi, runtime notice/harita ve native okunabilirlik kabulü beklemededir.

## Services offline notice manifestine bağlama

Services'in seçili profil sözleşmesi `iz:offline:profile` metadata'sında `id=iz-offline-labelled-latin-v1`, gerçek `fontBundleSha256` ve gerçek `noticesSha256` değerlerini taşır. Yerel notice manifesti `notices/<manifestSHA>.json` yolunda `iz.offline.notices.v1` şemasını kullanır: `profileId`, aynı font bundle hash'i ve her notice dosyası için `path`, `bytes`, `sha256`. `files` girdileri yalnız seçili profile ait tam kaynak notice metinlerini içermelidir: OpenFreeMap MIT, ilgili Liberty/Bright BSD ve CC BY 3.0/4.0 bildirimleri ile Carlito OFL/AUTHORS; upstream license/copyright metinleri gerekiyorsa byte-korunmuş halleriyle ayrı yerel dosyalara alınır. OpenStreetMap/OpenMapTiles atfı da bu profile ait notice text'te yer alır. Mühürlü font bundle SHA-256 `C19E8462BC293B89A780AA1A0D246F71D07FE30DD07E8E61ABF4F2F6CE0AEDC0` ve notices manifest SHA-256 `D3CFB87CC1BACE916DBF128158E3AE1FA8062904DEEA9C36009F184B0E42AC94` olarak doğrulanmıştır; notices manifesti 847 bayttır. Services kaynaklarında seçili profile ait 265 tam eşleşen asset entegredir. Hosted kaynakların gerçek/runtime closure doğrulanmamış; nihai modül/APK ve native kabul beklemededir.

Services assembles the local notices manifest at notices/<manifestSHA>.json using schema iz.offline.notices.v1, profileId iz-offline-labelled-latin-v1, the actual fontBundleSha256, and files[{path,bytes,sha256}]. Hashes are recorded only from actual handed-off files. This source text accompanies the integrated local notices manifest; neither proves final module/APK assembly nor offline runtime display.

Kayıtlı harita oturumu `session.noticeText()` değerini okuyucuya verir; metin yerel olmalı ve ağ gerektirmemelidir. Manifest/profile uyuşmazlığında başka profile ait genel fallback metni gösterilmemeli; atlanan sprite, raster veya font lisansları seçili profile varmış gibi eklenmemelidir. Font bundle ve notice manifesti Services kaynaklarına entegre edilmiş, sınırlı bilgisayar tarafı kontrolleri geçmiştir. Hosted kaynak/resource closure doğrulanmamış; nihai modül/APK derlemesi, offline runtime ve native okunabilirlik kabulü beklemededir. Bu belge kaynak, lisans ve manifest referanslarını verir; çalışma zamanı kabulü iddia etmez.

## İncelenen kaynak revizyonları

Bu pinler upstream kaynak araştırma envanteridir; mevcut seçilmiş corpus'un dosya listesi değildir ve OpenFreeMap sunucusundan alınmış byte'ların/yayındaki sürümlerin kriptografik kanıtı sayılmaz. Aşağıdaki tam Liberty/Noto/sprite kayıtlarının bir kısmı yalnız incelenen upstream zinciri belgeliyor; seçilmiş azaltılmış profile eklenmiş olduklarını göstermez.

| Kaynak | İncelenen revizyon | Kullanım |
|---|---|---|
| [OpenFreeMap Styles](https://github.com/hyperknot/openfreemap-styles/tree/006ea5796803ba2df4330888836cb0a8f17473b4) | `006ea5796803ba2df4330888836cb0a8f17473b4`; Liberty style blob `ca0730f12f398e1a743ddfbbde7336659112e939` | Stil kaynağı, lisans ailesi, sprite/font/NE üretim zinciri |
| [OpenFreeMap host](https://github.com/hyperknot/openfreemap/tree/332669abe580db3c6e06e1fd6d3f338fb5a5306f) | `332669abe580db3c6e06e1fd6d3f338fb5a5306f` | Alan adı çözümleme, TileJSON ve servis sunum mantığı |
| [MapLibre demotiles](https://github.com/maplibre/demotiles/tree/601ae60796ceceda2cbd2ed3d2ea92d17a84be4b) | `601ae60796ceceda2cbd2ed3d2ea92d17a84be4b` | OpenMapTiles fonts kaynak belgesi; OFM fontlarının gerçek yayın revizyonu değil |
| [OpenMapTiles fonts](https://github.com/openmaptiles/fonts/tree/d48c5fce2fc58b55c98d353558d807cac45e7262) | `d48c5fce2fc58b55c98d353558d807cac45e7262` | Önceki tam Liberty Noto kompozitinin araştırma girdisi; seçilmiş azaltılmış profilde kullanılmıyor |
| [Carlito font kaynağı](https://github.com/googlefonts/carlito/tree/3a810cab78ebd6e2e4eed42af9e8453c4f9b850a) | `3a810cab78ebd6e2e4eed42af9e8453c4f9b850a`; Google Fonts dağıtımındaki TTF/OFL revizyonu `6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec` | Seçilmiş Regular TTF/OFL/AUTHORS kaynak pinleri; yerel türev çıktısı ayrı mühürlü bundle manifestine bağlanmıştır |
| [MapLibre Native Android](https://github.com/maplibre/maplibre-native/tree/android-v13.6.1) | `android-v13.6.1` | Yerel stil yükleme API'si; offline render kabulü değil |

### Glyph üretim aracı — build-time, APK dışı

Seçilmiş üretim yolu [MapLibre font-maker kaynak revizyonu `714ccaea67d384717890a3c2c27143dfdd4189b7`](https://github.com/maplibre/font-maker/tree/714ccaea67d384717890a3c2c27143dfdd4189b7) ile onun yayınladığı [WASM artifact revizyonu `bab9b243413cb17b66b158d629a4bac2d7299786`](https://github.com/maplibre/font-maker/tree/bab9b243413cb17b66b158d629a4bac2d7299786) üzerindendir. Yayın ağacında `sdfglyph.js` (220444 B, blob `1d41d17ac07f550dfc63c00ff352fba455031288`) ve `sdfglyph.wasm` (893164 B, blob `9befced36392e39f17cf18cb9c796b1109e0af29`) belirtilir. Kaynak/telif bildirimi [font-maker LICENSE.txt](https://github.com/maplibre/font-maker/blob/714ccaea67d384717890a3c2c27143dfdd4189b7/LICENSE.txt) BSD 3-Clause olup MapLibre contributors (2022), Protomaps LLC (2019–2022) ve Mapbox (2014) bildirimlerini içerir. Bu araç yalnız build-time üretim ortamı içindir; **APK'ya eklenmeyecektir**. Kaynak blob kimlikleri yayın artefaktı SHA-256'ları değildir.

Tam Liberty upstream sprite envanterinde `ofm.json` (`99fd5bc5f9eade5fbda429ed4fbf4e9a6d01aef1`), `ofm.png` (`8ac87bc1ccd91b37a44ae70e811642ee9d60c212`), `ofm@2x.json` (`15b71ede025a946fa6113a1ad580f4eaf61c6326`) ve `ofm@2x.png` (`224898ba7f072052d77760582cb02fdd1b63d56a`) blob kimlikleri kaydedilmiştir. Bu varlıklar seçilen azaltılmış profile dahil değildir. Blob değerleri repository object ID'leridir, dağıtılacak varlıkların SHA-256 değeri değildir; dosyalar bu çalışma sırasında indirilmemiştir.

## Etiketli harita ve açık kalanlar

Ürün hedefi okunabilir yol ve yerleşim adlarını koruyan bir haritadır. **Etiketsiz vektör harita, geçici ara fallback olabilir; bu ürün hedefinin tamamlandığı veya haritanın hazır olduğu anlamına gelmez.** Yazı tipi/altyapı kapanışı yoksa etiketli harita hazır diye işaretlenmemeli; Core/UI/UX nihai ürün durumunu doğru göstermelidir. Stil sadeleştirmesi yol/yer etiketlerini sessizce kaldırmamalıdır.

- Hosted Liberty JSON/TileJSON'ın fiili yanıtı, çözülmüş domain ve kaynak byte'ları ölçülmedi. Depo revizyonları bunları dondurmaz; geriye dönük görünen planet sürüm numarası da immutable veri garantisi değildir.
- Gerçek endpoint'ten gelen TileJSON hash'leri ve CDN kaynak kimliği doğrulanmamıştır. Seçili profile alınan vektör kaynakları için hosted resource closure ve gerçek byte kimlikleri hâlâ gerekir; glyph bundle kendi mühürlü manifest ve hash'ine sahiptir. Sprite/pattern ve Natural Earth raster kaynakları profile dahil değildir.
- Carlito orijinal lisans/author notice kaynağı ve ayrı türev adı belirlenmiştir; glyph bundle artık mühürlü ve manifest girdileri bağımsız olarak yeniden hash'lenmiştir; Services asset entegrasyonu ve 265 tam eşleşen asset bildirimi tamamdır. Nihai adayın birim testleri, lint ve debug-assemble sonuçları, native yükleme ve okunabilirlik kabulü beklemededir. Hosted stil/TileJSON yanıtı ve gerçek runtime resource closure doğrulanmamıştır.
- Bu nedenle belge kaynak ailesi ve bildirimi tarif eder; uygulama içi/offline notice kurulumu, hosted kaynakların gerçek/runtime closure'ı, nihai modül/APK veya native harita sunumu/okunabilirlik kabulü iddiası taşımaz.
