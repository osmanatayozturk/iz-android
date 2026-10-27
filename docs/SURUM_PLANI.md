# İz sürüm değişikliği planı

Güncel yayın durumu bu dosyada, tamamlanan değişiklikler [değişiklik geçmişinde](../CHANGELOG.md) bulunur.

## Güncel sürümler

| Bileşen | versionName | versionCode | Durum |
|---|---|---|---|
| Telefon | 0.10.1 | 20 | GitHub'da normal Release / Latest olarak yayımlandı |
| Wear OS | 0.6.0 | 10 | Değişmedi; bu yayında yeni saat paketi yok |
| Ortak iletişim | v5 | — | Eski v1–v4 desteği korunuyor |

## Geliştirme adayı — telefon 0.11.0 / code 22

**Durum:** 27 Ekim 2026'da telefon 0.11.0 / code 22 aday kaynağı derlendi ve hedefli birim testleri geçti. Bu sınırlı kaynak/derleme doğrulaması tam aday kabulü, cihaz kabulü, güvenlik onayı veya yayın beyanı değildir. Yayımlanmış telefon sürümü **0.10.1 / code 20** olarak kalır.

- **Tek Motosiklet türü:** Aday kaynakta ayrı işe gidiş/şehir içi modu veya profili yoktur. Motosiklet için varsayılan rota en hızlı seçenek ve bilinen kaplamasız yollardan kaçınmadır; otoyol ve ücretli yol izinli kalır. Açıkça kaydedilen tercihler korunur.
- **Hedef kısayolları:** Kayıtlı yerlerden Ev ve İş hedefi açıkça atanabilir. Kısayol ulaşım türünü veya yolculuk kaydını değiştirmez; rota önizlemesi ve başlatma kullanıcı eylemleridir.
- **Sürüş kamerası:** Araba/motosiklette teyitli kayıt veya yönlendirme sırasında telefon ve Android Auto için ortak kamera bulunur. Hız ve yaklaşan manevra kadrajı etkiler; elle harita kullanımı ve telefon yönelimi korunur. Auto güvenli görünür alanı bilinmiyorsa düz görünüm kullanılır.
- **Deneysel yatış tahmini:** Özellik yalnız tamamlanmış motosiklet yolculuğu için, açık tercih, gövdeye sabit telefon, uygun kalibrasyon ve yeterli sensör/GPS örnekleriyle kullanılmak üzere uygulanmıştır. Gidon/bilinmeyen montaj veya yetersiz örnek sayısal sonuç vermez; canlı yatış göstergesi yoktur. Ağır analiz etkin/geçici kayıt ve navigasyon kalmayınca, pil en az %20 olduğunda çalışır; yeni kayıt veya düşük pilde bekler.
- **Veri ve sınırlar:** Eski yedek biçimleri için hedefli uyumluluk testleri kapsamdadır; eski yolculuklara tahmini yatış değeri üretilmez. Yatış sonucu deneysel etiketlenir. Fiziksel doğruluk, enerji tasarrufu veya sürüş güvenliği iddiası yoktur.

### Doğrulama ve kalan kabul

- Son hedefli birim testi koşusu 142 geçti, 0 başarısız oldu ve 0 atlandı. Önceki seçili koşuda görülen iki eski beklenti uyuşmazlığından sonra yalnız test beklentileri düzeltilip son koşu çalıştırıldı.
- Telefon debug uygulaması, QA ürün paketi ve QA Android test paketi üretildi. Telefon lint 0 hata / 108 uyarı verdi. Üretilen debug/QA paketleri Release değildir; Release paketi veya dağıtım varlığı oluşturulmadı.
- UI/UX incelemesinde işaretlenen beş düzeltme kaynak üzerinden doğrulandı; bu, görsel/çalışma zamanı kabulü değildir. Android instrumentation, telefon UI ve Android Auto host çalışma zamanı USB hedefi olmadığı için çalıştırılmadı.
- Wear OS **0.6.1 / code 11** ve iletişim **v6** değişmedi ve tekrar test edilmedi. Fiziksel yatış doğruluğu ve enerji etkisi ölçülmedi.
- Aynı adayın güvenlik incelemesi ve koordinatör kabulü beklemede. Cihaza kurulum veya GitHub yayını yapılmadı.

