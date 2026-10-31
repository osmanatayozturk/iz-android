# Değişiklik geçmişi

## Telefon 0.11.4 / code 26 — harita üzerinde Gösterge paneli (yayımlanmamış geliştirme adayı)

**Durum:** Telefon 0.11.4 / code 26 kaynak adayı tamamlandı; herkese açık APK veya Release yayımlanmadı. Yerel Debug derlemesi ve paket kimlik/imza okuması geçti. Son birim testinde 967 test başarısızlık, hata veya atlama olmadan geçti; lint 0 hata/ölümcül hata ve 95 uyarı verdi. Wear OS **0.6.1 / code 11** ve iletişim **v6** değişmiyor.

**Sınırlı telefon sonucu:** Yalnızca test uygulamasındaki Ana ekran/panel bileşen sahnelerinin dikey ve yatay kontrollerinin her biri geçti; atanmış UI/UX kaynak incelemesi ile bu iki normal yazı boyutlu görüntünün görsel kabulü tamamlandı. Geçici QA kurulumları kaldırıldı; gündelik uygulama kurulumu değiştirilmedi. Bu sonuç tam uygulama gezinme akışını, TalkBack'i, yapılandırma/süreç geri yüklemesini veya genel çalışma zamanı kaynak tüketimini doğrulamaz. Bu kapılar çalıştırılmadı; aday bazlı dış güvenlik incelemesi ve koordinatörün son yayın kabulü beklemede.

- Tek mevcut harita ve gezinme/oturum sahipliği korunarak aynı ekranda **Gösterge** adlı panel açılır. Panel dikey ekranda altta, yatay ekranda yanda açılır; Geri yalnız paneli kapatıp gerçek açan ekranı/oturumu korur.
- Bilgi sırası hız ve varsa koşullu hız sınırıyla başlar; yönlendirme manevrası ile kritik uyarılar önde kalır. Kayıt, yönlendirme ve tüm oturum işlemleri birbirinden ayrı; ses kapatma erişilebilir tutulur.
- Aday yalnız mevcut oturum/sürüş, yönlendirme ilerlemesi, hız, hava, grup ve analiz durumlarını kullanmayı hedefler. Yönlendirme/ilerleme yokken manevra, kalan rota veya ETA üretilmez; eski hız güncelmiş gibi gösterilmez. Yeni harita, veri üreticisi, GPS/API isteği, zamanlayıcı, sensör, ETA/traffic hesabı veya ek motosiklet profili eklenmez. Eski/eksik/veri sahibi uyuşmazlığı durumlarının açık gösterilmesi hedeflenir.
- Manuel harita tutuşu, kamera/viewport, harita atfı ve mevcut Back/sonuç sahiplikleri korunur. Panel açma/kapama kayıt, konum bulma, rota önizleme veya onaylı bir komutu yeniden çalıştırmaz. Wear OS, iletişim protokolü, şema, izin ve bağımlılıklar bu adayda değişmeyecektir.
- 0.11.4, 0.11.3'teki F05 yedek içeriği açıklaması ve F09 yerel mekân/OSM işlem teyitlerinin mevcut davranışını devralır. Bu geçmiş uygulama kaydı, 0.11.4 için yeni doğrulama veya kabul değildir.

## Önceki aday — Telefon 0.11.3 / code 25 (yayımlanmamış, tarihsel)

**Tarihsel durum:** Önceki belge kontrol noktasında bu aday yayımlanmamıştı; uygulama, bağımsız test ve UI/UX kabulü tamamlanmamış, adaya özgü güvenlik incelemesi ve son yayın kabulü beklemedeydi. Bu tarihsel kanıtlar ve kararlar yalnız 0.11.3'e aittir, 0.11.4 kabulü değildir. Wear OS **0.6.1 / code 11** ve telefon-saat iletişimi **v6** değişmiyor.

**Tarihsel masaüstü teslim kontrolü:** Sonraki sabit kaynak tesliminde 957 birim testi geçti, hata/başarısızlık/atlama olmadı; Debug lint 0 hata/ölümcül hata ve 95 uyarı verdi; yerel Debug paketi üretildi. UI/UX kaynak metin/akış incelemesi kabul edildi. Native/cihaz, dış güvenlik ve sürüm/yayın kabulü verilmedi. Bu teslim kontrolü daha önceki belgesel durum özetini günceller; yalnız 0.11.3'e aittir.

