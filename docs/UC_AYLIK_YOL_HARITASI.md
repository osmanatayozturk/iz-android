# İz: üç aylık geliştirme yol haritası

Bu belge, onaylı navigasyon/gösterge paneli kapsamındaki kişisel kullanım odaklı programı küçük, sıralı adaylara ayırır. Telefon 0.11.4/code26 kaynak adayı tamamlandı ve yerel doğrulamaları yapıldı; herkese açık APK veya Release yayımlanmadı. 0.11.3 ve önceki 0.11.2 adaylarının kanıtları tarihsel bağlamda korunur, yeni adaya kabul olarak aktarılmaz. Haftalık aday ritmi planlama hedefidir, takvim veya yayımlama sözü değildir. Sonraki backlog maddeleri yalnızca 0.11.4 kabulünden sonra sırayla ele alınır; bu belge yeni aday başlatmaz.

## İlk paket — Telefon 0.11.4 / code 26, harita üzerinde Gösterge

Kaynak adayı, mevcut tek haritayı koruyarak **Gösterge** panelini aynı ekranda açar. Dikey yerleşim altta, yatay yerleşim yandadır. Hız ve varsa koşullu hız sınırı ilk sırada; manevra ve kritik uyarılar önde kalır. Geri paneli kapatıp gerçek açan ekranı ve oturumu korur. Kaynak adayında 967 birim testi geçti, lint 0 hata/ölümcül hata ve 95 uyarı verdi; yerel Debug derleme/paket kimliği doğrulandı. Yalnızca test uygulamasındaki Ana ekran/panel sahnelerinin dikey ve yatay kontrolleri geçti; atanmış UI/UX kaynak incelemesi ile iki normal yazı boyutlu görüntünün görsel kabulü tamamlandı. Bu sınırlı kontroller tam uygulama gezinme akışı, TalkBack veya yapılandırma/süreç geri yükleme kabulü değildir. Aday herkese açık olarak yayımlanmadı. Wear OS **0.6.1 / code 11** ve iletişim **v6** değişmez.

- **Yerleşim ve Back:** Tek mevcut harita aynı ekranda kalır. Gösterge dikeyde alt panel, yatayda yan paneldir. Geri yalnız paneli kapatır ve gerçek açan ekran/oturum korunur; panel açma/kapama konum bulma, rota önizleme veya kayıt başlatmaz.
- **Öncelik ve eylemler:** Hız ve varsa koşullu hız sınırı başta; yönlendirme varsa manevra ve kritik uyarılar önde kalır. Yönlendirme/ilerleme yokken manevra, kalan rota veya ETA uydurulmaz; eski hız güncelmiş gibi sunulmaz. Sesi kapatma ile kayıt, yönlendirme ve tüm oturum işlemleri ayrı kalır. Yönlendirme sırasında Nereye? başlığının yüksekliği 0'dır.
- **Veri ve harita sahipliği:** Mevcut oturumun navigation/progress/speed/weather/group/analysis snapshot'ları ve sahiplik/güncellik etiketleri kullanılır. Yeni veri üreticisi, harita, GPS/API isteği, zamanlayıcı, sorgu, sensör, ETA/traffic hesabı veya ağır metrik yoktur. Kamera, manuel harita tutuşu/recenter, ölçülen viewport ve atıf korunur. Wear OS **0.6.1 / code 11**, iletişim **v6**, şema, izin ve bağımlılıklar değişmez.
- **Kabul kapıları:** Uygulama, bağımsız test ve UI/UX kaynak kabulü; sabit adayla son teknik kontroller; aday bazlı güvenlik incelemesi ve son yayın kabulü birbirinden ayrıdır. İki sınırlı Ana ekran/panel sahnesi geçti; tam uygulama gezinme akışı, TalkBack ve yapılandırma/süreç geri yüklemesi çalıştırılmadı. Eski aday kanıtı bu kabulün yerine geçmez.

## Önceki adaylar — telefon 0.11.3 ve 0.11.2 (tarihsel)

0.11.3, F05/F09 yedek açıklaması ve OSM yerel işlemleri adayını; 0.11.2 önceki navigasyon/ekran davranışları adayını kaydeder. Bu sürümlerin test, kaynak ve kabul kanıtları kendi kapsamlarında kalır; 0.11.4 için doğrulama veya kabul değildir. Ayrıntılar [değişiklik geçmişindedir](../CHANGELOG.md).

0.11.4, 0.11.3'teki F05 yedek içeriği açıklaması ve F09 yerel mekân/OSM işlem teyitlerinin mevcut davranışını korur. 0.11.3 tarihsel masaüstü tesliminde 957 birim testi sıfır hata/başarısızlık/atlama ile geçti; Debug lint 0 hata/ölümcül hata ve 95 uyarı verdi, yerel Debug paketi üretildi ve UI/UX kaynak incelemesi kabul edildi. Native/cihaz, dış güvenlik ve yayın kabulü verilmedi. Bunlar 0.11.3'e özgü tarihsel kayıtlardır.

## Önceki aday — telefon 0.11.2 / code 24 (yayımlanmamış, tarihsel)

