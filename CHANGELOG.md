# Değişiklik geçmişi

## Geliştirme adayı — telefon 0.11.0 / code 22

27 Ekim 2026 tarihli telefon 0.11.0 / code 22 geliştirme adayı kaynakta uygulanıp derlendi. Aşağıdaki özellikler yayımlanmış uygulamada kullanılabilir kabul edilmemelidir. Yayımlanmış telefon sürümü 0.10.1 / code 20'dir. Wear OS 0.6.1 / code 11 ve telefon-saat iletişimi v6 değişmedi; bu adayda yeniden test edilmedi.

- Tek **Motosiklet** ulaşım türü korunur; ayrı işe gidiş modu veya profili yoktur. Aday kaynakta motosiklet için varsayılan rota tercihleri ve kullanıcının kayıtlı yerlerinden açıkça atanan Ev/İş hedef kısayolları bulunur; otoyol ve ücretli yol tercihleri izinli kalır, kayıtlı seçimler korunur.
- Araba/motosiklette teyitli kayıt veya yönlendirme sırasında telefon ve Android Auto için ortak sürüş kamerası bulunur. Elle kaydırma/yakınlaştırma, mevcut harita kontrolleri ve küçük/bilinmeyen Auto görünür alanında düz görünüm desteği kaynakta yer alır.
- İsteğe bağlı yatış tahmini yalnız tamamlanmış motosiklet yolculuğunda ve gövdeye sabit telefonla kullanılmak üzere geliştirilmiştir. Gidon/bilinmeyen montaj, eksik kalibrasyon veya yetersiz örnekler sayısal sonuç sağlamaz; canlı yatış göstergesi ve tehlikeli manevra istemi yoktur.
- Yatış sonucu deneysel olarak sunulur. Fiziksel ölçümle doğrulanmış doğruluk, güven yüzdesi veya enerji tasarrufu iddiası yoktur.

### Sınırlı doğrulama ve kalan kabul

- Değişmeyen ürün kaynağına ait hedefli birim testi kanıtı 142 geçti, 0 başarısız oldu ve 0 atlandı; yalnız test kodunun değiştiği bu çalışma için yeniden çalıştırılmadı. Telefon lint sonucu 0 hata / 108 uyarı olarak korundu.
- Telefon debug uygulaması, QA ürün paketi ve QA instrumentation test paketi üretildi. Bu debug/QA paketleri 0.11 APK Release veya gündelik cihaza kurulumu kanıtlamaz.
- Sınırlı QA AndroidTest turunda R1'de 10 geçti, 1 test beklentisi başarısız oldu. R2'de düzeltilen kısayol testi geçti; telefon sahnesinin açılışı test gövdesine ulaşmadan başarısız oldu ve ekran görüntüsü alınmadı. R3'te telefon sahnesi testi geçti. R1'de geçen 10 kontrol R3'te yeniden çalıştırılmadı.
- R3'te normal yazı ölçeğinde iki gerçek GPU ekran görüntüsü alındı. UI/UX bu görüntülerle sınırlı kapsamı kabul etti; görsel engel bulunmadı. İkinci görüntüde elle tutulan genel bakışın bir bölümü hava durumu katmanı altında kaldı; bu manuel tutuşu gösterir, uyarlanabilir hedeflemeyi kanıtlamaz.
- Ev/İş kısayollarını içeren başlık yerleşimi çalışma zamanında kontrol edilmedi. Android Auto bileşeni/hostu, gerçek GNSS/sürüş, fiziksel yatış doğruluğu ve enerji etkisi, tam uygulama ve daha geniş erişilebilirlik kabul edilmedi.
- Wear OS 0.6.1 / code 11 ve iletişim v6 değişmedi ve yeniden test edilmedi. Gerçek sürüş yapılmadı; fiziksel yatış doğruluğu ve enerji etkisi ölçülmedi.
- Bu kaynak ve bu doğrulama özeti 0.11 APK Release, gündelik cihaza kurulum veya tam çalışma zamanı kabulünü kanıtlamaz.

