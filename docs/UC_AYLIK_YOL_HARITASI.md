# İz: üç aylık geliştirme yol haritası

Bu belge, onaylı UX-0112 kapsamındaki kişisel kullanım odaklı üç aylık programı küçük, sıralı adaylara ayırır. İlk tek paket telefon 0.11.2/code24'tür ve önceki 0.11.1 planını da içerir; ayrı 0.11.1 yayını yoktur. Haftalık aday ritmi planlama hedefidir, takvim veya yayımlama sözü değildir. Sonraki maddeler backlog'dur; uygulanmış/test edilmiş özellikler değildir ve ilk paket kabulünden önce paralel başlatılmaz.

## İlk paket — telefon 0.11.2 / code 24, 0.11.1 kapsamı dahil

Telefon kaynak sürümü 0.11.2/code24'tür ve önceki 0.11.1 arayüz kapsamını içerir. Dağıtım durumu, dosyalar ve doğrulama kapsamı için ilgili [GitHub Releases/sürüm notları kaydını](https://github.com/osmanatayozturk/iz-android/releases) esas alın. Wear OS **0.6.1 / code 11** ve iletişim **v6** bu kaynak sürümünde korunur.

1. **Yönlendirme başlığı, bitirme teyidi ve karşıtlık:** Aktif dönüşlü yönlendirmede Nereye? başlığı ve aralığı gizlenir; dönüş/mesafe, uyarılar, ses, oturum kartı, İşlemler ve harita/kamera durumu korunur. Yalnız kayıt, rota önizleme ve dönüşsüz GPX davranışı değişmez. Günlük ayrıntısındaki **Bitir**, eşleşen yolculuğu açıklayan iptal edilebilir teyidi açar; yönlendirme, kayıt ve tüm oturumu bitirme seçenekleri ayrıdır. Normal metin karşıtlığı en az 4,5:1'dir.
2. **TTS durumu:** Telefon TTS motoru ve ses odağı tek sahibinde kalır; hava durumu konuşması bu sahibi kullanır. Yönlendirme için motor/dil hazırlığı teyitli yönlendirme başlangıcında bir kez sessizce yapılır. Hazır olma, tekil ses odağı/çıktı hatasından ve sessiz tercihinden ayrı gösterilir. Hata görsel yönlendirmeyi engellemez ve mevcut GPS/rota-yenileme uyarılarını değiştirmez; eski callback yeni istek/oturumu bozamaz. Dönüş anonsu sürmüyorsa motor/dil durumuna bağlı olarak TTS testi denenebilir; anons sürüyorsa istek sıraya alınmadan BUSY döner. Motorun hazır görünmesi duyulabilir ses çıkışını garanti etmez. Yeni dönüş anonsu elle testi keser. Otomatik tekrar, ses indirme veya sistem sesi/engine değişimi yoktur.
3. **Telefon gezinme geçmişi:** Menü ve sayfa, gerçek açan ekranına geri döner. Journeys/Places/Collections/Routes, GPX, Directions, Weather, Group, Community/OSM, Settings, backup/export/share ve map/picker/editor akışları kendi liste/ayrıntı/ebeveyn bağlamını korur. Yalnız hafif sorgu/filtre/sıralama/scroll/entity/account anahtarları saklanır; gizli ekran ağacı, domain nesnesi, kimlik bilgisi, izin tokenı veya iş gövdesi tutulmaz. Geri/restore ağ, kayıt, rota veya gönderim eylemini yeniden oynatmaz. Saat ve Auto kendi yüzeylerinde kalır.
4. **Gerçek kaydedilmemiş değişiklikler:** Yalnız özellik sahibinin dirty/busy ve onaylanmış kayıt/revision bilgisiyle doğruladığı değişiklik çıkış teyidi ister. **Düzenlemeye dön** girdiyi korur; **Değişikliklerden vazgeç** sadece kaydedilmemiş farkı atıp bekleyen çıkışı bir kez yürütür. Filtre, arama, kaydırma ve değişmemiş sıralama tek başına dirty değildir; değişmiş, açık kaydetme isteyen sıralamalar korunur. Seçiciler ebeveyn girdisini terk etmez. OSM'un mevcut yerel autosave'i sürer; onaylanan kayıt geri alınmaz veya silinmez, dispose callback'i atılan metni diriltmez. Back otomatik save/send yapmaz.
5. Başarılı teyitli rota başlatma yalnız onaydan sonra UI'ı sürüş haritasına alır; başarısızlık/iptal hazırlık bağlamını korur. Bu gezinme akışları günlük/navigasyon yaşam döngüsünü değiştirmez ve yeni veri/yedek biçimi veya genel taslak deposu oluşturmaz.

APP-02 kaynak adayı 8b2160e06cc6fa5c5b3b141c50e070242e51afe7 üzerinde 934 birim testi geçti (0 başarısızlık, 0 hata, 0 atlama); lint 0 hata/ölümcül hata ve 95 uyarı verdi, Debug ve QA ürün APK’ları üretildi. APP-03 doğrulama kaynağı 4656b3492a8d2ff469e0043c0f28ad03c44ef7d6 yalnız AndroidTest test-kaynağı düzeltmesini içerir; ürün, derleme ve birim-test girdileri APP-02 ile aynı kaldığından birim/lint sonuçları yeniden çalıştırılmadan kullanıldı. AndroidTest APK’sı üretildi, üç APK’nın manifest/imza kontrolü geçti ve ürün APK’larının hash’leri aynı kaldı. Bu, yerel host ve Debug/QA doğrulama kaydıdır; Release veya cihazda AndroidTest çalıştırması anlamına gelmez. Dağıtım durumu, dosyalar ve doğrulama kapsamı için ilgili [GitHub Releases/sürüm notları kaydını](https://github.com/osmanatayozturk/iz-android/releases) esas alın. 0.11.1'e ait önceki test, lint ve ekran görüntüleri tarihsel kanıttır.

## Haftalar 1–4 — sonraki küçük adaylar

Yalnız 0.11.2 kabulünden sonra, sırayla değerlendirilecek başlıklar:

- **Sonraki aday:** İlgili açıklama/disclosure konularını (F05/F09) yalnız ayrı kabul edilen kapsamda ele almak.
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
