# İz 0.10.0 — Rotalarım, GPX takibi ve Geziler

[İz 0.10.0 telefon APK'sını indirin](https://github.com/osmanatayozturk/iz-android/releases/download/v0.10.0/iz-0.10.0-android.apk). Paket Android 10 veya üzerini ve Google Play hizmetlerini gerektirir. [Sürüm notları ve doğrulama sonuçları](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.10.0).

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