0.11.3'e ait kaynak/test kanıtları ve kabul durumu yalnız o adayın tarihsel kaydıdır; 0.11.4 için kabul değildir ve otomatik taşınmaz.

- **F05 — yedek içeriği açıklaması:** Yedek içeriği açıklaması, onaylı yolculuklar, konum kayıtları, yerler, özel notlar ve fotoğrafların yanı sıra varsa deneysel yatış verileri, ham sensör kayıtları ve bunlarla ilişkili konum, zaman, hız ve kalibrasyon bilgisini belirtir. Sağlık ölçümleri varsayılan olarak dışarıda kalır; bu seçim yatış/sensör verilerini etkilemez. Mevcut yedek içeriği ve biçimi değişmez.
- **F09 — mekân taslağı ve OSM bağlantısı:** Mekân taslağı silinmeden önce açık teyit gösterilir; iptal yerel veriyi değiştirmez ve OSM'deki mekânları etkilemez. OSM bağlantısını kesme teyidi, temizlenecek yerel OSM oturumu ve topluluk önbelleği/taslaklarını, korunacak günlük/fotoğraf/mekân taslaklarını ve OSM hesabı ya da sunucu içeriğinin silinmediğini açıklar; başlamış gönderimler tamamlanabilir. İptal bağlantı veya veriyi değiştirmez. Mevcut yetkilendirme, veri saklama ve sunucu davranışları değişmez.
- **Sınırlar:** Yeni geri alma/Undo, yedek ya da veritabanı biçimi, ek sağlık seçeneği, temizleme/saklama politikası, backend veya uzak silme davranışı eklenmez. Uygulama ve kabul sonuçları doğrulanmadan tamamlanmış özellik gibi sunulmaz.

## Önceki aday — Telefon 0.11.2 / code 24 (yayımlanmamış, kabulü bekliyor)

Telefon 0.11.2, yönlendirme başlığı, günlük bitirme teyidi ve metin karşıtlığı iyileştirmelerini tek sürümde birleştirir; 0.11.1 ayrı bir telefon sürümü değildir. Bu kayıt sürüm davranışını ve doğrulanmış kaynak/host kapsamını açıklar. Wear OS **0.6.1 / code 11** ve telefon-saat iletişimi **v6** değişmez.

**Tarihsel durum:** 0.11.2 / code 24 yayımlanmamıştır. Telefonda yapılan sınırlı Menü → Ayarlar kontrolü başarısız oldu; kök nedeni henüz belirlenmedi ve uygulama hatası olduğu doğrulanmadı. Tanılama test paketi üretildi ancak çalıştırılmadı. Native/UI/UX kabulü, bu adaya özgü güvenlik incelemesi ve koordinatörün son yayın kabulü beklemededir. APP-02/APP-03 test, lint ve paket sonuçları yalnız 0.11.2'nin kendi kaynaklarına aittir; 0.11.3 kabulü değildir. Native01 bulgusunun çözüldüğü veya kabul şartından feragat edildiği doğrulanmış değildir.

### Birleşik 0.11.1 kapsamı

- **Yönlendirme başlığı:** Dönüşlü aktif yönlendirmede Nereye? başlığı ve boşluğu gizlenir; dönüş/mesafe, ses, uyarılar, oturum kartı, İşlemler ve harita/kamera durumu korunur. Yönlendirme bitince başlık geri gelir; yalnız kayıt, rota önizleme ve dönüşsüz GPX takibi değişmez.
- **Günlükten bitirme teyidi (F01):** Mevcut eşleşen yolculuk ve etkileri açıklanır. Yönlendirme, kayıt ve tüm oturum için bitirme seçenekleri ayrıdır; iptal değişiklik yapmaz, eski teyit başka oturumu bitiremez.
- **İkincil metin karşıtlığı (F02):** Normal boyuttaki ikincil metin/arka plan çiftleri en az 4,5:1 olur; değişiklik ilgili renk tokenı ve yüzeylerle sınırlı kalır.

### 0.11.2 kapsamı