Wear OS **0.6.1 / code 11** ile telefon-saat iletişimi **v6** bu adayda değişmedi. [Kullanıcıya dönük 0.11 aday bölümü](IZ_010_KULLANIM.md). Yeni cihaz veya yayın kanıtı geldiğinde bu kayıt gerçek sonuçlarla güncellenecektir.

## Önceki geliştirme adayı kaydı — 0.10.2 / code 21 ve Wear OS 0.6.1 / code 11

Bu, 27 Ekim 2026 tarihli sınırlı doğrulama kaydıdır. O tarihte telefon 0.10.2 / code 21, Wear OS 0.6.1 / code 11 ve iletişim v6 geliştirme adayıydı; yayımlanmış 0.10.1'i değiştirmiyor ve indirme için sunulmuyordu. Telefon `appDebug` derlemesi geçti; lint 0 hata ve 108 uyarı verdi. İlk odaklı QA koşusu 8 geçti, 1 tanılama kontrolü başarısız oldu ve 1 kontrol, pilin doğal olarak %20’nin altında olması koşulu sağlanmadığı için atlandı; dört gizlilik kontrolü bu koşuda geçti. Test-only düzeltmesinden sonra aynı QA ürünü üzerinde yalnız değişen tanılama kontrolü bir kez daha çalıştırıldı ve geçti; bu tam koşunun tekrarı değildir ve ilk başarısızlık kaydını değiştirmez. Dört gizlilik kontrolü bu tekrar koşusunda yeniden çalıştırılmadı. Sınırlı salt okunur postflight sırasında alınan tek anlık görüntü, türetilmiş indeks ve ısı haritasının boş ve yerleşmiş olduğunu gösterdi. Boş durum için günlük toplamlar sıfırdı; bekleyen işaret veya atomik `new`/`bak` yan dosyaları yoktu. Play uygulamasına dokunulmadı. Ham veritabanı veya tüm kişisel veriler denetlenmedi. Wear OS ve iletişim protokolü değişmedi; önceki Wear lint sonucu (0 hata, 41 uyarı) ve 38 protokol testi değişmeyen kaynak kanıtıyla tarihsel olarak yeniden kullanıldı, bu adayda tekrar çalıştırılmadı. Bu sonuçlar genel aday kabulü değildir; tam kişisel veri denetimi, gerçek sürüş, WorkManager dağıtımı/işlem devamı, fiziksel saat kabulü veya ölçülmüş pil tasarrufu kanıtı sağlamaz. Bu kayıt güvenlik kararı ya da yayın onayı değildir. Ayrıntı için [aday değişiklik kaydına](../CHANGELOG.md) bakın.

Önerilen davranışta sürüşün gerekli canlı işlevleri ve ham kayıtlar korunur. Ağır iz/ısı haritası/istatistik ve saat özetleri aktif kayıt/navigasyon bittikten ve telefon pili %20 veya üzerindeyken ertelenmiş kuyruktan işlenir. Yerel tanılama verisi yedek dışı tutulur, en fazla 7 gün/2 MiB saklanır ve hassas yol/sağlık içeriği içermez. Ayrıntı için [pil optimizasyonu notuna](PIL_OPTIMIZASYONU.md) bakın.

26 Ekim tarihli araştırma önerileri [ayrı yol haritası taslağında](MOTOSIKLET_YOL_HARITASI.md) yer alır. Güncel onaylı aday kapsamı yukarıdaki 0.11.0 bölümünde tanımlıdır; o taslaktaki diğer fikirler bu sürüme eklenmiş sayılmaz.

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

Yayımlanmış telefon sürümü **0.10.1 / code 20**, Wear OS **0.6.0 / code 10**'dur. Etkin telefon adayı **0.11.0 / code 22**; bu adayda değişmeyen Wear sürümü **0.6.1 / code 11** olarak belirlenmiştir. Güncel yayın ve aday hedefi için bu dosyanın üst bölümündeki tablolar geçerlidir; eski sürüm örnekleri yeni hedef yerine kullanılmamalıdır.

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
