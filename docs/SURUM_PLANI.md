# İz sürüm değişikliği planı

Bu dosyayı Sürüm Belgeleri her İz güncellemesinde günceller; GitHub teslimi aşağıdaki koordinatör onayı akışıyla yapılır. Tamamlanan değişiklikler ayrıca [değişiklik geçmişine](../CHANGELOG.md) yazılır.

## Güncel sürümler

| Bileşen | versionName | versionCode | Durum |
|---|---|---|---|
| Telefon | 0.10.0 | 18 | GitHub önizleme sürümü |
| Wear OS | 0.6.0 | 10 | Telefon 0.10.0 ile iletişim biçimi değişmedi |
| Ortak iletişim | v5 | — | Eski v1–v4 desteği korunuyor |

## 2026-09-14: Yeni uygulama sürümleri için güvenlik kabul şartı

- **Amaç:** Her yeni İz uygulama sürümünün kabulünden önce Güvenlik projesindeki Siber Güvenlik görevinden, o sabit aday için açık **OLUMLU** güvenlik dönüşü alınması.
- **Akış:** Kaynak/paket ve teknik test kanıtları hazırlanır; yalnız koordinatör güvenlik talebini gönderip yanıtı bekler. Olumlu dönüşten sonra koordinatör sürümü kabul eder ve somut yayın/kurulum onayını verir. Sürüm Belgeleri ile Cihaz Kurulumu bu onayları kendi görevleri kapsamında uygular.
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

## Sonraki uygulama güncellemesinin numarası

Yayımlanan telefon sürümü **0.10.0 / code 18**'dir. Saat uygulaması değişmediği için **0.6.0 / code 10** korunur. Aşağıdaki tablo ilerideki güncellemelerde kullanılan numaralandırma türlerini gösterir; ayrı sürüm taahhüdü değildir.

| Değişiklik | Telefon için sonraki sürüm | Saat de değişirse sonraki saat sürümü |
|---|---|---|
| Hata düzeltmesi veya mevcut ekranın küçük iyileştirmesi | 0.10.1 / code 19 | 0.6.1 / code 11 |
| Yeni kullanıcı özelliği veya yeni modül | 0.11.0 / code 19 | 0.7.0 / code 11 |
| Yalnız dokümantasyon veya teslim süreci | 0.10.0 / code 18 korunur | 0.6.0 / code 10 korunur |

- Numara, çalışmaya başlarken gerçek kapsam ve depodaki en son sürüm esas alınarak kesinleştirilir. Yayımlanan/teslim edilen yeni APK için ilgili modülün `versionCode` değeri monoton artar; art arda test derlemeleri ayrı sürüm sayılmaz.
- Telefon ve saat bağımsız numaralandırılır. Değişmeyen modülün sürümü artırılmaz. Değerlerin kaynağı `app/build.gradle.kts` ve `wear/build.gradle.kts` dosyalarıdır.
- Veri, yedek, uygulama kimliği veya iletişimde uyumsuz değişiklik gerekiyorsa başlamadan önce somut geçiş planı eklenir. 1.0.0 için ayrıca bir kararlı sürüm kapsamı belirlenir.

## Her güncellemede uygulanacak akış