- **Sesli yönlendirme durumu:** Tek telefon TTS motoru sesli yönlendirme ve hava durumu istemlerini yönetir. Motor/dil hazırlığı, tekil ses odağı/çıktı hatası ve sessiz tercih ayrı gösterilir. Teyitli yönlendirme başlangıcında, yönlendirme için bir kez sessiz hazırlık yapılır; ses kullanılamazsa görsel yönlendirme sürer. Geçerli hata manevra kartında kısa, GPS ve rota-yenileme uyarılarını değiştirmeden gösterilir; kontrollerde ayrıntı bulunur. Yönlendirme sırasında dönüş anonsu sürmüyorsa elle ses testi çalışabilir; anons sürüyorsa test sıraya alınmadan meşgul bilgisi verilir. Yeni dönüş anonsu elle testi kesebilir. Hazır olma durumu sesin duyulduğunu kanıtlamaz.
- **Telefon geri gezinmesi ve bağlam:** Sayfalar ve menüler gerçek açan ekranına döner. Liste/ayrıntı, arama, tarih/harita seçicisi, GPX, hava, grup, topluluk/OSM, ayarlar, yedekleme ve paylaşım akışlarının hafif görüntüleme durumu korunur. Geri veya ekranı geri yükleme rota başlatma, durdurma, yeniden planlama, gönderim ya da izin sonucunu tekrar oynatmaz. Saat ve Auto kendi gezinme akışlarını korur.
- **Düzenleme koruması:** Yalnız gerçekten kaydedilmemiş değişiklikler için **Düzenlemeye dön** ve **Değişikliklerden vazgeç** seçenekleri sunulur. Sorgu, filtre, kaydırma veya değişmemiş sıralama tek başına kirli sayılmaz; değişmiş, açık kaydetme isteyen sıralamalar korunma/teyit kapsamındadır. Çocuk seçici üst formu bırakmaz. Vazgeçme sadece bekleyen değişikliği atar ve çıkışı bir kez sürdürür. Mevcut OSM yerel otomatik kaydı devam eder; onaylanmış kayıt geri alınmaz ve atılan metin yeniden diriltilmez. Geri eylemi otomatik kaydetme/gönderme yapmaz.
- **Veri ve uyumluluk:** Yeni günlük/yedek biçimi veya küresel taslak deposu planlanmıyor. Telefon **0.11.2 / code 24**; Wear OS **0.6.1 / code 11** ve iletişim **v6** aynı kalacak.

### Kaynak ve doğrulama kaydı

