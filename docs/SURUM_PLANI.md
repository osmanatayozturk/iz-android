# İz sürüm değişikliği planı

## Telefon 0.13.2 / code 31 — haritadan OSM düzenleme (yayımlanmamış kaynak adayı)

Ana haritaya uzun basarak yeni OSM mekânı ekle. Ad, tür ve telefon bilgilerini gir; türü aranabilir listeden seç ve sık kullandıklarını yıldızla favorilerine ekle. Yeni formda tür seçimi boş başlar. Mevcut desteklenen nokta ve basit kapalı alanların bilgileri aynı sade düzenleyiciden değiştirilebilir.

Tür listesi uygulamayla birlikte gelen iD Tagging Schema kataloğunu kullanır; ek arama servisi veya API anahtarı gerekmez. Seçilebilir türler nesnenin geometrisine göre süzülür. Bina içindeki yeni işletmeler ayrı nokta olarak eklenir; bina çizimi ve geometri taşıma bu sürümün kapsamı dışındadır.

Yeni yer eklemedeki çevre/mükerrer nesne taraması kullanıcının tercihiyle kaldırıldı. Mevcut nesnelerde güncel sürüm denetimi, gönderim önizlemesi ve belirsiz sonuçta otomatik yeniden göndermeme korunur. Ad veya telefonu bilerek boşaltmak silme olarak önizlenir; diğer etiketler ve geometri korunur. Favoriler ile yeni taslak alanları yedeklenir; eski kayıtlar okunmaya devam eder.

**Doğrulama: BEKLİYOR.** Birim/lint/derleme, izole QA, bağımsız Test/UIUX ve güvenlik sonuçları aynı son adaydan alınacak. Kısmi JVM sonuçları bu paragrafta Android veya cihaz kabulü gibi sunulmayacak.

| Aşama | Kullanıcıya dönük sonuç |
|---|---|
| Yeni mekân | Ana haritaya uzun bas, adı ve telefonu gir; başlangıçta boş olan türü yerel katalogdan seç. |
| Tür ve favoriler | Aranabilir türleri geometriye göre süz; sık kullanılan türleri yıldızla ve yedekte koru. |
| Mevcut mekân | Desteklenen nokta veya basit kapalı alanın bilgilerini önizle; açık silme niyetini ve güncel OSM sürümünü denetle. |
| Kabul | Son kaynak sabitlenmesi, birim/lint/derleme, izole QA, bağımsız Test/UIUX ve güvenlik doğrulaması bekleniyor. |

## Telefon 0.13.0 / code 29

Büyük yedekler sayfalı hazırlanır. ZIP kaydedildiğinde geri okunup doğrulanır. Geri yüklemede arşiv ve ilişkiler denetlenir. Kamera kadrajı iyileştirmeleri, Molalı rota ve isteğe bağlı OSM GPS izi paylaşımı da sürüm kapsamındadır. Wear OS 0.7.0 / code 12 ve iletişim protokolü değişmiyor. [Telefon 0.13.0 sürüm sayfası](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.13.0).

| Aşama | Kullanıcıya dönük sonuç |
|---|---|
| Yedekleme | Büyük yedekler sayfalı hazırlanır. ZIP kaydedildiğinde geri okunup doğrulanır. Geri yüklemede arşiv ve ilişkiler denetlenir. |
| Kamera kadrajı | Yönlendirme bilgisini harita alanından ayrı tut ve takip hareketini yumuşat. |
| Molalı rota | Planlı molaları rota zamanlaması ve yolculuk kartıyla eşleştir. |
| OSM GPS izi paylaşımı | Tamamlanmış yolculuk izlerini açık kullanıcı onayıyla isteğe bağlı paylaş. |
| Nereye? (telefon kaynak adayı) | Ana ekrandan kayıtlı bir yer veya OpenStreetMap arama sonucu seç. Ulaşım türünü belirleyip mevcut konumdan rota başlat. |
| Nereye? yakınlık önceliği (telefon kaynak adayı) | Konum kullanılabiliyorsa yaklaşık arama alanıyla yakındaki eşleşmelere öncelik ver ve sonuçları cihazda mesafeye göre sırala. Konum yoksa servis sırasını kullan. |
| Nereye? kısmi ad araması (canlı kabul bekliyor) | Nominatim’de uygun eşleşme yoksa aynı alanda tek Overpass ad araması kullan. Özgün sorguyu sözcük başlangıçları ve kısaltmalarla doğrula. Toplam istek sayısını ikiyle sınırla. |
| OSM toplu iz (telefon kaynak adayı) | Tamamlanmış yolculuk izlerini birlikte seç, gönderimi inceleyip onayla ve sonucu bağlı hesabın ayrı **Yüklenenler** sayfasında kontrol et. |
| Ev/İş (telefon kaynak adayı) | Boştaki ana haritada logo ve İz başlığının altında Nereye?, ardından Ev ve İş yer alır. Yer atadıktan sonra kısayol, hızlı hedef ekranını seçili hedefle açar. |
| OSM Katkı Merkezi (telefon kaynak adayı) | Yerel taslakları ve bağlı hesabın gönderimlerini gör. GPS izini her uçta sabit 1 km eşiğiyle kırpılmış önizlemeden incele ve iki görünürlükten birini seç. |
| OSM mekân düzeltmeleri ve yedek | Yerinde doğrulanan nokta veya tek kapalı çizgiyle tanımlanan alanın adını, sokağını ve kapı numarasını düzelt. Düzeltme kayıtlarını Room ve ZIP yedeklerinde sakla. |
| OSM’de yerinde tamamla (canlı kabul bekliyor) | 1 km içindeki görevleri harita veya listeden aç. Mevcut mekânın çalışma saatlerini ve tekerlekli sandalye erişimini yerinde gözlemle, alanın kontrol tarihini kaydet. |
| Saat formu ve yedek uyumluluğu | Haftalık form veya gelişmiş OSM ifadesi kullan. Kısmi katkıyı ve yeniden kontrol durumunu Room ve ZIP v10 ile sakla. |
| Ortak arama ve paylaşılan harita girişi (telefon kaynak adayı) | Yer seçme ekranlarında ortak aramayı kullan. Desteklenen harita bağlantılarını hedef/rota önizlemesinde incele ve ulaşım türünü seç. |
| Çok duraklı rota (telefon kaynak adayı) | Başlangıç ve varış dahil en fazla 20 durak planla; gerektiğinde mevcut konumdan yaklaşımı etkin rotaya ekle. |
| Mekân bilgi kartı (telefon kaynak adayı) | Harita veya kayıtlı yerden OSM bilgilerini, güncellik ve eksik bilgi durumunu gör; yol tarifine ve uygun iletişim işlemlerine geç. |
| Paylaşım girişi ve sürüş göstergesi düzeltmeleri (telefon kaynak adayı) | Paylaşım önizlemesini navigasyon girişinde göster; yatay kompakt göstergede iki satırlı özeti, mola ve uyarı metinlerini düzenle. |

**Ortak arama ve mekân bilgileri kaynak adayının sınırlı doğrulaması:** `fa82701f` için beş odaklı fiziksel telefon kontrolü geçti. 1.643 birim testi ile uygulama ve test paketi derleme kanıtı, yalnız AndroidTest düzeltmesinden önceki eşleşen ürün kaynağından açıkça yeniden kullanıldı. Lint, başarıyla tamamlanan toplu host çalıştırmasında istendi; ancak eldeki lint raporu tarihsel olduğundan bu çalıştırmanın ayrı lint görev sonucu ve yeni XML raporu doğrulanmış değildir. İncelenen üç HUD görüntüsünde kalan ürün engeli görülmedi; normal yazı boyutunda mola ve uyarılar okunabildi. Üç piksel tabanlı rota görünürlüğü denetimi başarısız kaldı ve geçilmiş sayılmadı; yöntem gösterge/pusula örtüşmesini yeterince kapsamıyor. Tam native arayüz, TalkBack ve canlı harita tabanı kabulü tamamlanmadı. Ayrıntılı kapsam [değişiklik geçmişinde](../CHANGELOG.md) yer alır.

**Nereye? doğrulaması.** Kaynak adayı `ad84d727` üzerinde 193 test grubunda 1.410 birim testi geçti. Başarısızlık, hata ve atlama sayıları sıfırdı. Debug lint 97 uyarı ve 3 ipucu verdi, hata vermedi. Debug uygulama ve AndroidTest APK’ları bilgisayarda derlendi.

Fiziksel Samsung telefonda ağsız QA paketiyle yapılan ilk koşuda üç sentetik hızlı hedef senaryosu geçti. MainActivity’de Nereye? giriş ve geri dönüş kontrolü, MapLibre’ın ağ isteği izin hatası verince tamamlanamadı. Aynı ürün APK’sıyla yapılan ayrı koşuda, yalnız test paketine eklenen çevrimdışı MapLibre kuralıyla giriş ve geri dönüş kontrolü geçti.

