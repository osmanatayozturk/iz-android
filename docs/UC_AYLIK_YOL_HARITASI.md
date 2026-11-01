# İz: üç aylık geliştirme yol haritası

Bu belge, onaylı kişisel kullanım odaklı programı küçük, sıralı adaylara ayırır. Telefon 0.11.5/code27 sabit kaynak adayı için yerel Debug birim/lint/assemble ve Debug APK kimlik/imza kontrolleri tamamlandı. Bu sonuç Release/AAB, cihaz veya yayın kabulü değildir. Haftalık aday ritmi planlama hedefidir, takvim veya yayımlama sözü değildir. Sonraki backlog başlıkları yalnızca 0.11.5 kabulünden sonra sırayla değerlendirilir; bu belge yeni aday başlatmaz.

## İlk paket — Telefon 0.11.5 / code 27, sesli yönlendirme yönergeleri

İki kısa Türkçe yönerge hatırlatıcıdır: ses kontrolünü araç dururken yap, interkom kullanılıyorsa seçili ses çıkışı ile medya sesini kontrol et ve ekranı elle kilitleyip yeniden açtığında anonsun duyulup duyulmadığını gözlemle. Motor hazırlığı veya Bluetooth bağlantısı duyulabilir sesi kanıtlamaz; ses duyulmazsa mevcut “Tekrar dene” ya da “Seslendirme ayarları” kontrolü önerilir. Kaynak adayı [`c593bfb`](https://github.com/osmanatayozturk/iz-android/commit/c593bfb739f1cd82ad994013e733a33e6c653bb8) üzerinde 967 Debug birim testi geçti, lint 0 hata/ölümcül hata ve 95 uyarı verdi; Debug assemble ve APK kimlik/imza doğrulaması tamamlandı. `-PizTestBuildType=batteryCheck` yalnız test hedefi yapılandırmasıdır; AndroidTest-source lint, `batteryCheck` lint ve enstrümantasyon doğrulanmadı. Native/gerçek cihaz interkom sesi, kilit ekranı sürekliliği, TalkBack ve Release/AAB kabulü yoktur. Wear OS **0.6.1 / code 11** ve iletişim **v6** değişmez.

- **Kaynak kapsamı:** İki metin eklemesi mevcut ses testi, tekrar deneme, seslendirme ayarları veya ses/oturum durum davranışını değiştirmez. Wear OS **0.6.1 / code 11** ve iletişim **v6** değişmez.
- **Kabul sınırları:** Yerel Debug birim/lint/assemble ve paket kimlik/imza kontrolleri geçti. AndroidTest-source lint, `batteryCheck` lint, enstrümantasyon, gerçek cihaz interkom/ekran kilidi sesi ve görünüm/TalkBack doğrulanmadı. Bu yol haritası cihaz veya yayın kabulü vermez.

## Önceki paket — Telefon 0.11.4 / code 26, harita üzerinde Gösterge (tarihsel)

Bu önceki adayın davranış ve doğrulama kaydı [değişiklik geçmişindedir](../CHANGELOG.md). Daha eski telefon adayları aşağıda tarihsel bağlamda korunur; kanıtları 0.11.5'e aktarılmaz.

0.11.4, 0.11.3'teki F05 yedek içeriği açıklaması ve F09 yerel mekân/OSM işlem teyitlerinin mevcut davranışını korur. 0.11.3 tarihsel masaüstü tesliminde 957 birim testi sıfır hata/başarısızlık/atlama ile geçti; Debug lint 0 hata/ölümcül hata ve 95 uyarı verdi, yerel Debug paketi üretildi ve UI/UX kaynak incelemesi kabul edildi. Native/cihaz, dış güvenlik ve yayın kabulü verilmedi. Bunlar 0.11.3'e özgü tarihsel kayıtlardır.

## Önceki aday — telefon 0.11.2 / code 24 (yayımlanmamış, tarihsel)

0.11.2 ayrı ve yayımlanmamış bir adaydır. APP-02/APP-03 test, lint ve Debug/QA paket bulguları yalnız kendi tarihsel kaynaklarına aittir; UX-0113 için test veya kabul kanıtı değildir. Sınırlı Menü → Ayarlar kontrolü başarısız oldu; kök nedeni belirlenmedi ve uygulama hatası olduğu doğrulanmadı. Tanılama test paketi üretildi ancak çalıştırılmadı. Native/UI/UX, aday bazlı güvenlik ve son yayın kabulü 0.11.2 için beklemede kaldı. Ayrıntılar [değişiklik geçmişinde](../CHANGELOG.md) korunur.

### 0.11.2 tarihsel doğrulama kaydı

APP-02 kaynak adayı 8b2160e06cc6fa5c5b3b141c50e070242e51afe7 üzerinde 934 birim testi geçti (0 başarısızlık, 0 hata, 0 atlama); lint 0 hata/ölümcül hata ve 95 uyarı verdi, Debug ve QA ürün APK’ları üretildi. APP-03 doğrulama kaynağı 4656b3492a8d2ff469e0043c0f28ad03c44ef7d6 yalnız AndroidTest test-kaynağı düzeltmesini içerir; ürün, derleme ve birim-test girdileri APP-02 ile aynı kaldığından birim/lint sonuçları yeniden çalıştırılmadan kullanıldı. AndroidTest APK’sı üretildi, üç APK’nın manifest/imza kontrolü geçti ve ürün APK’larının hash’leri aynı kaldı. Bu, yerel host ve Debug/QA doğrulama kaydıdır; Release veya cihazda AndroidTest çalıştırması anlamına gelmez. Dağıtım durumu, dosyalar ve doğrulama kapsamı için ilgili [GitHub Releases/sürüm notları kaydını](https://github.com/osmanatayozturk/iz-android/releases) esas alın. 0.11.1'e ait önceki test, lint ve ekran görüntüleri tarihsel kanıttır.

## Haftalar 1–4 — sonraki küçük adaylar

Yalnız 0.11.5 kabulünden sonra, sırayla değerlendirilecek backlog başlıkları:

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