Kullanıcıya dönük aday notu: [kullanım kılavuzundaki 0.11 bölümü](docs/IZ_010_KULLANIM.md). Gerçek cihaz ve yayın durumu doğrulandıkça bu kayıt yeniden güncellenecektir.

## Önceki geliştirme adayı kaydı — telefon 0.10.2 / code 21, Wear OS 0.6.1 / code 11, iletişim v6

Bu, 27 Ekim 2026 tarihli yayın öncesi doğrulama kaydıdır ve telefon 0.10.2 / code 21, Wear OS 0.6.1 / code 11, iletişim v6 geliştirme adayının kapsamını ve o tarihe kadarki kanıtları özetler. İndirilebilir paket veya tamamlanmış sürüm duyurusu değildir. Aday için yalnız sınırlı kontroller raporlanmıştır; tam kabul anlamına gelmez.

- Sürüş sırasında yönlendirme, ses, periyodik rota/trafik yenilemesi, hava durumu, grup takibi, hız ve ekranın açık kalması korunur. Ham GPS ve saat sensör kayıtları devam eder.
- Ağır iz/ısı haritası/ayrıntı ve saat özetleri aktif kayıt ya da navigasyon sırasında hesaplanmak yerine, ikisi de sona erince ve pil en az %20 iken kuyruğa alınır. Şarj şartı yoktur; Android başlatma zamanını geciktirebilir.
- Görünmeyen yerel pil tanılaması için yedek dışı alanda 7 gün ve toplam 2 MiB sınırı hedeflenir. Koordinat, adres, mesaj ve sağlık ölçüm değerleri bu kayda alınmaz; ADB çıktısı salt okunur tasarlanmıştır.
- Mevcut günlük ve yedek verisini koruma hedefi sürer. Pil kazancı ölçülmüş değildir; bu kayıt bir test, cihaz kabulü, güvenlik onayı veya yayın beyanı değildir.
- Tam ertelenmiş-istatistik davranışı eşleşen Wear OS 0.6.1 / iletişim v6 gerektirir. Eski saat yolu yalnız gerçek eski eş saptanınca kullanılır; eski eşin yetenek bilgisi 60 saniye önbelleklenir, v6 düğümleri hariç tutulur. Bekleyen eski günlük/sağlık özetleri gösterilmez; ham kayıt ve gönderim kuyruğu korunur. Ölçülmemiş değerler sıfır gibi sunulmaz; fiziksel saat kabulü yapılmış değildir.

### Sınırlı doğrulama ve kalan kapsam

- Telefon `appDebug` derlemesi geçti; telefon lint 0 hata ve 108 uyarı verdi. İlk odaklı QA koşusu 8 geçti, 1 tanılama kontrolü başarısız oldu ve 1 kontrol, pilin doğal olarak %20’nin altında olması koşulu sağlanmadığı için atlandı. Dört gizlilik kontrolü ilk koşuda geçti.
- Test-only düzeltmesinden sonra aynı QA ürünü üzerinde yalnız değişen tanılama kontrolü bir kez çalıştırıldı ve geçti. Bu dar tekrar tam koşuyu baştan yürütmedi; ilk 8/1/1 sonucu ve başarısızlık kaydı tarihsel olarak korunuyor. Dört gizlilik kontrolü bu tekrar koşusunda yeniden çalıştırılmadı; önceki geçiş kanıtları aynı ürün kimliği altında ayrı kalır.
- Sınırlı salt okunur postflight sırasında alınan tek anlık görüntü, türetilmiş indeks ve ısı haritasının boş ve yerleşmiş olduğunu gösterdi. Boş durum için günlük toplamlar sıfırdı; bekleyen işaret veya atomik `new`/`bak` yan dosyaları yoktu. Play uygulamasına dokunulmadı. Ham veritabanı veya tüm kişisel veriler denetlenmedi; bu sonuç tüm kuyruk işlerinin tamamlandığı anlamına gelmez.
- Wear OS ve iletişim protokolü bu adayda değişmedi. Önceki Wear lint sonucu (0 hata, 41 uyarı) ve 38 protokol testi değişmeyen kaynak kanıtıyla tarihsel olarak yeniden kullanıldı; yeni adayda tekrar çalıştırılmadı.
- Bu sonuçlar genel sürüm kabulü değildir; gerçek sürüş regresyonu, WorkManager dağıtımı/işlem devamı, fiziksel saat kabulü veya ölçülmüş pil tasarrufu kanıtı sağlamaz. Bu kayıt güvenlik kararı ya da yayın onayı değildir.