**OSM toplu iz ve Ev/İş doğrulaması.** Kaynak adayı `a1d1ad5d` üzerinde 1.426 birim testi geçti. Başarısızlık, hata ve atlama sayıları sıfırdı. Debug lint 98 uyarı ve 1 ipucu verdi, hata vermedi. Debug uygulama ve AndroidTest APK’ları bilgisayarda derlendi. Fiziksel Samsung telefonda ayrı çevrimdışı QA paketiyle 7/7 kontrol geçti. Kapsam ana ekrandan Ev/İş atama, seçili hedefle ulaşım türü seçme, ana ekran yerleşimi, geri dönüldüğünde geciken rota sonucunun uygulanmaması ve yeni yer kaydıydı. Canlı rota ve OSM gönderimi kapsam dışıydı.

**Nereye? arama doğrulaması.** `22678f2d` üzerinde 1.438 birim testi geçti ve Debug lint 0 hata verdi. Debug uygulama ve AndroidTest APK’ları bilgisayarda derlendi. Son aday `aeb44d5d` üzerinde fiziksel Samsung telefonda ayrı QA paketiyle 2/2 sentetik kontrol geçti. Kapsam seçilen arama sonucunun koordinatlarıyla yönlendirme başlatma, klavyeden arama ve geri dönüşte seçimlerin korunmasıydı.

**Geri yükleme koruması.** Geri yüklenen mekân gönderimlerinde İz’den durum kontrolü yapılabilir ve kayıt OSM’de açılabilir. İz’den bir değişiklik kümesini kapatmak için bu kurulumun OSM’den aldığı gerçek oluşturma yanıtını kaydetmiş olması gerekir.

**OSM Katkı Merkezi doğrulaması.** `aa13500` üzerinde 1.467/1.467 birim testi geçti. Debug lint 0 hata verdi. Debug uygulama ile AndroidTest APK’ları derlendi. Aynı adayın ayrı QA APK çiftiyle fiziksel Samsung telefonda çevrimdışı 12/12 sentetik kontrol geçti ve iki QA paketi kaldırıldı. İki ek kontrol, aynı kimlikle geri yüklenen kayıtta yerel kapatma yetkisinin temizlendiğini ve gecikmiş oluşturma yanıtının reddedildiğini, ayrıca kimlik ve zaman damgası eşleşse bile önceki yanıtın geri yüklenen kayda bağlanamadığını doğruladı. Canlı OSM işlemleri test kapsamı dışındaydı.

**Nereye? kısmi ad araması.** Canlı Overpass kabulü bekliyor. Yapılandırılmış uç noktada HTTP 502 alındı, önceki denemelerde zaman aşımı da görüldü. Gerekli iki canlı sorgunun kabulü tamamlanmadı.

`ae038e7` üzerinde 1.487/1.487 birim testi geçti. Debug lint 0 hata, 96 uyarı ve 1 ipucu verdi. Debug APK üretimi ve AndroidTest Kotlin derlemesi geçti. Derlenen ayrı QA APK çiftiyle fiziksel Samsung’da kısmi ad ve kısaltma sonuçlarının seçilen koordinatlarla ulaşım türü seçimine ulaştığı iki sentetik kontrol geçti. QA temizliği 2/2 tamamlandı.

**Yerinde bilgi tamamlama doğrulaması.** `3adea536` üzerinde 1.536/1.536 birim testi geçti. Debug lint 0 hata ve 96 uyarı verdi. Debug APK üretimi ve AndroidTest Kotlin derlemesi geçti. Aynı kaynağın ayrı çevrimdışı QA paketiyle fiziksel Samsung’da 26/26 sentetik kontrol geçti. Telefon kapsamı 20 Compose, 5 Room/ZIP/geri yükleme ve 1 native akış kontrolüydü. Beş native ekran görüntüsü incelendi ve QA temizliği 2/2 tamamlandı.

**Canlı kabul bekliyor.** Yapılandırılmış Overpass uç noktasındaki son yakın görev sorgusu zaman aşımına uğradı. Canlı harita, gerçek GNSS, gerçek OSM hesabıyla yazma ve manuel TalkBack bu telefon turunun kapsamı dışındaydı.

Yayımlanmış önceki sürüm Telefon 0.12.0 / code 28’dir. Bu bölüm, 0.12.0 yayımlanmadan önceki aday doğrulama anlık görüntüsünü ve yayımlanmış 0.12.0’dan sonra 6–7 Kasım’da aynı 0.12.0 numarasıyla yapılan kamera, Molalı rota ve OSM GPS izi paylaşımı kaynak güncellemelerini birlikte kaydeder. Sonraki kaynak güncellemeleri yayımlanmış 0.12.0 sürümünün içeriği değildir. Bu tarihsel doğrulama ve kaynak kayıtları 0.13.0 için kabul veya doğrulama oluşturmaz. Yayımlanmış 0.12.0’ın release notları için [GitHub sürüm kaydı](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.12.0) esas alınır.

## Telefon 0.12.0 numarasıyla tutulan tarihsel aday kayıtları

Aşağıdaki bilgiler 0.12.0 yayımlanmadan önceki aday doğrulama anlık görüntüsüdür. Telefon 0.12.0 / code 28 daha sonra yayımlanmıştır. S2 v7 Wear komut manifesti filtresi/guard’ı, S3 derinliği 64’te sınırlı katı JSON ön ayrıştırması sağlayıcı/cache-restore/normalize aktarım yollarını kapsar. Dört hedefli host aşaması geçti; telefon birim testlerinde 1.309/175 yöntem/suite bu adayda yeni çalıştırıldı ve 0 başarısızlık/hata/atlama görüldü. Wear 82/15 ile protokol 48/3 önceki adaydan eşdeğerlik kanıtıyla yeniden kullanıldı; 1.439/193 birleşik envanter yeni yürütme toplamı değildir. App lint 0 hata/ölümcül hata ve 96 uyarı verdi; Wear lint 29 uyarısı tarihsel sonuçtur. Telefon Debug APK metadata/imza/varlık denetimi geçti; 265 çevrimdışı varlığın kaynak baytlarıyla eşleştiği görüldü. Derleme manifestinde v1–v7 komut ve v4 Health yolları statik olarak kapsandı; runtime listener teslimi doğrulanmadı. Bu kanıtlar dağıtım, kurulum, tam UX veya çalışma zamanı kabulü değildir. U1 sınırlı değerlendirmedir; TTS/duyulabilirlik, tam okunabilirlik/UX, canlı sağlayıcı, native/cihaz ve fiziksel doğrulama yapılmadı; telefon/native/cihaz HOLD durumundadır. Kaynak/belge yayını aday bazlı güvenlik ve koordinatör kabulüne tabidir; dağıtım ve cihaz doğrulaması ayrı izlenir. Aşağıdaki “Bilgisayarda doğrulandı” durumu yalnız belirtilen toplu host kontrollerine aittir; aşama başına uçtan uca kabul değildir.