1. Koordinatör ürün kapsamını, önceliği ve hedef sürümü belirler. Sürüm Belgeleri bu plana kullanıcıya yansıyan değişiklikleri, veri/saat uyumluluğunu ve kabul kontrollerini yazar.
2. Teknik lider ortak dosyaları tahsis eder, uzmanlara teknik iş atar, incelemeyi ve yerel entegrasyonu yürütür. Kod içindeki sürüm alanlarını yalnız teknik lider veya açıkça tahsis ettiği görev değiştirir.
3. Atanan geliştiriciler ilgili kontrolleri çalıştırır; Test birleşmiş adayın işlevsel kabulünü raporlar. Derleme ve APK görevi, gerekiyorsa bu kaynak commit'inden kişisel ve herkese açık paketleri ayrı üretir, sürüm/imza/SHA-256 ve kaynak/lisans eşleşmesini doğrular. Tahsisli izole test ortamındaki kontroller güvenlik yanıtı beklenirken sürdürülebilir.
4. Sürüm Belgeleri planı, değişiklik geçmişini ve gerekli kullanım/README notlarını doğrulanmış sonuçlarla günceller. Yalnız ilgili dosyaları commit'e alır; özel ayarları, anahtarları, cihaz kanıtlarını ve kişisel paketleri yayımlanacak içeriğe eklemez. Yukarıdaki inceleme paketi alanları ve teknik test kanıtları sabit kaynak/paket adayıyla eşleştirilir.
5. Yeni uygulama sürümünde koordinatör bu adayı Güvenlik projesindeki Siber Güvenlik görevine sunar ve açık OLUMLU dönüşü bekler. Olumsuz, eksik, engelli veya yanıtsız karar beklemededir; zaman aşımı istisnası yoktur. Kaynak, paket veya güvenlik kararının dayandığı ilgili derleme/hizmet yapılandırması değişirse yeniden olumlu teyit gerekir. Teknik test, paket hazırlama veya teknik lider incelemesi bu kararın yerine geçmez.
6. Her teslimde hedef depo/dal, varsa etiket/Release, kaynak commit, tam yayın metni, dosya listesi ve varsa her APK'nın SHA-256 değeri koordinatöre sunulur. Olmayan etiket, Release ve APK açıkça belirtilir. Yeni uygulama sürümünde koordinatör eşleşen olumlu güvenlik dönüşünden sonra sürümü kabul eder ve somut yayın/kurulum onayını verir; iki onay birbirinin yerine geçmez. Karar/tarih/kapsam/kanıt referansı ve son kabul yerel teslim kaydında tutulur. Değişen commit, metin, hedef veya dosyalar yeniden onaylanır. Uygulama kodu, incelenen adayın ilgili derleme/hizmet yapılandırması, sürümü ve paketi değişmeyen salt belge tesliminde uygulama güvenlik taraması, uygulama testi/derlemesi veya APK üretimi gerekmez; yine de o belge commit'i için ayrı somut yayın onayı alınır. Rutin işlem için kullanıcıdan tekrar onay istenmez.
7. Onaylı GitHub işlemlerini yalnız Sürüm Belgeleri mevcut CLI/OAuth oturumuyla yürütür. Kullanıcı farklı bir akış istemedikçe önerilen hedef `osmanatayozturk/iz-android` deposunun `main` dalıdır. Uzak dal ilerlerse teknik liderle uzlaştırılır ve değişen aday yeniden onaylanır; yeni uygulama sürümünün kaynağı, paketi veya incelenen ilgili derleme/hizmet yapılandırması değişirse güvenlik teyidi de yenilenir. Force push yapılmaz. Herkese açık APKlar yalnız onaylı Release varlığına yüklenir; APK/AAB dosyaları kaynak deposuna eklenmez.
8. Uzak commit, belgeler ve yayın metni doğrulanır. Varsa etiket/Release'in kaynak commit'i, indirilebilir dosyalar ve APK SHA-256 değerleri kontrol edilir. Cihaz Kurulumu görevinin kurulum sonuçları ile Test'in işlevsel kabul sonuçları ayrıca raporlanır; her kontrolün gerçek durumu geçti, kaldı, engelli veya çalıştırılmadı olarak belirtilir. Kullanıcı telefonu/saati güncellemesi yeni sürümün güvenlik sonucu ve koordinatör kabul/kurulum onayı tamamlanmadan başlatılmaz.

Bu akış [AGENTS.md](../AGENTS.md) içinde de kayıtlıdır. Diğer proje dışı iletişim ve Birikenler teslimi koordinatörde kalır. Zamanlanmış bir görev veya otomasyon kurulmaz.

## Tamamlanan plan: İz 0.10.0 / code 18

Telefon için Rotalarım, rota tercihleri ve alternatifler, GPX çizgi takibi, gezi koleksiyonları ile fotoğraf ve GPX paylaşım gizliliği tamamlandı. Çevrimdışı harita ve yeni bir çevrimdışı rota motoru bu sürüme eklenmedi. Yerel veritabanı (Room) ve manuel yedek sürümü 7 oldu; eski 1-6 verileri okunur. Wear OS 0.6.0 / code 10 ve iletişim biçimi v5 korunur. [Sürüm notları, doğrulama sonuçları ve dosyalar](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.10.0).