İlgili çalışma notları: [pil ve tanılama](docs/PIL_OPTIMIZASYONU.md), [motosiklet yol haritası önerileri](docs/MOTOSIKLET_YOL_HARITASI.md).

## 0.10.1 / code 20 — 2026-09-16

Telefon sürümü [GitHub'da yayımlandı](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.10.1). Kaynak kodu `v0.10.1` etiketinde, APK, kaynak arşivi, bağımlılık kaynakları, lisanslar ve SHA-256 listesi aynı yayında bulunur. Sürüm normal Release / Latest olarak sunulur.

- Yer aramada ilk sayfa 1–9 geçerli sonuç ve en az bir OSM referansı içeriyorsa en fazla bir devam isteği yapılır. İlk sayfa boşsa, tam 10 sonuç içeriyorsa veya referans yoksa devam edilmez. Birleşik listede en fazla 20 sonuç işlenir; bütün yerlerin bulunması garanti edilmez.
- Arama sayfaları ayrı önbellek anahtarları kullanır. İkinci sayfa başarısızsa ilk sonuçlar korunur; sorgu değişince önceki sonuç ve hata yeni sorguya taşınmaz.
- Yer adının altında mevcut adres metni gösterilir. Eksik mahalle/sokak bilgisi tahmin edilmez; koordinat adres yerine yazılmaz.
- OSM arama penceresi klavyenin üstündeki alana sığar. Başlık ve **Kapat** sabit kalırken sonuç listesi kaydırılır.
- Sol üst navigasyon kartı küçültüldü. Manevra, ses, **İşlemler** ve gerekli uyarılar korunur; toplam mesafe ve süre mevcut Yol tarifi panelindedir.
- Kart mevcut **Nereye?** panelini planlama durumunu koruyarak açar. Yol tarifini, yolculuk kaydını veya tüm oturumu bitirme eylemleri ayrı teyitlerle çalışır.
- Aktif yol tarifinde İz ön plandayken ekran açık kalır. Elle kilitleme korunur; arka planda veya yol tarifi bitince normal ekran zaman aşımı geçerli olur.
- Grup servisi, kimlik doğrulama ve kota kontrolünden sonra istek gövdesini en fazla 16 KiB ve mutlak 5 saniye sınırıyla okur.
- Telefon paketi yeni yayın anahtarına ve imza geçmişine taşındı. Release paketinde kullanılmayan eski dinamik alıcı izni kaldırıldı; debug davranışı korunur.
- Herkese açık APK kişisel servis yapılandırması içermez. Kişisel paket yayımlanmaz.
- Wear OS **0.6.0 / code 10**, iletişim **v5**, günlük ve yedek veri biçimleri değişmedi. Bu yayında yeni saat APK'sı yoktur.

Mevcut derleme, birim testi ve lint sonuçları kullanıldı. Android 16/API 36 emülatöründe veri koruyan güncelleme, 19 fotoğraf/URI gizliliği senaryosu ve gerçek OSM girişinin güncelleme sonrası korunması doğrulandı. Telefon–saat kontrollerindeki başlatma ve eski önbellek başarısızlıkları ile tamamlanamayan bağlantı kontrolü sürüm notlarında açıklanır. Kalan testler durduruldu; fiziksel donanım ve gerçek araç kabulü tamamlanmış değildir. Nihai güvenlik onayı veya tüm testlerin geçtiği iddia edilmez. Önceki code 19 sonuçları tarihsel kapsamlarında kalır.

## Yeni uygulama sürümlerinde güvenlik kabulü — 2026-09-14

- Her yeni İz uygulama sürümünün kabulü, kullanıcı telefonu/saati güncellemesi ve GitHub kaynak/etiket/Release/APK yayını öncesinde, Güvenlik projesindeki Siber Güvenlik görevinden o aday için açık olumlu dönüş şartı eklendi. Talebi ve sonucu yalnız koordinatör yönetir.
- Güvenlik kararı sabit kaynak commit'i, başlangıç/kapsam, uygulama/platform sürümleri, varsa her özel/herkese açık APK'nın SHA-256 değeri ve ilgili derleme/hizmet yapılandırmasıyla eşleştirilir. Kaynak, paket veya güvenlik kararının dayandığı ilgili derleme/hizmet yapılandırması değişirse yeniden olumlu teyit gerekir.
- Olumsuz, eksik, engelli veya yanıtsız karar beklemede kalır; zaman aşımı onay sayılmaz. İşlevsel test, derleme ve teknik lider incelemesi güvenlik onayının; olumlu güvenlik sonucu da koordinatörün sürüm kabulü ve somut yayın/kurulum onayının yerine geçmez.
- Yanıt beklenirken atanan geliştirme, paket hazırlama ve tahsisli izole testler sürebilir. Bulgular teknik liderin tahsisiyle düzeltilir ve yeni aday koordinatör üzerinden yeniden değerlendirilir. Karar ve kabul kanıtı yerelde tutulur; özel veriler paylaşılmaz ve sıfır risk garantisi verilmez.
- Bu değişiklik yalnız belgelerdir; telefon **0.10.0 / code 18**, Wear OS **0.6.0 / code 10**, iletişim **v5** ve mevcut paketler korunur. Yeni uygulama sürümü, geriye dönük güvenlik incelemesi veya sürekli tarama başlatmaz.

## Uzman görevleri ve teslim süreci — 2026-09-13

- Koordinatör ürün kapsamı, öncelik, kullanıcı/dış iletişim ve son yayın onayını yönetir. Çekirdek ve Teknik Lider onaylı kapsamda doğrudan teknik iş atar, ortak dosyaları tahsis eder ve yerel entegrasyonu yürütür.
- Servis Entegrasyoncusu, Sürüm Belgeleri, Derleme ve APK ile Cihaz Kurulumu görevleri tanımlandı. Bileşen geliştiricilerinin kod/yerel test, Test'in birleşmiş kabul ve izole test ortamı sorumlulukları; UI/UX Tasarım ve Fikir rolleri korundu.
- Sürüm Belgeleri her teslim için koordinatörün somut hedef, commit, metin, dosya ve varsa APK SHA-256 onayından sonra GitHub push, etiket, Release ve onaylı herkese açık APK yüklemesini yürütür. Diğer dış iletişim ve Birikenler koordinatörde kalır.
- Eklenti önerilerinin üst sınır olmadığı, yeni çıktıların D: üzerinde tutulması ve mevcut veri/emülatörlerin korunması belgelendi.
- Bu değişiklik yalnız görev yönergeleri ve sürüm belgeleridir. Telefon **0.10.0 / code 18**, Wear OS **0.6.0 / code 10**, iletişim **v5** ve veri/yedek biçimleri değişmedi; yeni APK üretilmedi.

## Telefon 0.10.0 — 2026-09-13

**versionCode: 18 · Uyumlu saat: 0.6.0 / code 10 · İletişim: v5**

- Rotalarım: adlandırılmış planlar ve ayrı GPX kütüphanesi, yeniden açma, ad değiştirme ve silme.
- Yürüyüş, koşu ve bisiklette varsayılan otoyoldan kaçınma; ulaşım türüne göre kalıcı tercihler, doğrulanan alternatif güzergâhlar ve seçilmiş rotaya bağlı hava karşılaştırması.
- Rota başlangıcı, trafik yenileme ve yeniden hesaplamada aynı yol tercihleri ve kişisel hızın korunması.
- GPX izlerinde bağımsız bölüm/yön/başlangıç seçimi ve çizgi takibi; kesilen konumda ilerleme dondurma, belirsiz döngüde açık seçim, uygulama kapanmasında otomatik başlamayan takip.
- Geziler: tamamlanmış, kalıcı yolculukların sıralı koleksiyonları. Koleksiyon silme günlük kaydını etkilemez.
- Room ve manuel yedek sürümü 7; eski 1–6 yedeklerinin okunması. Kayıtlı planlar, GPX izleri ve koleksiyonların yedeklenmesi.
- GPX dışa aktarımında isteğe bağlı tarih/saat; paylaşılan fotoğraflarda üstveri temizliği ve özgün fotoğrafların korunması.

**Önizleme dağıtımı:** [Telefon APK'sı](https://github.com/osmanatayozturk/iz-android/releases/download/v0.10.0/iz-0.10.0-android.apk) kişisel servis anahtarları ve kullanıcı verileri olmadan sunulur. TomTom trafik özellikleri ve OSM hesabıyla yapılan işlemler kullanıcının kendi sağlayıcı ayarını gerektirir; grup özelliği genel APK'da bir Supabase sunucusuna bağlı değildir. [Sürüm notları, doğrulama sonuçları ve dosyalar](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.10.0).

Telefon ve Wear OS sürümleri ayrı numaralandırılır. Plan ve teslim kuralları için [sürüm planına](docs/SURUM_PLANI.md) bakın. Buradaki tarihler değişikliklerin tamamlandığı tarihlerdir.

## Çalışma düzeni - 2026-09-13

> Önceki düzenin kaydıdır. Teknik atama ve GitHub işlemlerinin güncel görev paylaşımı yukarıdaki uzman görevleri bölümündedir.

- Dış agent/proje isteklerinin koordinatöre yönlendirilmesi; yanıt, iş dağıtımı, editör iletişimi ve GitHub yayınının tek sorumluda kalması kalıcı talimatlara eklendi.
- Telefon, saat ve Android Auto görevlerine kural iletildi. Uygulama kodu, paketler ve sürüm numaraları değişmedi.

## Telefon 0.9.2 - 2026-09-13

**GitHub önizleme dağıtımı:** Telefon APK'sı kişisel servis ayarları olmadan release olarak hazırlandı. README'ye kurulum bağlantısı, yapılandırma bilgisi ve örnek konumlarla alınmış iki gerçek ekran görüntüsü eklendi. İlgili lisanslar, bağımlılık kaynakları ve dosya özetleri sürüm dosyalarıyla sunulur. Bu dağıtım yeni uygulama özelliği veya sürüm numarası eklemez.

**versionCode: 17 · Uyumlu saat: 0.6.0 / code 10**

- Ana haritanın üstüne küçük yeşil logo, serif **İz** adı ve **“Küçük yollar, güzel anılar.”** sözü geri eklendi.
- Başlık, rota planlama ve aktif yolculuk sırasında gizlenir. Büyük yazı ayarında slogan satır kırabilir; ekran okuyucu marka bilgisini bir kez okur.
- Başlık yüksekliği harita düğmeleri ve görünür alan hesabına katılır. Kısa yatay ekranlarda düğme çakışmaları ve hava düğmesinin harita atfını kapatması giderildi.
- Mevcut konum, takip ve yeniden ortalama haritanın açıkta kalan bölümünü kullanır. Rota sığdırma ve kullanıcının haritada gezinmesi korunur.
- Günlük, yedek biçimi ve saat iletişimi değişmedi. 0.9.1 yenilikleri bu sürüme dahildir.

**Doğrulama:** 551 telefon birim testi, 19 Android ekran/gerçek harita testi; debug ve release derlemeleri, lint hata vermeden tamamlandı. Lint uyarıları devam ediyor. Aynı imzayla telefonda yerinde güncelleme ve yeni ana ekran doğrulandı.

Kaynak: [43b0f15](https://github.com/osmanatayozturk/iz-android/commit/43b0f15).

## Telefon 0.9.1 — 2026-09-13

**versionCode: 16 · Uyumlu saat: 0.6.0 / code 10**

- Isı haritasındaki geniş renk lekeleri, kaydedilen yollardan oluşan ince izlerle değiştirildi.
- Uzak/orta/yakın görünümde çizgiler 1/2/3 piksel; bulanıklık yok. Sabit ölçek: 1 yolculuk mavi, 2–3 turkuaz, 4–7 sarı, 8+ kırmızı.
- Yaklaşık 30 metrelik bölgelerden geçen farklı yolculuklar sayılır. Aynı yolculukta bekleme, dönüş ve tekrar noktaları sayıyı artırmaz; GPS kesintileri birleştirilmez.
- Ulaşım filtreleri, normal/tam ekran açıklaması ve veri yenilenirken kullanıcının harita konumu korunur. Hız renkleri, kayıt ve yedek biçimi değişmedi.

**Doğrulama:** 645 birim testi (telefon 551, saat 54, protokol 40), 15 Android ekran/harita testi; derleme ve lint hata vermeden tamamlandı. Telefona ayrı kurulmak yerine 0.9.2 güncellemesiyle birlikte ulaştı.

Kaynak: [28e8ade](https://github.com/osmanatayozturk/iz-android/commit/28e8ade).

## Telefon 0.9.0 — 2026-09-12

**versionCode: 15 · Uyumlu saat: 0.6.0 / code 10**

- Yolculuk havasına TomTom trafik hesabı ve Matrix v2 ile kullanıcı onaylı durak sırası önerisi eklendi.
- Araba ve motosiklette GPS hızı ile mevcut yolun desteklenen hız sınırı ayrı gösterilir; veri belirsizse sınır uydurulmaz.
- Kaydedilen yollar, yolculuğun ölçülmüş hız aralığına göre renklendirilir.
- Biriktirilen yerlerin sırası düzenlenebilir; rota seçim listesi aynı sırayı kullanır.
- Yer sıralaması için günlük ve ZIP yedek sürümü 6 oldu; eski 1–5 yedeklerinin okunması korundu.

[Ayrıntılar ve sınırlar](docs/IZ_090_YENILIKLER.md) · Kaynak: [4de11c8](https://github.com/osmanatayozturk/iz-android/commit/4de11c8).

## Telefon 0.8.2 ve saat 0.6.0 — 2026-09-12

**Telefon code 14 · Saat code 10**

- Wear OS için İz kartı ve kadran bilgi alanı eklendi; içerik navigasyon, kayıt ve günlük özet durumuna göre değişir.
- Telefon navigasyonu kayıtsız çalışırken de saate aktarılır; ortak protokol v5, eski v1–v4 iletişimini korur.
- Samsung Health günlük adımları ile İz kayıtları ayrı kaynaklar olarak sunulur; kart açmak sağlık sensörlerini başlatmaz.

[Saat kartı ve kadran ayrıntıları](docs/WATCH_SURFACES.md) · Kaynak: [194be42](https://github.com/osmanatayozturk/iz-android/commit/194be42).

Önceki değişiklikler [Git geçmişinde](https://github.com/osmanatayozturk/iz-android/commits/main/) bulunur.
