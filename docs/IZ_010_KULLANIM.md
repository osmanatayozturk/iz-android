# İz 0.11.0 — Motosiklet rotası, sürüş kamerası ve yolculuk sonrası yatış özeti

[İz 0.11.0 sürüm sayfası, telefon APK'sı ve doğrulama notları](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.11.0). Android 10 veya üzeri ve Google Play hizmetleri gerekir. Aşağıda 0.11.0 özellikleri ile önceki 0.10.x sürümlerinin kullanım bilgileri yer alır.

## 0.11.0 — motosiklet sürüşü

Telefon uygulaması **0.11.0 / code 22** sürümünde Wear OS **0.6.1 / code 11** ve telefon-saat iletişimi **v6** olarak kalır; yeni Wear APK'sı bu sürüme dahil değildir.

- **Motosiklet rotası ve hedefleri:** Tek Motosiklet türü kullanılır; işe gidiş modu veya profili yoktur. En hızlı rota ve bilinen kaplamasız yollardan kaçınma varsayılandır; otoyol ve ücretli yol açık kalır, kaydedilmiş tercihler korunur. Ev/İş hedefi kayıtlı yerlerden açıkça atanır; kısayol türü veya kaydı değiştirmez ve rota önizlemesini atlamaz. Yol verisinin eksikliği asfalt ya da güvenlik garantisi değildir; sağlayıcı tercihi uygulayamazsa durum açıklanmalı, sessizce otomobil rotasına geçilmemelidir.
- **Sürüş kamerası:** Araba/motosiklette teyitli kayıt veya yönlendirme sırasında telefon ve Android Auto için hız/manevraya uyarlanan harita kamerası kullanılır. Planlama, boşta bekleme ve geçici kayıt adayı kapsam dışıdır. Elle kaydırma/yakınlaştırma takibi durdurur; konuma dönme takibi açar. Düz görünüm ve genel bakış kontrolleri korunur; Android Auto alanı bilinmiyor veya küçükse düz görünüm kullanılır. Telefonda mevcut ekran yönü tercihi korunur. Telefon sahnesinin sınırlı QA akışı R3'te kontrol edildi ve iki GPU ekran görüntüsü UI/UX tarafından bu kapsamda kabul edildi; bu tam telefon veya tam çalışma zamanı kabulü değildir. Android Auto bileşeni ve host çalışma zamanı kabul edilmedi.
- **Yolculuk sonrası yatış özeti:** İsteğe bağlı ve deneysel hesap yalnız tamamlanmış motosiklet yolculuğuna uygulanır. Açık tercih, gövdeye sabit telefon, uygun kalibrasyon ve yeterli sensör/GPS örnekleri gerekir. Gidon/bilinmeyen montaj, eksik kalibrasyon ya da yetersiz örnek sayısal sonuç vermez. Saatin bilek hareketi gövde açısını temsil etmez; tehlikeli manevra istenmez ve canlı açı göstergesi yoktur. Ağır hesaplama kayıt/navigasyon kalmadığında ve pil en az %20 olduğunda yapılır; yeni kayıt veya düşük pilde bekler. Yatış yakalama açık Motosiklet kaydında 1 saniyelik GPS hedefi kullanır; diğer kayıtların sıklığı değişmez.
- **Veri ve sınırlar:** Ham sensör verisi uygulama içinde özel tutulur; Android otomatik yedekleme/paylaşımı, grup paylaşımı veya otomatik yüklemeye eklenmez. Sürümlü manuel ZIP yedeği analiz bekleyen kapatılmış ham örnekleri ve kalıcı sonuçları içerebilir. Eski yolculuklara geriye dönük yatış tahmini üretilmez; özellik kullanılamasa da temel kayıt/navigasyon sürer. Fiziksel doğruluk, güven yüzdesi, enerji kazancı veya sürüş güvenliği iddiası yoktur.

### Doğrulama kapsamı ve kalan sınırlar

- Değişmeyen ürün kaynağına ait hedefli birim testi kanıtı **142 geçti, 0 başarısız, 0 atlandı**; yalnız test kodunun değiştiği bu çalışma için yeniden çalıştırılmadı. Telefon lint sonucu **0 hata / 108 uyarı** olarak korundu. QA AndroidTest turunda R1'de 10 geçti, 1 test beklentisi başarısız oldu; R2'de düzeltilen kısayol testi geçti, telefon sahnesinin açılışı test gövdesine ulaşmadan kaldı ve ekran görüntüsü alınmadı; R3'te telefon sahnesi testi geçti. R1'de geçen 10 kontrol R3'te yeniden çalıştırılmadı.
- R3'te normal yazı ölçeğinde iki gerçek GPU ekran görüntüsü alındı; UI/UX bu kapsamı kabul etti ve görsel engel bulmadı. İkinci görüntüde elle tutulan genel bakışın bir bölümü hava durumu katmanı altında kaldı; bu manuel tutuşu gösterir, uyarlanabilir hedeflemeyi kanıtlamaz. Ev/İş kısayollarını içeren başlık yerleşimi çalışma zamanında kontrol edilmedi.
- Bu sınırlı QA sonucu tam telefon veya Android Auto kabulü değildir. Auto bileşeni/hostu, gerçek GNSS/sürüş, fiziksel yatış doğruluğu ve enerji etkisi, tam uygulama ve daha geniş erişilebilirlik kabul edilmedi. Wear OS 0.6.1 / code 11 ve iletişim v6 değişmedi ve yeniden test edilmedi.

Wear OS 0.6.1 / code 11 ve telefon-saat iletişimi v6 değişmedi ve yeniden test edilmedi. 0.11.0'ın sınırlı doğrulama kapsamı ve kalan kabul başlıkları [sürüm planında](SURUM_PLANI.md) açıklanır. Android Auto hostu, gerçek sürüş, fiziksel yatış doğruluğu ve enerji etkisi bu doğrulama kapsamında değildir.

## 0.10.1 — Yer arama ve yolculuk işlemleri

Bu bölüm 0.10.1 kullanımını açıklar. Yukarıdaki indirme bağlantısı 0.10.0 paketine aittir; her sürümün paket ve doğrulanmış sonuçları kendi [Release kaydında](https://github.com/osmanatayozturk/iz-android/releases) bulunur.

**Yer ara:** Yer adının altında mevcut adres açıklaması gösterilir. Açıklama yoksa “Adres bilgisi yok” yazar. Uzun metinler sarılır; sonuçlara ve OpenStreetMap kaynak gösteriminin tamamına kaydırarak erişilebilir. Arama yalnız **Ara** ile başlar; sonuçlar sınırlıdır ve bütün yerleri kapsamayabilir.

**Yolculuk işlemleri:** Aktif yol tarifi kartına dokunmak mevcut **Nereye?** panelini açar ve yolculuğu sonlandırmaz. **Yol tarifini bitir** ile **Yolculuk kaydını bitir** ayrı eylemlerdir. Teyit, hangi işin biteceğini ve hangi açık işlerin devam edeceğini belirtir. En alttaki **Yolculuğu tamamen bitir** seçeneği açık işleri birlikte sonlandırmadan önce sonuçlarını gösterir. **Vazgeç**, geri veya paneli kapatma hiçbir işi bitirmez.

**Ekran:** Yol tarifi aktifken ve İz öndeyken ekran açık kalır; menüyü açmak bunu kesmez. Elle kilitleme geçerlidir. Yol tarifi bitince veya İz arka plana geçince normal ekran zaman aşımı geri gelir. Yalnız kayıt veya rota önizlemede bu davranış açılmaz.

## Rotalarım

Menüden **Rotalarım** bölümünü açın. Yol tarifi ve Hava ekranlarında hazırladığınız planı bir ad vererek kaydedebilirsiniz. Başlangıç, varış, ara duraklar, ulaşım türü, kişisel hız ve yol tercihleri saklanır.

Bir planı açtığınızda güncel rota yeniden hesaplanır. **Konumum** ile kaydettiğiniz başlangıç güncel konumunuzu kullanır; haritadan veya Yerler'den seçtiğiniz başlangıç aynı kalır. Plan kendi ulaşım türü, kişisel hız ve yol tercihleriyle açılır; ulaşım türünün diğer rotalarda kullanılacak son ayarları değişmez. Planı açmak veya kaydetmek yolculuk kaydı başlatmaz.

## Yol tercihleri ve alternatifler

Yürüyüş, koşu ve bisiklette **Otoyola girme** başlangıçta açıktır. Her ulaşım türünde yaptığınız son seçim ayrı saklanır. Kaydettiğiniz bir plan kendi tercihlerini korur.

Bu üç türde otoyoldan kaçınma açıkken servis rotanın otoyolsuz olduğunu doğrulayamazsa rota başlatılmaz; açıklama gösterilir. Tercih kendiliğinden kapanmaz. Araba, motosiklet ve yolcuda otoyol, ücretli yol ve feribot tercihleri rota sağlayıcısına iletilir; sağlayıcı her durumda tam kaçınmayı garanti etmeyebilir.

Desteklenen rotalarda **Alternatifler** ile en fazla iki ek seçenek isteyebilirsiniz. Hava değerlendirmesi seçtiğiniz rota için yapılır. Durak sıralama önerisi, seçilen tercihleri destekleyemediğinde açıklama gösterir.

## GPX izi takip et

Menüdeki **GPX izi takip et** ile bir GPX dosyası seçin veya Rotalarım'da sakladığınız bir izi açın. Dosyadaki bölümler ayrı gösterilir. Bir bölüm seçebilir, yönünü tersine çevirebilir ve haritada başlangıç noktası belirleyebilirsiniz.

Takibi başlattığınızda bölümde kalan mesafe, çizgiye uzaklık ve konum durumu görünür. Konum eskirse ilerleme bekler. Döngü veya kesişmede konumun hangi geçişe ait olduğu belirsizse başlangıcı yeniden seçmeniz istenir. Bir bölümün sonunda sonraki bölüme geçişi siz seçersiniz.

GPX takibi açıksa mevcut yolculuk kaydı korunur. Takibi açmak veya kapatmak kendi başına günlük kaydı ya da saat sensörü başlatmaz. Günlüğe kaydetmek için kayıt işlemini ayrıca kullanın. Açık navigasyon ile GPX takibi arasında geçerken onay istenir. Uygulama süreci kapanırsa takip otomatik yeniden başlamaz.

GPX çizgisinde dönüş talimatı, tahmini varış, hava değerlendirmesi ve otomatik yeniden rota hesaplama sunulmaz. Harita verisinin yüklenmesi mevcut çevrimiçi harita hizmetine bağlıdır.

GPX 1.0 ve 1.1 içindeki iz/bölüm kayıtları desteklenir. Yalnız rota noktaları veya yer işaretleri içeren dosyalar kabul edilmez. Dosya en fazla 10 MiB, 100 iz, 1.000 bölüm ve toplam 100.000 nokta içerebilir.

## Geziler

Menüden **Geziler** bölümünü açıp bir gezi oluşturun. Tamamlanmış ve kalıcı yolculukları seçerek sıralayın. Aynı yolculuk farklı gezilerde bulunabilir; genel istatistiklere birden fazla kez eklenmez.

Geziden bir yolculuğu çıkarmak veya geziyi silmek günlükteki yolculuğu silmez. Günlükte silinen yolculuk gezi üyeliklerinden de çıkarılır.

## Paylaşım ve yedek

GPX dışa aktarımında tarih/saat seçeneği her açılışta kapalıdır. İsterseniz açabilirsiniz. Dosya koordinatları içerir; başlangıç ve bitişi kırpma seçeneği kullanılabilir. Paylaşılan fotoğraflardan gömülü konum ve diğer kaynak üstverileri temizlenir; özgün fotoğraflar korunur.

Yerel veritabanı (Room) ve manuel yedek sürümü 7'dir; eski 1–6 verileri okunabilir. Kaydedilmiş planlar, GPX çizgileri ve Geziler yedeğe dahildir. Geçici takip oturumu yedeğe eklenmez ve geri yükleme kayıt başlatmaz. Sağlık verisinin yedeğe katılması mevcut ayrı seçime bağlıdır. Aynı uygulama kimliği ve imzayla güncelleme verileri yerinde korur; farklı imzalı APK için önce manuel yedek alın.

Telefon sürümü **0.10.0 / code 18**; saat **0.6.0 / code 10**, ortak iletişim **v5** olarak korunur. Genel APK kişisel TomTom veya OSM uygulama kimliği içermez; bu sağlayıcılara bağlı özellikler için kendi ayarınızı girmeniz gerekir. Grup özelliği genel APK'da bir Supabase sunucusuna bağlı değildir.