| Aşama | Kullanıcıya dönük sonuç | Uygulama ve sınırlı host durumu |
|---|---|---|
| S1 Sesli yönlendirme toparlanması | Gerçek teslim/odak/yaşam döngüsü sonuçlarından sonra yalnız güncel ve hâlâ uygun talimatı kurtar; eski/iptal/süresi geçmiş anonsu oynatma, oturumu devralma veya kaydı durdurma. | Uygulandı; sınırlı host kontrolleri geçti (aşamaya özgü uçtan uca kabul değildir) |
| S2 Saat ve Android Auto durumları | Telefon/veri bağlantısı, GPS güncelliği, etkin işlemler, varış/simülasyon, harita ve ses hazırlığını ayrı göster; eksik değeri sıfır sayma. Saat telefon kaynaklı ve bilgi amaçlı kalır. | Uygulandı; sınırlı host kontrolleri geçti (aşamaya özgü uçtan uca kabul değildir) |
| S3 Çevrimdışı rota ve harita hazırlığı | Valhalla rotası ile seçilmiş izinli OpenFreeMap koridorunu ayrı hazırla; indirme yönetimini açık kullanıcı eylemleriyle sun. Hazır rota yeni rota hesabı veya hazır harita demek değildir. | Uygulandı; sınırlı host kontrolleri geçti (aşamaya özgü uçtan uca kabul değildir) |
| S4 Rota düzenleme ve duraklar | Çevrimiçi desteklenen bölüm kaçınma, uygun ara durak atlama ve duraklat/sürdür önizleme/onay akışlarını; sınırlı yakındaki yakıt/dinlenme aramasını destekle. Hata/iptal mevcut rotayı korur. | Uygulandı; sınırlı host kontrolleri geçti (aşamaya özgü uçtan uca kabul değildir) |
| S5 Karşılaştırma ve GPS izi ayrıntıları | İki uygun tamamlanmış yolculuğun hazırlanmış verisini karşılaştır; kararlı GPS kaydından dönüş/eğri ayrıntısını deneysel yatış tahmininden bağımsız göster. | Uygulandı; sınırlı host kontrolleri geçti (aşamaya özgü uçtan uca kabul değildir) |
| Kamera kadrajı ve rehberlik alanı | Kompakt yönlendirme bilgilerini harita alanının dışında tut. Hız uyarlamalı kamera takibini Telefon ve Android Auto'da yumuşat. Yakın planı yalnız yön değişikliği içeren manevralarda kullan. | 6 Kasım kaynak güncellemesinde App birim testlerinde 1.327 test ve 177 suite çalıştı. Başarısızlık, hata veya atlama olmadı. Odaklı kontroller 44/44 geçti. Lint 0 hata ve 96 uyarı verdi. Debug derleme ve paketleme geçti. Önceki sentetik ölçümler yalnız aynı kamera yolları ve girdileri için destekleyicidir. Seçilen dokuz sınıf grubundaki 69 sınıf önceki derlemeyle birebir eşleşti. Yeni APK üzerinde çalışma zamanı ve AndroidTest kontrolleri çalıştırılmadı. Fiziksel telefon ve DHU kontrolleri de çalıştırılmadı. |
| Molalı rota ve yolculuk kaydı (7 Kasım kaynak güncellemesi) | Planlı molaları rota zamanlamasına ve navigasyon kartına yansıt. Erken dönüşte ve mola süresi aşılınca tahmini varış saatini güncelle. | App/Wear/ortak protokol birim testleri sırasıyla 1.370/82/48. Toplam 1.500 testte başarısızlık, hata veya atlama yok. Lint 0 hata ve 125 uyarı verdi. Atanmış fiziksel kontrolde sentetik iki molalı akışın 640×320 dp yatay Ana ekran sabit kontrolleri, normal yazı boyutunda geçti. Tam kaydırılmış ayrıntı görünümü doğrulanmadı. Gerçek GNSS, üretim kayıt servisi, duyulabilir TTS ve canlı harita etiketleri doğrulanmadı. Diğer düzenler ve erişilebilirlik dolaşımı da doğrulanmadı. Bu, 0.12.0 öncesi aday kaydındaki sınırlı kaynak ve fiziksel UI/UX kanıtıdır. |
| OSM GPS izlerinin isteğe bağlı paylaşımı (7 Kasım kaynak güncellemesi) | Tamamlanmış ve onaylanmış yolculuklar çoklu seçilir. Her uçtan en az 1 km kırpılır. İz 2 km veya daha kısaysa ya da çizilebilir bölümü kalmadıysa engellenir. Görünürlük sabit `identifiable` olur ve açık hesap/veri onayı gerekir. Kısmi ve belirsiz sonuçlar korunur. İz listesi yalnız belirsiz gönderimler için kontrol edilir. | 1.397 JUnit testi 0 başarısızlık, 0 hata ve 0 atlamayla geçti. Lint 0 hata ve 97 uyarı verdi. Debug derleme ve paket incelemesi geçti. Yerel MockWebServer testleri ayrı host kontrolleridir. Tek yalıtılmış fiziksel Android telefon QA senaryosu sentetik hesap, kayıt ve taşıma kullandı. Gerçek OSM hesabı veya yüklemesi yapılmadı. Release/gündelik kurulum, Wear/Auto çalışma zamanı ve geniş cihaz matrisi doğrulanmadı. |

**Sabit sınırlar:** S3 indirme başına hem 3.000 karo hem 50.000.000 bayt üst sınırını, en çok dört eşzamanlı isteği, tanımlayıcı User-Agent'i ve kaynak/izin/lisans kapanışını uygular. OpenStreetMap standart karo sunucusu ön indirmeye kapalıdır; TomTom verisi kalıcılaştırılmaz. Hazırlanmış rota çevrimdışı yeniden yönlendirme sağlamaz. S4'te mesafe tahmini sürüş sapma süresi gibi sunulmaz; stok, açık olma veya fiyat uydurulmaz. S5 ham veri analizi, yol yarıçapı ve doğrulanmamış yatış/derece/doğruluk üretmez. Eksik/eski veriler sıfır kabul edilmez; mevcut kayıt ve ağır işlem kapıları korunur.