APP-02 kaynak adayı 8b2160e06cc6fa5c5b3b141c50e070242e51afe7 üzerinde 934 birim testi geçti (0 başarısızlık, 0 hata, 0 atlama); lint 0 hata/ölümcül hata ve 95 uyarı verdi, Debug ve QA ürün APK’ları üretildi. APP-03 doğrulama kaynağı 4656b3492a8d2ff469e0043c0f28ad03c44ef7d6 AndroidTest kaynaklı tek planlayıcı çağrısı düzeltmesini içerir. APP-02 ile APP-03 arasında ürün, derleme ve birim-test girdileri aynı kaldığından birim/lint sonuçları tekrar çalıştırılmadan kullanıldı. AndroidTest APK’sı üretildi; üç APK’nın manifest/imza kontrolleri geçti ve Debug/QA ürün APK’larının SHA-256 değerleri değişmedi. Bu sonuçlar yerel Debug/QA kaynak doğrulama zincirini kaydeder; Release APK’sı veya cihazda AndroidTest çalıştırması anlamına gelmez. Dağıtım durumu, dosyalar ve doğrulama kapsamı için ilgili [GitHub Releases/sürüm notları kaydını](https://github.com/osmanatayozturk/iz-android/releases) esas alın. 0.11.1’deki b3 (860 testten 856 geçti, 4 kaldı) ve 7ade5c9 üzerindeki dört düzeltme kontrolü ile lint (0 hata/ölümcül hata, 92 uyarı) tarihsel kanıttır. README görselleri 0.9/0.10 döneminden tarihsel örneklerdir.

## Telefon 0.11.0 / code 22 — motosiklet sürüşü

Bu sürüm motosiklet yolculuklarında rota hedeflerini, sürüş kamerasını ve yolculuk tamamlandıktan sonra açılabilen deneysel yatış özetini bir araya getirir. İndirme dosyaları ve sürüme ait doğrulama notları [GitHub Release sayfasında](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.11.0) yer alır. Wear OS 0.6.1 / code 11 ve telefon-saat iletişimi v6 değişmedi; bu sürümde yeni Wear APK'sı yoktur.

- Tek **Motosiklet** ulaşım türü korunur; ayrı işe gidiş modu veya profili yoktur. Motosiklet için varsayılan rota tercihleri ve kullanıcının kayıtlı yerlerinden açıkça atanan Ev/İş hedef kısayolları bulunur; otoyol ve ücretli yol tercihleri izinli kalır, kayıtlı seçimler korunur.
- Araba/motosiklette teyitli kayıt veya yönlendirme sırasında telefon ve Android Auto için ortak sürüş kamerası bulunur. Elle kaydırma/yakınlaştırma, mevcut harita kontrolleri ve küçük/bilinmeyen Auto görünür alanında düz görünüm desteği kaynakta yer alır.
- İsteğe bağlı yatış tahmini yalnız tamamlanmış motosiklet yolculuğunda ve gövdeye sabit telefonla kullanılmak üzere geliştirilmiştir. Gidon/bilinmeyen montaj, eksik kalibrasyon veya yetersiz örnekler sayısal sonuç sağlamaz; canlı yatış göstergesi ve tehlikeli manevra istemi yoktur.
- Yatış sonucu deneysel olarak sunulur. Fiziksel ölçümle doğrulanmış doğruluk, güven yüzdesi veya enerji tasarrufu iddiası yoktur.

### Sınırlı doğrulama ve kalan kabul

- Önceki debug QA bağlamında değişmeyen üretim kaynağına ait hedefli birim testleri 142 geçti, 0 başarısız oldu ve 0 atlandı; lint 0 hata / 108 uyarı olarak korundu. Yalnız test kodu değiştiğinden bu kontroller o turda yeniden çalıştırılmadı; bunlar Release APK'sı için R8 sonucu değildir.
- Yeni 0.11.0 herkese açık yapılandırma R8 Release derlemesi 304.443 saniyede geçti ve 135 dış bağımlılık artefaktı envanterlendi. Denetim üç sağlayıcı alanının boş olduğunu doğruladı; bu, özel yapılandırma veya kişisel verilerin tam denetimi değildir.
- Release APK'sı için paket doğrulaması geçti: `org.iz.navigation`, 0.11.0 / code 22, min SDK 29 / target SDK 36, hata ayıklama kapalı, v3 imza şeması ve 16 KiB hizalama. Boyut 50.321.150 bayt; SHA-256 `2772781B6DCE98A0A6DBEE07DAD15A06C189C9DE87B936D2F64571CD407FDDB6`.
- Bu SHA-256 ile eşleşen APK için bağımsız statik paket kontrolü geçti: uygulama kimliği, imza geçmişi ve hizalama, 283 dosya bağı, 135 ikili bileşen, 20 izin, 32 bileşen, yedekleme/FileProvider yapılandırması, üç boş sağlayıcı alanı ve imzalı-imzasız paket içerik eşlemesi incelendi. Bu sonuç çalışma zamanı veya cihaz kabulü değildir.
- Sınırlı QA AndroidTest turunda R1'de 10 geçti, 1 test beklentisi başarısız oldu. R2'de düzeltilen kısayol testi geçti; telefon sahnesinin açılışı test gövdesine ulaşmadan başarısız oldu ve ekran görüntüsü alınmadı. R3'te telefon sahnesi testi geçti. R1'de geçen 10 kontrol R3'te yeniden çalıştırılmadı.
- R3'te normal yazı ölçeğinde iki gerçek GPU ekran görüntüsü alındı. UI/UX bu görüntülerle sınırlı kapsamı kabul etti; görsel engel bulunmadı. İkinci görüntüde elle tutulan genel bakışın bir bölümü hava durumu katmanı altında kaldı; bu manuel tutuşu gösterir, uyarlanabilir hedeflemeyi kanıtlamaz.
- Ev/İş kısayollarını içeren başlık yerleşimi çalışma zamanında kontrol edilmedi. Android Auto bileşeni/hostu, gerçek GNSS/sürüş, fiziksel yatış doğruluğu ve enerji etkisi, tam uygulama ve daha geniş erişilebilirlik kabul edilmedi.
- Wear OS 0.6.1 / code 11 ve iletişim v6 değişmedi ve yeniden test edilmedi. Gerçek sürüş yapılmadı; fiziksel yatış doğruluğu ve enerji etkisi ölçülmedi.
- Bu sınırlı doğrulama özeti tam telefon/Android Auto çalışma zamanı kabulünü, gerçek sürüşü, fiziksel yatış doğruluğunu veya enerji etkisini kanıtlamaz.

[0.11.0 kullanım rehberi](docs/IZ_010_KULLANIM.md). Bu sınırlı doğrulama, gerçek cihaz veya tam çalışma zamanı kabulü değildir.

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