0.11.2 ayrı ve yayımlanmamış bir adaydır. APP-02/APP-03 test, lint ve Debug/QA paket bulguları yalnız kendi tarihsel kaynaklarına aittir; UX-0113 için test veya kabul kanıtı değildir. Sınırlı Menü → Ayarlar kontrolü başarısız oldu; kök nedeni belirlenmedi ve uygulama hatası olduğu doğrulanmadı. Tanılama test paketi üretildi ancak çalıştırılmadı. Native/UI/UX, aday bazlı güvenlik ve son yayın kabulü 0.11.2 için beklemede kaldı. Ayrıntılar [değişiklik geçmişinde](../CHANGELOG.md) korunur.

### 0.11.2 tarihsel doğrulama kaydı

APP-02 kaynak adayı 8b2160e06cc6fa5c5b3b141c50e070242e51afe7 üzerinde 934 birim testi geçti (0 başarısızlık, 0 hata, 0 atlama); lint 0 hata/ölümcül hata ve 95 uyarı verdi, Debug ve QA ürün APK’ları üretildi. APP-03 doğrulama kaynağı 4656b3492a8d2ff469e0043c0f28ad03c44ef7d6 yalnız AndroidTest test-kaynağı düzeltmesini içerir; ürün, derleme ve birim-test girdileri APP-02 ile aynı kaldığından birim/lint sonuçları yeniden çalıştırılmadan kullanıldı. AndroidTest APK’sı üretildi, üç APK’nın manifest/imza kontrolü geçti ve ürün APK’larının hash’leri aynı kaldı. Bu, yerel host ve Debug/QA doğrulama kaydıdır; Release veya cihazda AndroidTest çalıştırması anlamına gelmez. Dağıtım durumu, dosyalar ve doğrulama kapsamı için ilgili [GitHub Releases/sürüm notları kaydını](https://github.com/osmanatayozturk/iz-android/releases) esas alın. 0.11.1'e ait önceki test, lint ve ekran görüntüleri tarihsel kanıttır.

## Haftalar 1–4 — sonraki küçük adaylar

Yalnız 0.11.4 kabulünden sonra, sırayla değerlendirilecek backlog başlıkları:

- **Sonraki aday:** Ekran kilitliyken ve interkomla kullanım tutarlılığını incelemek; saati bilgilendirici yüzey olarak tutmak ve Android Auto/Wear metinleriyle ilgili F06–F08 açıklama konularını değerlendirmek.

Bu maddeler ürün davranışı için peşin kabul değildir; sonraki adayların sınırları ve kabul ölçütleri ayrı belirlenir.

## Haftalar 5–8 — hazırlanmış rota ve seçili koridor

Valhalla ile önceden hazırlanmış rotayı ve seçilmiş koridor için indirilebilir OpenFreeMap/MapLibre harita paketini değerlendirecek ayrı adaylar planlanır. İndirilmiş harita koridoru GPS ile kullanılabilir; mevcut dönüş yönergeleri ve yerel Türkçe ses korunur. Paket yeni çevrimdışı rota oluşturma veya yeniden hesaplama sağlamaz; rota önizlemesi ve mevcut çevrimiçi davranış korunur, rota farkı varsa kullanıcıya açıkça gösterilir.

İndirme için kabul sınırları: paket başına en fazla 3.000 karo ve 50 MB; sağlayıcı başına en fazla dört eşzamanlı istek ve ayırt edilebilir User-Agent. Kotayı bölerek aşma yapılmaz. TomTom REST çıktıları için saklama izni kesinleşmeden kalıcı önbellek eklenmez. Hazır olma, devam ettirme, hata ve temizlik davranışları ayrı kanıtlanmadan tamamlanmış sayılmaz.

## Haftalar 9–12 — çevrimiçi rota araçları ve yolculuk bilgisi

Ayrı adaylar halinde çevrimiçi rota kesimi kaçınma/atlama, duraklamayı hesaba katan ETA ve hava durumu, rota yakınındaki yakıt/dinlenme noktaları, kişisel yolculuk karşılaştırmaları ve viraj ayrıntısı ele alınabilir. Gidon yatış ölçümü yalnız uygulanabilirlik araştırmasıdır; doğrulanmamış derece veya doğruluk iddiası kullanılmaz ve navigasyon teslimini engellemez.

## Bütün adaylar için sınırlar

- Mevcut ulaşım türleri, veri ve yedek uyumluluğu, günlük kayıtları ve navigasyon yaşam döngüsü korunur. Saat için yeni bağımsız motor/uzaktan özellik veya ticari dağıtım bu programda yoktur.
- Ağır iz/ısı haritası/istatistik ve saat özetleri aktif kayıt/navigasyon yokken ve pil en az %20 iken ertelenmiş iş olarak kalır. Özel yerel tanılama ve yedek dışı saklama sınırları korunur.
- Canlı servis, ücretli hesap geçişi, otomatik zamanlama, donanım alımı veya geniş cihaz matrisi bu yol haritasından yetki almaz. Maliyetli seçenekler somut ihtiyaç ve ayrı karar gerektirir.
- Her aday için teknik, test, UI/UX, güvenlik ve yayın durumu ayrı raporlanır. Bir plan maddesi, tek başına uygulama, test, güvenlik veya yayın kabulü değildir.
