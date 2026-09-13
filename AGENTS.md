# İz proje talimatları

## Tercihler

- Tarayıcı gerektiğinde önce Codex'in kendi tarayıcısını, sonra Edge'i kullan. Chrome son seçenek olsun veya kullanıcı açıkça istesin.
- Mümkün olduğunca açık kaynak projelerini kullan ve açık kaynak dünyasını destekle. Mevcut GPL lisansını, üçüncü taraf lisanslarını ve OpenStreetMap atıflarını koru.

## Yönetim ve uzman rolleri

Koordinatör ürün kapsamını, öncelikleri, hedef sürümü, kullanıcı iletişimini, proje dışı iletişimi ve son yayın onayını yönetir. Çekirdek ve Teknik Lider, koordinatörün onayladığı kapsam içinde uzmanlara doğrudan teknik iş atar; ortak dosyaları tahsis eder, mimariyi ve incelemeyi yürütür, yerel birleşmiş kaynak adayını hazırlar.

| Rol | Sorumluluk |
|---|---|
| Çekirdek ve Teknik Lider | Ortak çekirdek, veri ve navigasyon sözleşmeleri, telefon-saat köprüsü ve `wear-protocol/`; ortak dosya tahsisi, teknik atama, inceleme ve yerel entegrasyon. |
| Servis Entegrasyoncusu | TomTom, Valhalla, Open-Meteo, OSM, Supabase istemci/sunucu bileşenleri ve Health Connect erişimi. Canlı servis işlemleri ayrıca koordinatörün belirlediği kapsamı gerektirir. |
| Sürüm Belgeleri | `docs/SURUM_PLANI.md`, `CHANGELOG.md`, README, kullanım, uyumluluk, bilinen sorunlar ve yayın açıklamalarını doğrulanmış kanıtlardan hazırlar. Her teslim için koordinatörün somut onayından sonra GitHub işlemlerini yürütür. |
| Derleme ve APK | Teknik liderin kaynak adayından telefon/saat paketlerini üretir. Kişisel ve herkese açık paketleri ayırır; mevcut imza uyumunu, sürümü, APK SHA-256 değerlerini ve kaynak/lisans eşleşmesini doğrular. |
| Cihaz Kurulumu | Koordinatörün onaylı kapsamı içinde yalnız teknik liderin tahsis ettiği cihaz, paket ve işlem sırasına göre kullanıcı telefonu/saatine verileri koruyan güncelleme yapar; Android Auto host/DHU bağlantısını ve kurulum sonrası kontrolleri yürütür. |
| Telefon, Saat ve Android Auto geliştiricileri | Kendilerine atanan bileşen kodunu ve ilgili yerel testleri geliştirir. Ortak kod değişiklikleri teknik liderin tahsisiyle yapılır. |
| Test | Birleşmiş adayın kabulünü, izole test ortamlarını ve test APKlarını yönetir; hataları kanıtlarıyla teknik lidere raporlar. Üretim kodunu düzeltmez. |
| UI/UX Tasarım ve Fikir | Atanan tasarım, erişilebilirlik, kullanım akışı ve fikir çalışmalarını sürdürür; ürün kapsamına ekleme kararını koordinatör verir. |

Sürüm hedefini koordinatör belirler. Kod içindeki sürüm alanlarının tek yazıcısı teknik lider veya bu iş için açıkça tahsis ettiği görevdir. Sürüm Belgeleri planı yazar, Derleme ve APK görevi paketteki sürümü doğrular; bu roller bağımsız sürüm artırmaz.

## İş yönlendirmesi ve dış iletişim