Aşağıdaki doğrulama özeti 0.12.0 öncesi aday anlık görüntüsünü kaydeder. U1 kapsamı tam UI/UX kabulü değildir; TTS/duyulabilirlik, runtime, native/cihaz, fiziksel ve canlı sağlayıcı doğrulaması yapılmamış/HOLD durumundadır. Kaynak/belge yayını aday bazlı güvenlik ve koordinatör kabulüne tabidir; dağıtım ve cihaz doğrulaması ayrı izlenir. Bu kanıtlar yeni sürümün yayınlandığı veya gündelik cihaza kurulduğu anlamına gelmez. Ayrıntılı tarihsel kapsam [değişiklik geçmişindedir](../CHANGELOG.md#telefon-0120-öncesi-tarihsel-aday-kayıtları); güncel sürüm bilgisi yukarıdaki bölümde yer alır.

Bu dosya telefon ve uyumlu bileşenlerin sürüm kapsamını kaydeder. Dağıtım dosyaları ve doğrulama sonuçları için ilgili [GitHub sürüm notlarını](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.13.0) esas alın; davranış özeti [değişiklik geçmişindedir](../CHANGELOG.md).

## Telefon kaynak sürümü ve uyumluluk

| Bileşen | versionName | versionCode | Dağıtım ve doğrulama kaydı |
|---|---|---|---|
| Telefon | 0.13.0 | 29 | Özellik kapsamı yukarıdaki bölümde, doğrulama ve dağıtım sonuçları sürüm sayfasında |
| Wear OS | 0.7.0 | 12 | Telefon 0.13.0 ile değişmiyor |
| Ortak iletişim | v6 | — | Bu telefon kaynak sürümünde korunur |

## Tarihsel telefon 0.11.5 / code 27 — sesli yönlendirme ve interkom kontrolü

**Durum:** Sabit kaynak adayı [`c593bfb`](https://github.com/osmanatayozturk/iz-android/commit/c593bfb739f1cd82ad994013e733a33e6c653bb8), 0.11.5 / code 27'dir. 967 Debug birim testi geçti (0 hata/başarısızlık/atlama); Debug lint 0 hata/ölümcül hata ve 95 uyarı verdi; `:app:assembleDebug` tamamlandı. `org.iz.navigation` Debug APK'sı versionCode 27/versionName 0.11.5, minSdk 29/targetSdk 36 ve debuggable olarak okundu; imza doğrulandı. Bu, yerel Debug kanıtıdır; Release/AAB, gerçek cihaz veya yayın kabulü değildir. Wear OS **0.6.1 / code 11** ve iletişim **v6** korunur.

Debug kanıtı bağlantı verilen uygulama commit'ine aittir; belge güncellemesi ayrı bir commit'te izlenir.

- **Kullanıcı yönergeleri:** Ses kontrolünü araç dururken yap; interkom varsa telefonun seçili ses çıkışını ve medya sesini kontrol et; ekranı elle kilitleyip yeniden açtığında anonsun duyulup duyulmadığını gözlemle. Ses motorunun hazır olması veya Bluetooth bağlantısı duyulabilir anonsu doğrulamaz. Ses duyulmuyorsa var olan “Tekrar dene” veya “Seslendirme ayarları” kontrolleri önerilir.
- **Davranış ve uyumluluk:** Yalnız iki metin yönergesi eklendi; mevcut durum, callback, ses/oturum davranışı, veri biçimi, izinler ve bağımlılıklar değişmedi. Otomatik interkom algılama, duyulabilirlik veya ekran kilidi sürekliliği garantisi yoktur.
- **Doğrulama kapsamı:** `:app:testDebugUnitTest`, `:app:lintDebug` ve `:app:assembleDebug` işleri `-PizTestBuildType=batteryCheck` yapılandırma geçersiz kılmasıyla çalıştırıldı. Bu ayar yalnız test hedefi içindir. AndroidTest kaynak lint'i, `batteryCheck` lint'i ve enstrümantasyon doğrulanmadı; bu paket QA/native sonucu değildir. Gerçek cihaz interkom/ekran kilidi, görünüm/TalkBack ve Release/AAB doğrulanmadı. APK'nın kimliği ve Debug imzası salt okunur paket incelemesinde doğrulandı.

## Önceki aday — Telefon 0.11.4 / code 26 — harita üzerinde Gösterge paneli

**Durum:** 0.11.4 / code 26 kaynak adayı tamamlandı; G2 kaynak ve belgeleri GitHub main dalında. Yerelde imzalanmış Release APK ve AAB için paket kimliği/imza doğrulaması geçti; herkese açık APK veya uygulama Release'i yayımlanmadı. Son birim testinde 967 test başarısızlık/hata/atlama olmadan geçti; lint 0 hata/ölümcül hata ve 95 uyarı verdi. Adaya özgü odaklı güvenlik incelemesi olumludur; koordinatör G2 kaynak/belge main aktarımını ve IAS hedefini kabul etti. Google Play IAS yüklemesi bu kayıtta doğrulanmadı. Wear OS **0.6.1 / code 11** ve iletişim **v6** değişmiyor.

- **Yerleşim ve gezinme:** Tek mevcut harita ekranda kalır. **Gösterge** paneli dikeyde altta, yatayda yanda açılır. Geri paneli kapatır ve gerçek açan ekranı/aktif oturumu korur; panel açma veya kapama konum bulma, rota önizleme, kayıt ya da onaylı başka bir komutu yeniden çalıştırmaz.
- **Bilgi sırası:** Hız ve varsa koşullu hız sınırı ilk sıradadır. Yönlendirme manevrası ve kritik uyarılar önde; mevcut sesi kapatma ve ayrı kayıt/yönlendirme/tüm oturum işlemleri erişilebilir kalır. Yönlendirmede Nereye? başlık yüksekliği 0 olarak korunur.
- **Mevcut veriler ve korumalar:** Yalnız geçerli oturumun mevcut navigation, progress, roadSpeed, weather, group ve analysis snapshot'ları kullanılır. Yönlendirme/ilerleme yokken manevra, kalan rota veya ETA uydurulmaz; eski hız güncelmiş gibi sunulmaz. Tazelik, yanlış veri sahibi, hata ve gecikmiş durumlar açık kalır. Yeni üretici, harita, GPS/API isteği, zamanlayıcı, sorgu veya sensör başlatılmaz; yeni ETA/traffic hesabı, canlı trace/ağır istatistik veya ek motosiklet profili oluşturulmaz.
- **Harita ve uyumluluk:** Mevcut kamera, elle harita tutuşu/recenter, ölçülen görünür alan ve atıf korunur. Wear OS **0.6.1 / code 11**, iletişim **v6**, veri/yedek biçimi, izinler ve bağımlılıklar değişmez. Dikey ve yatay Ana ekran/panel bileşen kontrolleri geçti; bu kapsam tam uygulama gezinme akışını, TalkBack'i veya yapılandırma/süreç geri yüklemesini kapsamaz.
- **Devralınan davranış:** 0.11.3'teki F05 yedek içeriği açıklaması ile F09 yerel mekân taslağı ve OSM bağlantı teyitlerinin mevcut davranışı 0.11.4'te korunur; önceki uygulama ve kabul kayıtları yeni aday için kanıt sayılmaz.
- **Sınırlı telefon QA:** Yalnızca test uygulamasındaki Ana ekran/panel sahnelerinin dikey ve yatay kontrolleri geçti; atanmış UI/UX kaynak incelemesi ile iki normal yazı boyutlu görüntünün görsel kabulü tamamlandı. Geçici QA kurulumları kaldırıldı; gündelik uygulama kurulumu değiştirilmedi.
- **Kalan kabul kapıları:** Odaklı aday güvenlik incelemesi olumludur; koordinatör G2 kaynak/belge main aktarımını ve IAS hedefini kabul etti. Google Play IAS yüklemesi doğrulanmadı. Tam uygulama/cihaz ve dağıtım uyumluluğu ile tam geçişli kaynak/lisans kapsamı halen doğrulanmayı bekliyor; ayrıntılar aşağıdaki sürüm durumunda yer alır. Önceki sürümlerin kanıtları 0.11.4 kabuline aktarılmaz.

## Önceki aday — Telefon 0.11.3 / code 25 (tarihsel)

**Tarihsel durum:** Önceki belge kontrol noktasında 0.11.3 yayımlanmamıştı; uygulama, bağımsız test ve UI/UX kabulü tamamlanmamış, aday güvenlik incelemesi ve son yayın kabulü beklemedeydi. Bu adayın sonuçları yalnız kendi kaynağına aittir ve 0.11.4 kabulü değildir. Wear OS **0.6.1 / code 11** ve iletişim **v6** korunur.

**Sonraki tarihsel masaüstü teslim kontrolü:** 957 birim testi hata/başarısızlık/atlama olmadan geçti; Debug lint 0 hata/ölümcül hata ve 95 uyarı verdi; yerel Debug paketi üretildi ve UI/UX kaynak metin/akış incelemesi kabul edildi. Native/cihaz, dış güvenlik ve sürüm/yayın kabulü verilmedi. Bu güncellenmiş kayıt yalnız 0.11.3'e aittir.

0.11.3 test ve kabul durumu kendi adayına aittir; 0.11.4 için kanıt veya kabul değildir.

- **F05 — yedek içeriği açıklaması:** Yedekte yolculuklar, konum kayıtları, yerler, özel notlar ve fotoğraflar bulunur. Varsa deneysel yatış verileri, ham sensör kayıtları ve bunlarla ilişkili konum, zaman, hız ve kalibrasyon bilgisi de açıklanır. Sağlık ölçümleri varsayılan olarak eklenmez; bu tercih yatış/sensör verilerini etkilemez. Mevcut yedek içeriği, seçimi ve biçimi değişmez.
- **F09 — yerel mekân taslağı:** Silme öncesi teyit gösterilir; Vazgeç/Geri/dışarı dokunma işlemi iptal eder ve yerel veri değişmez. Onaylanan işlem yalnızca güncel, değişmemiş ve silinebilir yerel taslağı hedefler; OSM'deki mekânlar değişmez. Uygulama sabit kaynakta tamamlandı; bağımsız uygulama ve cihaz/native kabulü bu tarihsel teslimde tamamlanmadı.
- **F09 — OSM bağlantısını kesme:** Teyit yerel OSM oturumu ile temizlenecek topluluk kişileri/mesajları/taslaklarını açıklar; günlük, fotoğraf ve mekân taslaklarının korunduğunu, OSM hesabı ve OSM'deki içeriğin silinmediğini, başlamış gönderimlerin tamamlanabileceğini belirtir. İptal işlem yapmaz. Sunucu, kimlik doğrulama veya mevcut temizleme politikası değiştirilmez.
- **Kabul durumu:** Sabit kaynak teslimi ve UI/UX kaynak metin/akış incelemesi kabul edildi; 957 birim testi geçti. Cihaz/native kontrolü çalıştırılmadı; dış güvenlik incelemesi ve sürüm/yayın kabulü beklemede kaldı. Yeni Undo, sağlık seçeneği, yedek/veritabanı biçimi, saklama politikası veya backend davranışı eklenmez.

## Önceki aday — Telefon 0.11.2 / code 24 (yayımlanmamış, tarihsel kayıt)

Telefon 0.11.2, önceki 0.11.1 planındaki arayüz iyileştirmelerini aynı sürümdeki sesli yönlendirme ve ekran geçmişi değişiklikleriyle birleştirir; 0.11.1 ayrı bir telefon sürümü değildir. Bu bölüm kaynak davranışını ve host doğrulama zincirini özetler. Dağıtım durumu, dosyalar ve doğrulama kapsamı için ilgili [GitHub Releases/sürüm notları kaydını](https://github.com/osmanatayozturk/iz-android/releases) esas alın.

0.11.2 yayımlanmış değildir. Telefonda yapılan sınırlı Menü → Ayarlar kontrolü başarısız oldu; kök nedeni henüz belirlenmedi ve uygulama hatası olduğu doğrulanmadı. Tanılama test paketi üretildi ancak çalıştırılmadı. Native/UI/UX kabulü, bu adaya özgü güvenlik incelemesi ve koordinatörün son yayın kabulü beklemededir. Aşağıdaki APP-02/APP-03 sonuçları yalnızca kendi tarihsel kaynak/host doğrulama kapsamındadır; 0.11.3'e aktarılmaz.

- **Yönlendirme başlığı, bitirme teyidi ve karşıtlık:** Aktif dönüşlü yönlendirmede Nereye? başlığı ve boşluğu gizlenir; dönüş/mesafe, uyarılar, ses, oturum kartı, İşlemler ve harita/kamera durumu korunur. Günlükten Bitir, eşleşen yolculuğu ve etkilerini açıklayan teyidi açar; yönlendirme, kayıt ve tüm oturum seçenekleri ayrıdır, iptal değişiklik yapmaz. Normal boyuttaki ikincil metin/arka plan çiftleri en az 4,5:1 karşıtlık sağlar.
- **TTS ve ses durumu:** Tek telefon motoru sesli yönlendirme ve hava istemlerini yönetir. Motor/dil hazırlığı, istek başına ses odağı/çıktı hatası ve sessiz tercih ayrı durumlar olarak gösterilir. Teyitli yönlendirme başlangıcında motor/dil bir kez sessizce hazırlanır. Ses hatası görsel yönlendirmeyi durdurmaz; kısa uyarı manevra kartında, ayrıntılar mevcut ses kontrollerinde gösterilir. Motor/dil kullanılabilir durumdaysa ve dönüş anonsu sürmüyorsa elle ses testi denenebilir; anons sürüyorsa istek sıraya alınmadan meşgul yanıtı verir. Yeni dönüş anonsu elle testi kesebilir. Motorun hazır durumu duyulabilir, interkom veya çevrimdışı ses kabulü anlamına gelmez.
- **Geri gezinme ve geri yükleme:** Telefon sayfaları gerçek açan sayfa ve ebeveyn bağlamına döner. Liste/arama/filtre/sıralama/konum gibi hafif görüntüleme durumu güvenli, kararlı anahtarlarla korunur; hassas domain verisi, credential veya iş/izin sonucu saklanmaz. Geri, onCreate/onNewIntent veya süreç dönüşü başlatma/durdurma/yeniden planlama/gönderim komutunu tekrar oynatmaz. Saat ve Auto gezinmesi bu değişikliğin dışında.
- **Kirli düzenlemeden çıkış:** Yalnız gerçek ve kaydedilmemiş fark çıkış teyidi ister. Temiz filtre/sorgu/kaydırma ve değişmemiş sıralama kirli sayılmaz; değişmiş, açık kaydetme isteyen sıralamalar çıkış korumasına dahildir. Çocuk seçiciler ebeveyn girdisini atmaz. **Düzenlemeye dön** girdiyi korur; **Değişikliklerden vazgeç** yalnız kaydedilmemiş farkı atıp çıkışı bir kez sürdürür. Mevcut OSM yerel otomatik kaydı sürer; onaylanmış kayıt geri alınmaz, atılan metin yaşam döngüsü geri çağrısıyla yeniden oluşmaz. Back otomatik Save/Send yapmaz.
- **Uyumluluk:** Telefon hedefi 0.11.2/code24'tür. Yeni veritabanı/yedek biçimi veya genel taslak deposu planlanmıyor. Wear OS 0.6.1/code11 ve iletişim v6 değişmiyor.

### Kaynak ve host doğrulama özeti

APP-02 kaynak adayı 8b2160e06cc6fa5c5b3b141c50e070242e51afe7 üzerinde 934 birim testi geçti (0 başarısızlık, 0 hata, 0 atlama); lint 0 hata/ölümcül hata ve 95 uyarı verdi, Debug ve QA ürün APK’ları üretildi. APP-03 doğrulama kaynağı 4656b3492a8d2ff469e0043c0f28ad03c44ef7d6 yalnız AndroidTest kaynaklı test çağrısı düzeltmesini içerir. APP-02 ile APP-03 arasında ürün, derleme ve birim-test girdileri aynı kaldığından önceki birim/lint sonuçları yeniden çalıştırılmadan kullanıldı. AndroidTest APK’sı üretildi; üç APK’nın manifest/imza kontrolü geçti ve Debug/QA ürün APK’larının SHA-256 değerleri aynı kaldı. Bu host ve Debug/QA paket doğrulama kaydı Release APK’sı veya cihazda AndroidTest çalıştırması anlamına gelmez. Dağıtım durumu, dosyalar ve doğrulama kapsamı için ilgili [GitHub Releases/sürüm notları kaydını](https://github.com/osmanatayozturk/iz-android/releases) esas alın.

### 0.11.1 dönemi kanıtı — tarihsel, sonraki adayların kabulü değildir

Önceki `b3` kaynağının tam birim turunda 860 testten 856'sı geçti, 4'ü başarısız oldu. Sonraki `7ade5c9` kaynağında seçilen dört düzeltme kontrolü geçti; lint 0 hata/ölümcül hata ve 92 uyarı verdi. Bunlar yalnız o kaynak ve kapsam için tarihsel kanıttır; 0.11.2, 0.11.3 veya 0.11.4 kaynağında test/derleme sonucu değildir. 0.11.1 için native cihaz hedefi yoktu. README'deki ekran görüntüleri 0.9/0.10 döneminden tarihsel örneklerdir; 0.11.4 ekran görüntüsü değildir.

## Telefon 0.11.0 / code 22 — motosiklet sürüşü

APK, kaynak arşivi, bağımlılık kaynakları, lisanslar ve doğrulama notları [GitHub Release sayfasında](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.11.0) listelenir.

- **Tek Motosiklet türü:** Ayrı işe gidiş/şehir içi modu veya profili yoktur. Motosiklet için varsayılan rota en hızlı seçenek ve bilinen kaplamasız yollardan kaçınmadır; otoyol ve ücretli yol izinli kalır. Açıkça kaydedilen tercihler korunur.
- **Hedef kısayolları:** Kayıtlı yerlerden Ev ve İş hedefi açıkça atanabilir. Kısayol ulaşım türünü veya yolculuk kaydını değiştirmez; rota önizlemesi ve başlatma kullanıcı eylemleridir.
- **Sürüş kamerası:** Araba/motosiklette teyitli kayıt veya yönlendirme sırasında telefon ve Android Auto için ortak kamera bulunur. Hız ve yaklaşan manevra kadrajı etkiler; elle harita kullanımı ve telefon yönelimi korunur. Auto güvenli görünür alanı bilinmiyorsa düz görünüm kullanılır.
- **Deneysel yatış tahmini:** Özellik yalnız tamamlanmış motosiklet yolculuğu için, açık tercih, gövdeye sabit telefon, uygun kalibrasyon ve yeterli sensör/GPS örnekleriyle kullanılmak üzere uygulanmıştır. Gidon/bilinmeyen montaj veya yetersiz örnek sayısal sonuç vermez; canlı yatış göstergesi yoktur. Ağır analiz etkin/geçici kayıt ve navigasyon kalmayınca, pil en az %20 olduğunda çalışır; yeni kayıt veya düşük pilde bekler.
- **Veri ve sınırlar:** Eski yedek biçimleri için hedefli uyumluluk testleri kapsamdadır; eski yolculuklara tahmini yatış değeri üretilmez. Yatış sonucu deneysel etiketlenir. Fiziksel doğruluk, enerji tasarrufu veya sürüş güvenliği iddiası yoktur.

### Doğrulama ve kalan kabul

- Önceki debug QA bağlamında değişmeyen üretim kaynağına ait 142 hedefli birim testi geçti, 0 başarısız oldu ve 0 atlandı; lint 0 hata / 108 uyarı olarak korundu. Yalnız test kodu değiştiğinden bu kontroller o turda yeniden çalıştırılmadı; bunlar yeni Release APK'sı için R8 sonucu değildir.
- 0.11.0 herkese açık yapılandırma R8 Release derlemesi 304.443 saniyede geçti; 135 dış bağımlılık artefaktı envanterlendi. Denetim üç sağlayıcı alanının boş olduğunu doğruladı; bu, özel yapılandırma veya kişisel verilerin tam denetimi değildir.
- Release APK paket doğrulaması geçti: `org.iz.navigation`, 0.11.0 / code 22, min SDK 29 / target SDK 36, hata ayıklama kapalı, v3 imza şeması ve 16 KiB hizalama. Boyut 50.321.150 bayt; SHA-256 `2772781B6DCE98A0A6DBEE07DAD15A06C189C9DE87B936D2F64571CD407FDDB6`.
- Bu SHA-256 ile eşleşen APK için bağımsız statik paket kontrolü geçti: uygulama kimliği, imza geçmişi ve hizalama, 283 dosya bağı, 135 ikili bileşen, 20 izin, 32 bileşen, yedekleme/FileProvider yapılandırması, üç boş sağlayıcı alanı ve imzalı-imzasız paket içerik eşlemesi incelendi. Bu sonuç çalışma zamanı veya cihaz kabulü değildir.
- Sınırlı QA AndroidTest turunda R1'de 10 geçti, 1 test beklentisi başarısız oldu. R2'de düzeltilen kısayol testi geçti; telefon sahnesinin açılışı test gövdesine ulaşmadan başarısız oldu ve ekran görüntüsü alınmadı. R3'te telefon sahnesi testi geçti. R1'de geçen 10 kontrol R3'te yeniden çalıştırılmadı.
- R3'te normal yazı ölçeğinde iki gerçek GPU ekran görüntüsü alındı. UI/UX bu görüntülerle sınırlı kapsamı kabul etti; görsel engel bulunmadı. İkinci görüntüde elle tutulan genel bakışın bir bölümü hava durumu katmanı altında kaldı; bu manuel tutuşu gösterir, uyarlanabilir hedeflemeyi kanıtlamaz.
- Ev/İş kısayollarını içeren başlık yerleşimi çalışma zamanında kontrol edilmedi. Android Auto bileşeni/hostu, gerçek GNSS/sürüş, fiziksel yatış doğruluğu ve enerji etkisi, tam uygulama ve daha geniş erişilebilirlik kabul edilmedi.
- Wear OS **0.6.1 / code 11** ve iletişim **v6** değişmedi ve tekrar test edilmedi. Fiziksel yatış doğruluğu ve enerji etkisi ölçülmedi.
- Bu sınırlı doğrulama özeti tam telefon/Android Auto çalışma zamanı kabulünü, gerçek sürüşü, fiziksel yatış doğruluğunu veya enerji etkisini kanıtlamaz.

Wear OS **0.6.1 / code 11** ile telefon-saat iletişimi **v6** telefon 0.11.0 kaynaklarında değişmedi; yeni Wear APK'sı yoktur. [0.11.0 kullanım rehberi](IZ_010_KULLANIM.md).

## Önceki geliştirme adayı kaydı — 0.10.2 / code 21 ve Wear OS 0.6.1 / code 11

Bu, 27 Ekim 2026 tarihli sınırlı doğrulama kaydıdır. O tarihte telefon 0.10.2 / code 21, Wear OS 0.6.1 / code 11 ve iletişim v6 geliştirme adayıydı; yayımlanmış 0.10.1'i değiştirmiyor ve indirme için sunulmuyordu. Telefon `appDebug` derlemesi geçti; lint 0 hata ve 108 uyarı verdi. İlk odaklı QA koşusu 8 geçti, 1 tanılama kontrolü başarısız oldu ve 1 kontrol, pilin doğal olarak %20’nin altında olması koşulu sağlanmadığı için atlandı; dört gizlilik kontrolü bu koşuda geçti. Test-only düzeltmesinden sonra aynı QA ürünü üzerinde yalnız değişen tanılama kontrolü bir kez daha çalıştırıldı ve geçti; bu tam koşunun tekrarı değildir ve ilk başarısızlık kaydını değiştirmez. Dört gizlilik kontrolü bu tekrar koşusunda yeniden çalıştırılmadı. Sınırlı salt okunur postflight sırasında alınan tek anlık görüntü, türetilmiş indeks ve ısı haritasının boş ve yerleşmiş olduğunu gösterdi. Boş durum için günlük toplamlar sıfırdı; bekleyen işaret veya atomik `new`/`bak` yan dosyaları yoktu. Play uygulamasına dokunulmadı. Ham veritabanı veya tüm kişisel veriler denetlenmedi. Wear OS ve iletişim protokolü değişmedi; önceki Wear lint sonucu (0 hata, 41 uyarı) ve 38 protokol testi değişmeyen kaynak kanıtıyla tarihsel olarak yeniden kullanıldı, bu adayda tekrar çalıştırılmadı. Bu sonuçlar genel aday kabulü değildir; tam kişisel veri denetimi, gerçek sürüş, WorkManager dağıtımı/işlem devamı, fiziksel saat kabulü veya ölçülmüş pil tasarrufu kanıtı sağlamaz. Bu kayıt güvenlik kararı ya da yayın onayı değildir. Ayrıntı için [aday değişiklik kaydına](../CHANGELOG.md) bakın.

Önerilen davranışta sürüşün gerekli canlı işlevleri ve ham kayıtlar korunur. Ağır iz/ısı haritası/istatistik ve saat özetleri aktif kayıt/navigasyon bittikten ve telefon pili %20 veya üzerindeyken ertelenmiş kuyruktan işlenir. Yerel tanılama verisi yedek dışı tutulur, en fazla 7 gün/2 MiB saklanır ve hassas yol/sağlık içeriği içermez. Ayrıntı için [pil optimizasyonu notuna](PIL_OPTIMIZASYONU.md) bakın.

26 Ekim tarihli araştırma önerileri [ayrı yol haritası taslağında](MOTOSIKLET_YOL_HARITASI.md) yer alır. Onaylı 0.11.0 kapsamı yukarıdaki bölümde tanımlıdır; o taslaktaki diğer fikirler bu sürüme eklenmiş sayılmaz.

## 0.10.1 / code 20 — Yayımlandı

**Yayın tarihi:** 16 Eylül 2026. [Sürüm ve indirme dosyaları](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.10.1) · [Yayımlanan uygulama kaynakları](https://github.com/osmanatayozturk/iz-android/tree/v0.10.1).

### Tamamlanan kapsam

- Daha fazla ilgili yer bulmak için sınırlı devam sorgusu, sonuçlarda mevcut okunabilir adres ve eski arama durumunun temizlenmesi.
- Klavyenin üstündeki alana sığan arama penceresi, sabit başlık ve Kapat eylemi.
- Küçültülmüş navigasyon kartı, mevcut Nereye? paneline erişim ve yol tarifi/kayıt/tüm oturum için ayrı bitirme teyitleri.
- Aktif yol tarifinde İz ön plandayken ekranın açık tutulması; elle kilitlemenin korunması.
- Grup servisi istek gövdesinde 16 KiB ve mutlak 5 saniye sınırı.
- Telefon yayın imzası geçişi ve yalnız release paketinde kullanılmayan eski dinamik alıcı izninin kaldırılması.
- Kişisel yapılandırmasız APK, APK ile eşleşen kaynak arşivi, bağımlılık kaynakları, üçüncü taraf lisansları ve SHA-256 listesinin yayımlanması.

### Uyumluluk

Telefon **0.10.1 / code 20**, Wear OS **0.6.0 / code 10** ve iletişim **v5** kullanılır. Günlük ve yedek biçimleri değişmez. Android 10 ve üzeri hedeflenir; bu ifade bütün Android sürümlerinde yeni test yapıldığı anlamına gelmez. Bu sürümde saat güncellenmez. Kişisel sağlayıcı ayarlı APK herkese açık dosyalara eklenmez.

### Mevcut doğrulama ve sınırlamalar

Mevcut debug/release derleme ve birim testi sonuçları kullanıldı. Her iki lint sonucunda 0 hata / 90 uyarı vardır. Android 16/API 36 emülatöründe veri koruyan güncelleme, 19 fotoğraf/URI gizliliği senaryosu ve gerçek OSM girişinin korunması doğrulandı. Grup servisinin mevcut yerel ve yayımlanmış hizmet kanıtları korunur.

Telefon–saat kontrollerinin bir bölümü tamamlandı. Başlatma ve eski önbellek kontrollerinde başarısız sonuçlar, gerçek bağlantı kesilip yeniden kurulması kontrolünde tamamlanamayan adımlar bulunur; nedenleri tümüyle doğrulanmadı. Kalan testler durduruldu. Fiziksel sensörler ve gerçek araç kabulü tamamlanmış değildir. Normal Release olarak yayımlanması, bu sınırlamaları veya nihai güvenlik onayının alınmamış olmasını değiştirmez.

Bu belge güncellemesi yalnız yayın durumunu düzeltir; APK'yı, kaynak etiketini veya uygulama sürümünü değiştirmez. Önceki code 19 ve eski platform kanıtları kendi tarihsel kapsamlarında kalır.

## 2026-09-14: Yeni uygulama sürümleri için güvenlik kabul şartı

- **Amaç:** Her yeni İz uygulama sürümünün kabulünden önce Güvenlik projesindeki Siber Güvenlik görevinden, o sabit aday için açık **OLUMLU** güvenlik dönüşü alınması.
- **Akış:** Geliştirme, yerel entegrasyon, son paketler, teknik kontroller, bağımsız Test raporu ve UI/UX kabulü tamamlanır; yalnız koordinatör sabit nihai aday için güvenlik talebini gönderip yanıtı bekler. Ara aday bildirimi veya periyodik yoklama yapılmaz. Olumlu dönüşten sonra koordinatör sürümü kabul eder ve somut yayın/kurulum onayını verir. Sürüm Belgeleri ile Cihaz Kurulumu bu onayları kendi görevleri kapsamında uygular.
- **Kanıt:** İnceleme paketi kaynak erişim referansı, tam commit, başlangıç commit'i/fark/kapsam, commit dışı içerik varsa sabit kopya/özet, uygulama/platform sürümleri ve kurulum/yayın hedefini içerir. Her özel/herkese açık APK için dosya referansı, SHA-256, paket kimliği/sürümü, derleme türü, ilgili derleme/hizmet yapılandırması ve imzalayan sertifikanın parmak izi aynı adaya bağlanır. Güvenlik değişiklikleri, bağımlılık/kilit farkları, önceki bulgu kimlikleri, önceki/güncel durumları ve değişen düzeltme/kanıt referansları, test/ortam, maskelenmiş kanıt ve çalıştırılmayan kontroller eklenir. Aynı adayın karar/tarih/kapsam/bulgu/düzeltme/kanıt ve incelenmeyen veya kapsam dışında bırakılan alanları ile koordinatörün son kabulü yerel teslim kaydında tutulur; public belgeler özel değerleri içermez.
- **Bekleme ve yeniden değerlendirme:** Olumsuz, eksik, engelli veya yanıtsız talep beklemededir; zaman aşımı onay sayılmaz. Kaynak commit'i, APK veya güvenlik kararının dayandığı ilgili derleme/hizmet yapılandırması değişirse eski onay taşınmaz. Teknik liderin tahsis ettiği düzeltmelerden sonra koordinatör yeni aday için yeniden olumlu teyit alır. Bu yapılandırma şartı kullanıcının sıradan arayüz ayarlarını kapsamaz.
- **Yetki:** İşlevsel test, derleme ve teknik lider incelemesi güvenlik onayı yerine geçmez. Olumlu güvenlik sonucu da GitHub yazma veya kurulum onayı yerine geçmez; koordinatörün kabulü ve somut işlem onayı ayrıca gerekir. Bu şartlar sağlanmadan kullanıcı telefonu/saati güncellemesi ve yeni sürümün GitHub kaynak push, etiket, Release veya APK yayını ilerlemez.
- **Beklerken yapılabilecek işler:** Atanan teknik geliştirme, paket hazırlama ve tahsisli izole test ortamındaki kontroller sürebilir. Kullanıcının gündelik cihazına erken kurulum yapılmaz. Özel veriler paylaşılmaz; olumlu sonuç sıfır risk garantisi olarak sunulmaz.
- **Bu teslim:** Yalnız görev yönergeleri ve sürüm belgeleri değişir. Telefon **0.10.0 / code 18**, Wear OS **0.6.0 / code 10**, iletişim **v5** ve mevcut paketler korunur. Salt belge/politika değişikliği yeni uygulama sürümü değildir; önceki belge teslimi geriye dönük güvenlik incelemesi sayılmaz.
- **Belge kabulü:** Dosya kapsamı, bağlantı, biçim, çelişki ve özel bilgi kontrolleri yapılır. Bu teslim için uygulama güvenlik taraması, uygulama testi/derlemesi, cihaz işlemi, APK, etiket, Release veya PR başlatılmaz. Yeni belge commit'i için ayrı koordinatör yayın onayı beklenir; önceki commit'in onayı taşınmaz.

Ayrıntılı kabul ve görev sınırları [AGENTS.md](../AGENTS.md) içindedir. Eklenti serbestisi korunur; yeni kural otomasyon veya bağımsız sürekli tarama başlatmaz.

## 2026-09-13: Uzman agentlar, teknik liderlik ve onaylı GitHub teslimi

- **Amaç:** Ürün ve yayın kararlarını koordinatörde tutarken teknik iş dağıtımını ve yerel entegrasyonu Çekirdek ve Teknik Lider'e vermek.
- **Kapsam:** Çekirdek ve Teknik Lider, Servis Entegrasyoncusu, Sürüm Belgeleri, Derleme ve APK ile Cihaz Kurulumu sorumlulukları tanımlandı. Bileşen geliştiricileri kod ve yerel testleri, Test görevi birleşmiş adayın kabulünü ve izole test ortamlarını yürütür. UI/UX Tasarım ve Fikir rolleri korunur.
- **Yayın:** Sürüm Belgeleri, her teslim için koordinatörün hedef depo/dal/etiket, kaynak commit, yayın metni, dosyalar ve varsa APK SHA-256 değerlerini kapsayan somut onayından sonra GitHub işlemlerini yürütür. Bu istisna diğer dış iletişim veya Birikenler yetkisini devretmez.
- **Çalışma koşulları:** Tüm erişilebilir eklentiler görevin ihtiyacına göre kullanılabilir; öneriler üst sınır değildir. Yeni çalışma kopyaları ve çıktılar D: üzerinde tutulur; mevcut veri ve emülatörler korunur. Cihazı, paketi ve işlem sırasını koordinatörün onaylı kapsamı içinde yalnız teknik lider tahsis eder.
- **Uyumluluk:** Yalnız belgeler ve görev düzeni değişir. Telefon **0.10.0 / code 18**, Wear OS **0.6.0 / code 10**, iletişim **v5** ve veri/yedek biçimleri korunur. Yeni platform veya servis özelliği eklenmez.
- **Kabul:** Rol/yetki tutarlılığı, yalnız ilgili belge farkları, bağlantılar, biçim ve özel bilgi kontrolü yapılır. Uygulama testi/derlemesi, yeni APK, etiket/Release veya cihaz kurulumu bu teslimin kapsamında değildir. GitHub'a gönderim, hazırlanan belge commit'i için ayrıca koordinatör onayı gerektirir.

Ayrıntılı görev ve teslim kuralları [AGENTS.md](../AGENTS.md) içindedir. Önceki iletişim düzeninin teknik atama ve GitHub uygulayıcısı hükümlerinin yerini bu düzen alır.

## Tamamlanan plan: telefon 0.9.1 → 0.9.2

**Amaç:** Ana haritada İz'in adını, küçük logosunu ve kısa sözünü geri getirmek.

- 36 dp mevcut yeşil logo, İz adı ve “Küçük yollar, güzel anılar.” sözü, Nereye? kartının üstüne yerleştirildi.
- Marka alanı boşta görünür; rota planlayıcı veya yolculuk açıkken gizlenir. Ayrı bir bekleme ekranı eklenmedi.
- Ölçülen başlık yüksekliği harita kontrolleri ve konumun görünür alanına uygulandı. Kısa yatay ekranda düğmeler ve harita atfı için yerleşim düzeltildi.
- Büyük yazı, erişilebilirlik, konuma dönme, rota sığdırma, haritada gezinme ve yolculuk geçişleri doğrulandı.
- Telefon sürümü 0.9.2 / code 17 oldu. Saat sürümü, günlük veritabanı, yedek ve iletişim biçimi değiştirilmedi.

**Kabul sonucu:** 551 telefon birim testi ve 19 Android ekran/harita testi geçti; debug/release derlemeleri ve lint hata vermedi. Lint uyarıları sürüyor. Mevcut imzayla telefona güncelleme ve yeni ana ekran doğrulandı. 0.9.1'in ince yol izleri bu pakete dahildir.

## 2026-09-13: APK ve uygulama görsellerinin yayını

- **Amaç:** Telefon 0.9.2 / code 17 için GitHub Releases üzerinden indirilebilir paket ve README'de gerçek uygulama görselleri sunmak.
- **Paket:** Mevcut kaynak kodundan, kişisel servis yapılandırmaları ve kullanıcı verileri eklenmeden oluşturulur. Uygulama kimliği, günlük/yedek biçimi ve saat iletişimi değişmez; saat sürümü 0.6.0 / code 10 kalır.
- **Görseller:** Emülatörde örnek konumlarla alınır; gerçek yolculuk, sağlık, hesap ve cihaz verileri yayımlanmaz. Harita kaynak gösterimi korunur.
- **Kabul:** Paket sürümü, imza, hata ayıklama durumu ve içeriği incelenir; emülatörde açılışı doğrulanır. Yayın varlıkları ve görseller GitHub'dan kontrol edilir. İndirme, kurulum ve yapılandırma açıklamaları güncellenir.
- **Sürüm etkisi:** Bu teslim mevcut 0.9.2'nin dağıtımıdır; yeni uygulama özelliği veya sürüm numarası üretmez.

**Yerel doğrulama:** 551 release birim testi geçti; release derlemesi başarılı, lint 0 hata / 90 uyarı ile tamamlandı. Minify edilmiş release APK, mevcut geliştirme sertifikasıyla imzalandı; hata ayıklama kapalıdır. Kişisel sağlayıcı değerleri ve veri dosyaları pakette bulunmadı. Emülatörde yerinde kurulum, ana harita, konuma dönme ve örnek Valhalla yürüyüş rotası (2,6 km / 34 dk) doğrulandı; APK kurulumundan sonraki çökme kaydı boş kaldı. İki görselde örnek konumlar ve görünür OpenStreetMap atfı kullanıldı. [GitHub sürümü ve indirme dosyaları](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.9.2).

## 2026-09-13: Proje iletişiminin koordinatörde toplanması

> Tarihçe: Bu bölüm önceki düzeni kaydeder. Güncel teknik atama ve onaylı GitHub uygulayıcısı kuralları yukarıdaki uzman görevleri bölümünde ve AGENTS.md içinde tanımlanmıştır.

- **Amaç:** Dış agent ve projelerden gelen istekleri tek sorumluda toplamak; yanıt ve iş dağıtımını İz koordinatörünün yürütmesi.
- **Kapsam:** Görev talimatları, iş devri ve dış yayın yetkisi netleştirilir. Telefon, saat ve Android Auto görevleri dış istekleri koordinatöre aktarır; kendi başlarına yanıt veya yayın yapmaz.
- **Uyumluluk:** Yalnız çalışma düzeni ve belgeler değişir. Telefon 0.9.2 / code 17, saat 0.6.0 / code 10, veri/yedek ve iletişim biçimi korunur.
- **Kabul:** Kalıcı talimatlardaki dış iletişim istisnaları kaldırılır; üç göreve bildirim teslimi, yalnız ilgili belge farkları ve GitHub'daki commit/plan doğrulanır. Uygulama kodu değişmediğinden derleme veya yeni APK gerekmez.

**Yerel doğrulama:** İki kalıcı talimat dosyası birlikte incelendi; dış iletişim çelişkileri kaldırıldı, mevcut görev kimlikleri ve sorumlulukları korundu. Telefon, saat ve Android Auto görevlerine bildirim teslim edildi. Belge farkları ve biçim kontrolü başarılı; uygulama testi/derlemesi çalıştırılmadı.

## Sürüm numaralandırma ilkesi

Aşağıdaki paragraf eski 0.11.4 kaydındaki numaralandırma durumunu korur. Güncel özellik kapsamı bu belgenin başında yer alır.

Tarihsel telefon kaynak hedefi **0.11.4 / code 26**'dır. G2 kaynak ve belgeleri GitHub main dalına normal fast-forward ile gönderilmiştir; bu, uygulama Release'i değildir ve herkese açık APK yayımlanmamıştır. Adaya özgü odaklı güvenlik incelemesi olumludur; koordinatör aynı G2 kaynak ve belgelerinin main'e aktarımını ve IAS paylaşım hedefini kabul etmiştir. Google Play Internal App Sharing yüklemesinin durumu bu kayıtta doğrulanmamıştır. Tam uygulama çalışma zamanı ve cihaz kabulü, TalkBack, süreç/yapılandırma geri yükleme, Android Auto/Wear, Google tarafı IAS yeniden imzalama/sertifika eşlemesi, gündelik kurulum güncelleme uyumluluğu ve tam geçişli kaynak/lisans kapsamı doğrulanmayı beklemektedir. Önceki 0.11.3 ve 0.11.2 adayları kendi tarihsel kapsamlarında korunur; 0.11.1 ayrı telefon sürümü değildir. Yayımlanmış uygulama sürümlerinin dosya ve dağıtım durumu [GitHub Releases](https://github.com/osmanatayozturk/iz-android/releases) bölümündedir. Wear OS **0.6.1 / code 11** ve iletişim **v6** modüller bazında ayrıca numaralandırılır; bu telefon adayı bunları değiştirmez.

- Sürüm numarası gerçek kapsam ve son yayımlanmış sürüme göre belirlenir. Yeni uygulama yayını için ilgili modülün `versionCode` değeri artar; ardışık test derlemeleri ayrı sürüm sayılmaz.
- Telefon ve saat bağımsız numaralandırılır. Değişmeyen modülün sürümü artırılmaz. Değerlerin kaynağı `app/build.gradle.kts` ve `wear/build.gradle.kts` dosyalarıdır.
- Veri, yedek, uygulama kimliği veya iletişimde uyumsuz değişiklik gerekirse başlamadan önce geçiş planı eklenir. 1.0.0 için ayrıca kararlı sürüm kapsamı belirlenir.

## Her güncellemede uygulanacak akış

1. Koordinatör ürün kapsamını, önceliği ve hedef sürümü belirler. Sürüm Belgeleri bu plana kullanıcıya yansıyan değişiklikleri, veri/saat uyumluluğunu ve kabul kontrollerini yazar.
2. Teknik lider ortak dosyaları tahsis eder, uzmanlara teknik iş atar, incelemeyi ve yerel entegrasyonu yürütür. Kod içindeki sürüm alanlarını yalnız teknik lider veya açıkça tahsis ettiği görev değiştirir.
3. AGENTS.md içindeki kısa test akışı uygulanır: geliştirmede hedefli kontroller, sabit kaynakta birleştirilmiş zorunlu kontroller ve bağımsız Test tarafından tek bütünleşik tur. Derleme ve APK ile Test, belgelenmiş uyumluluk koşullarını sağlayan aynı doğrulanmış ürün/test APK çiftini kullanır; gereken kişisel ve herkese açık paketler ayrı hazırlanır. Sürüm/imza/SHA-256 ve kaynak/lisans eşleşmesi doğrulanır; kısa aday raporu ve kanıt manifesti ortak referans olur.
4. Sürüm Belgeleri planı, değişiklik geçmişini ve gerekli kullanım/README notlarını doğrulanmış sonuçlarla günceller. Yalnız ilgili dosyaları commit'e alır; özel ayarları, anahtarları, cihaz kanıtlarını ve kişisel paketleri yayımlanacak içeriğe eklemez. Yukarıdaki inceleme paketi alanları ve teknik test kanıtları sabit kaynak/paket adayıyla eşleştirilir.
5. Yeni uygulama sürümünde geliştirme, entegrasyon, son paketler ve teknik/UI/UX işleri tamamlandıktan sonra koordinatör sabit nihai adayı Güvenlik projesindeki Siber Güvenlik görevine sunar ve açık OLUMLU dönüşü bekler. Ara aday bildirimi ve periyodik yoklama yapılmaz. Olumsuz, eksik, engelli veya yanıtsız karar beklemededir; zaman aşımı istisnası yoktur. Kaynak, paket veya güvenlik kararının dayandığı ilgili derleme/hizmet yapılandırması değişirse yeniden olumlu teyit gerekir. Teknik test, paket hazırlama veya teknik lider incelemesi bu kararın yerine geçmez.
6. Her teslimde hedef depo/dal, varsa etiket/Release, kaynak commit, tam yayın metni, dosya listesi ve varsa her APK'nın SHA-256 değeri koordinatöre sunulur. Olmayan etiket, Release ve APK açıkça belirtilir. Yeni uygulama sürümünde koordinatör eşleşen olumlu güvenlik dönüşünden sonra sürümü kabul eder ve somut yayın/kurulum onayını verir; iki onay birbirinin yerine geçmez. Karar/tarih/kapsam/kanıt referansı ve son kabul yerel teslim kaydında tutulur. Değişen commit, metin, hedef veya dosyalar yeniden onaylanır. Uygulama kodu, incelenen adayın ilgili derleme/hizmet yapılandırması, sürümü ve paketi değişmeyen salt belge tesliminde uygulama güvenlik taraması, uygulama testi/derlemesi veya APK üretimi gerekmez; yine de o belge commit'i için ayrı somut yayın onayı alınır. Rutin işlem için kullanıcıdan tekrar onay istenmez.
7. Onaylı GitHub işlemlerini yalnız Sürüm Belgeleri mevcut CLI/OAuth oturumuyla yürütür. Kullanıcı farklı bir akış istemedikçe önerilen hedef `osmanatayozturk/iz-android` deposunun `main` dalıdır. Uzak dal ilerlerse teknik liderle uzlaştırılır ve değişen aday yeniden onaylanır; yeni uygulama sürümünün kaynağı, paketi veya incelenen ilgili derleme/hizmet yapılandırması değişirse güvenlik teyidi de yenilenir. Force push yapılmaz. Herkese açık APKlar yalnız onaylı Release varlığına yüklenir; APK/AAB dosyaları kaynak deposuna eklenmez.
8. Uzak commit, belgeler ve yayın metni doğrulanır. Varsa etiket/Release'in kaynak commit'i, indirilebilir dosyalar ve APK SHA-256 değerleri kontrol edilir. Cihaz Kurulumu görevinin kurulum sonuçları ile Test'in işlevsel kabul sonuçları ayrıca raporlanır; her kontrolün gerçek durumu geçti, kaldı, engelli veya çalıştırılmadı olarak belirtilir. Kullanıcı telefonu/saati güncellemesi yeni sürümün güvenlik sonucu ve koordinatör kabul/kurulum onayı tamamlanmadan başlatılmaz.

Bu akış [AGENTS.md](../AGENTS.md) içinde de kayıtlıdır. Diğer proje dışı iletişim ve Birikenler teslimi koordinatörde kalır. Zamanlanmış bir görev veya otomasyon kurulmaz.

## Tamamlanan plan: İz 0.10.0 / code 18

Telefon için Rotalarım, rota tercihleri ve alternatifler, GPX çizgi takibi, gezi koleksiyonları ile fotoğraf ve GPX paylaşım gizliliği tamamlandı. Çevrimdışı harita ve yeni bir çevrimdışı rota motoru bu sürüme eklenmedi. Yerel veritabanı (Room) ve manuel yedek sürümü 7 oldu; eski 1-6 verileri okunur. Wear OS 0.6.0 / code 10 ve iletişim biçimi v5 korunur. [Sürüm notları, doğrulama sonuçları ve dosyalar](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.10.0).
