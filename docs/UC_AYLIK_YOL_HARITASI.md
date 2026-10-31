# İz: üç aylık geliştirme yol haritası

Bu belge, onaylı UX-0113 kapsamındaki kişisel kullanım odaklı üç aylık programı küçük, sıralı adaylara ayırır. İlk paket telefon 0.11.3/code25 geliştirme adayıdır; yayımlanmamıştır. 0.11.2/code24 önceki yayımlanmamış adayın tarihsel kapsamı ve doğrulama kaydı olarak aşağıda korunur; 0.11.1 ayrı telefon sürümü değildir. Haftalık aday ritmi planlama hedefidir, takvim veya yayımlama sözü değildir. Sonraki maddeler backlog'dur; uygulanmış/test edilmiş özellikler değildir ve ilk paket kabulünden önce paralel başlatılmaz.

## İlk paket — Telefon 0.11.3 / code 25, F05/F09

Bu yayımlanmamış geliştirme adayı 0.11.2'nin mevcut telefon davranışını içerir ve buna aşağıdaki F05/F09 kapsamını ekler. 0.11.3 için uygulama, bağımsız test ve UI/UX kabulü tamamlanmadı; APK veya Release yayımlandığı iddia edilmiyor. 0.11.2'ye ait test/acceptance kanıtları otomatik olarak 0.11.3 kabulü sayılmaz. Wear OS **0.6.1 / code 11** ve iletişim **v6** değişmez.

- **F05 — yedek içeriği:** Açıklama; yolculuklar, konum kayıtları, yerler, özel notlar ve fotoğraflara ek olarak varsa deneysel yatış verileri, ham sensör kayıtları ile ilişkili konum, zaman, hız ve kalibrasyon bilgilerini belirtir. Sağlık ölçümleri varsayılan olarak dışarıda kalır; bu tercih yatış/sensör kayıtlarını etkilemez. Yedek içeriği, sağlık seçeneğinin varsayılanı ve biçimi değişmez.
- **F09 — mekân taslağı silme:** Yerel taslak silinmeden önce teyit sunulur; Vazgeç/Geri/dışarı dokunma değişiklik yapmaz. Kabul edilen silme yalnız beklenen, hâlâ değişmemiş ve silinebilir yerel taslağa uygulanır; OSM'deki mekânları değiştirmez.
- **F09 — OSM bağlantısını kesme:** Teyit, yerel OSM oturumunun kapanacağını ve topluluk kişileri/mesajları/taslaklarının temizleneceğini açıklar; günlük, fotoğraf ve mekân taslaklarının korunacağını, OSM hesabı ve sunucu içeriğinin silinmeyeceğini ve başlamış gönderimlerin tamamlanabileceğini belirtir. İptal hiçbir değişiklik yapmaz.
- **Kapsam dışı ve kabul:** Backend/auth, uzak silme/iptal, yedek/veritabanı biçimi, sağlık anahtarı, Undo ve saklama/temizleme politikası değişmez. Uygulama, bağımsız test, UI/UX kabulü, aday bazlı güvenlik incelemesi ve son yayın kabulü tamamlanmış sayılmaz; cihaz/native kontrolü çalıştırılmadı.

## Önceki aday — telefon 0.11.2 / code 24 (yayımlanmamış, tarihsel)

0.11.2 ayrı ve yayımlanmamış bir adaydır. APP-02/APP-03 test, lint ve Debug/QA paket bulguları yalnız kendi tarihsel kaynaklarına aittir; UX-0113 için test veya kabul kanıtı değildir. Sınırlı Menü → Ayarlar kontrolü başarısız oldu; kök nedeni belirlenmedi ve uygulama hatası olduğu doğrulanmadı. Tanılama test paketi üretildi ancak çalıştırılmadı. Native/UI/UX, aday bazlı güvenlik ve son yayın kabulü 0.11.2 için beklemede kaldı. Ayrıntılar [değişiklik geçmişinde](../CHANGELOG.md) korunur.

### 0.11.2 tarihsel doğrulama kaydı

APP-02 kaynak adayı 8b2160e06cc6fa5c5b3b141c50e070242e51afe7 üzerinde 934 birim testi geçti (0 başarısızlık, 0 hata, 0 atlama); lint 0 hata/ölümcül hata ve 95 uyarı verdi, Debug ve QA ürün APK’ları üretildi. APP-03 doğrulama kaynağı 4656b3492a8d2ff469e0043c0f28ad03c44ef7d6 yalnız AndroidTest test-kaynağı düzeltmesini içerir; ürün, derleme ve birim-test girdileri APP-02 ile aynı kaldığından birim/lint sonuçları yeniden çalıştırılmadan kullanıldı. AndroidTest APK’sı üretildi, üç APK’nın manifest/imza kontrolü geçti ve ürün APK’larının hash’leri aynı kaldı. Bu, yerel host ve Debug/QA doğrulama kaydıdır; Release veya cihazda AndroidTest çalıştırması anlamına gelmez. Dağıtım durumu, dosyalar ve doğrulama kapsamı için ilgili [GitHub Releases/sürüm notları kaydını](https://github.com/osmanatayozturk/iz-android/releases) esas alın. 0.11.1'e ait önceki test, lint ve ekran görüntüleri tarihsel kanıttır.

## Haftalar 1–4 — sonraki küçük adaylar

Yalnız 0.11.3 kabulünden sonra, sırayla değerlendirilecek backlog başlıkları:

- **Sonraki aday:** Tek Motosiklet modunu koruyarak daha sade navigasyon ile ayrıntılı gösterge paneli arasındaki sunumu ele almak.
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