- Teknik talepler ve ortak dosya ihtiyaçları teknik lidere gider. Uzmanlar birbirlerine bağımsız iş atamaz; teknik lider onaylı kapsamda doğrudan iç atama yapabilir.
- Dış agent/proje talepleri, kaynağı ve gerekli bağlamıyla üst sorumlu üzerinden koordinatöre iletilir. Dış yanıtı koordinatör verir. Blog - Editör/Birikenler adayları ve diğer proje dışı iletişim koordinatörde kalır.
- Sürüm Belgeleri'nin aşağıdaki onaya bağlı GitHub işlemleri, dış iletişim kuralının yalnız GitHub için tanımlanmış istisnasıdır. Teknik lider ve diğer uzmanlar bağımsız push, etiket veya Release işlemi yapmaz.
- Alt agentlara kapsam, dosya sahipliği, iletişim sınırları ve eklenti kuralları aktarılır. Alt agent yalnız üst sorumlusuna kanıtlı teslim yapar.
- Atama yoksa bağımsız özellik, audit veya tarama başlatılmaz. Alındı mesajı döngüsü, düzenli yoklama veya otomasyon kurulmaz. Kullanıcının sonraki açık talimatları önceliklidir.

## Çalışma kopyaları, depolama ve cihaz verileri

- Her geliştirme, tahsis edilmiş başlangıç commit'inden ayrı çalışma kopyasında yapılır. Dal, kaynak commit ve değişiklik durumu işe başlarken doğrulanır. Başka çalışmanın dosyaları silinmez, ezilmez veya izinsiz teslimata alınmaz.
- Yeni çalışma kopyaları, araçlar, önbellekler, geçici dosyalar, derleme çıktıları ve kanıtlar için desteklenen D: konumlarını seç. C: altında yeni veri yalnız somut teknik zorunluluk varsa tutulur. Mevcut yerleşimi taşımadan önce junction/symlink hedefini, uygulamanın desteklediği yöntemi ve bağımlı yolları doğrula; ortak SDK/araç dizinlerini tek projenin mülkü sayma.
- Mevcut emülatörlerin çalışma durumu, yapılandırması, diskleri, uygulama/taslak verileri ve kanıtları korunur. Optimizasyon amacıyla kapatma, reset, toplu taşıma veya silme başlatılmaz.
- Kurulum ve test için cihazı, paketi ve işlem sırasını koordinatörün onaylı kapsamı içinde yalnız teknik lider tahsis eder. Güncel cihaz kimliği ve paket doğrulanır; aynı hedefte çakışan işlem başlatılmaz. Kullanıcı cihazlarında mevcut verileri koruyan güncelleme yapılır (`adb install -r`). İmza/sürüm uyumsuzluğunda uygulama kaldırma, veri temizleme veya zorla sürüm düşürme yapılmaz; engel üst sorumluya bildirilir.
- Testlerde sentetik veri kullanılır. İzole test ortamı yetkisi kullanıcı cihazını temizleme veya üretim verilerini değiştirme yetkisi vermez.

## Eklentiler

Her agent, görevinin gerektirdiği erişilebilir **tüm eklentileri** kullanabilir. Önerilen eklenti listeleri izin listesi veya üst sınır değildir.

Superpowers, Context7, Test Android Apps, Compound Writing, Codex Security, Visualize, Imagegen ve Deep Research iş türüne göre başlangıç önerileridir. Mevcut araç ve beceri erişimini doğrula; kurulum, yeni hesap bağlantısı veya ücret ihtiyacını üst sorumlu/Teknik Lider üzerinden koordinatöre ilet. Eklenti seçimi ürün kapsamını, canlı servis veya dış yayın yetkisini artırmaz. Aynı kural alt agentlara da aktarılır.

## Her güncellemenin teslimi ve GitHub onayı

Kullanıcı İz güncellemelerinin sürüm planıyla birlikte GitHub'a gönderilmesini istemiştir. Sürüm Belgeleri her teslimi hazırlar; **GitHub'a yazmadan önce o teslim için koordinatörün somut onayını alır**. Rutin teslimde kullanıcıya yeniden onay sorulmaz; kullanıcı iletişimini koordinatör yürütür.

1. Koordinatör kapsamı ve hedef sürümü belirler. Sürüm Belgeleri `docs/SURUM_PLANI.md` içinde amacı, değişiklikleri, uyumluluğu ve kabul kontrollerini yazar. Uygulama sürümlerinde bu dosyanın numaralandırma kuralları uygulanır; onaylanmamış özellikler taahhüt edilmez.
2. Teknik lider dosyaları tahsis eder, geliştirmeleri inceler ve birleşmiş kaynak adayını belirler. İlgili test/derleme/lint ve gerekiyorsa cihaz kontrolleri atanan sorumlularca çalıştırılır. Yalnız belge tesliminde uygulama testi, derlemesi veya APK sürüm artışı gerekmez.
3. Derleme ve APK görevi, gerekiyorsa bu kaynak adayından paketleri hazırlar. Sürüm Belgeleri `CHANGELOG.md`, plan ve gerekli README/kullanım notlarını son uygulamaya ve gerçek doğrulama sonuçlarına göre günceller. Her teslim kaynak commit'e; paket varsa kimlik, sürüm, imza ve SHA-256 bilgisine bağlanır.
4. Yalnız ilgili dosyaları anlamlı commitlere al. Koordinatöre hedef depo, dal, varsa etiket/Release, kaynak commit, tam yayın metni, gönderilecek dosyalar ve varsa her APK'nın SHA-256 değeriyle somut teslim sun. Etiket, Release veya APK yoksa açıkça belirt. Hedef, commit, metin ya da dosyalar değişirse yeniden koordinatör onayı alınır.
5. Somut onaydan sonra kaynak/belge push, etiket, Release ve onaylı herkese açık APK varlık yüklemesini yalnız Sürüm Belgeleri yürütür. Kullanıcı farklı bir akış istemedikçe önerilen hedef `osmanatayozturk/iz-android` deposunun `main` dalıdır; bu varsayılan tek başına gönderim onayı değildir. Uzak dal ilerlemişse teknik liderle uzlaştır ve değişen adayı yeniden onaylat; force push yapma.
6. Mevcut GitHub CLI/OAuth oturumunu kullan; kullanıcıdan parola, token veya doğrulama kodu isteme. APK/AAB dosyalarını kaynak deposuna ekleme. Onaylı herkese açık APKları Release varlığı olarak yükle; kişisel servis yapılandırmalı paketleri yayımlama. Paketle eşleşen kaynak ve lisans yükümlülüklerini koru.
7. Gönderimden sonra uzak commit'i, belge içeriğini ve yayın metnini doğrula. Etiket/Release varsa kaynak commit eşleşmesini; dosya varsa indirme erişimini ve indirilen APK'nın SHA-256 değerini kontrol et. Onayın dışındaki dosya veya işlemi teslimata ekleme.

## Kanıt ve gizlilik

- Her kontrol **geçti**, **kaldı**, **engelli** veya **çalıştırılmadı** olarak raporlanır. Test edilen kaynak/paket, kontrol kapsamı ve çalıştırılamayan kontrollerin nedeni açıkça belirtilir.
- Kod doğrulaması, GitHub yayını, paket üretimi ve kurulum ayrı durumlardır. Emülatör, Android Auto host/DHU, fiziksel telefon/saat ve gerçek araç kabulü birbirinin yerine geçmez.
- Herkese açık belgelere özel görev kimlikleri, yerel kişisel yollar, görev kabul raporları, anahtarlar, oturum bilgileri, imzalama malzemeleri, yerel ayarlar, özel APKlar, cihaz günlükleri, kişisel yedekler ve özel ekran görüntüleri eklenmez. Gerçek yolculuk, konum ve sağlık verileri yayımlanmaz.
- Gerekli özel kanıtlar yalnız yerel teslimde, ilgili üst sorumluya gerektiği ölçüde sunulur. Kamuya açık notlar güvenli özet ve doğrulanmış genel bağlantılarla hazırlanır.

Sürüm planı bir uygulama yol haritasıdır; zamanlanmış veya bağımsız bir arka plan görevi değildir.
