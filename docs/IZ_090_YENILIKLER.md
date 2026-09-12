# İz 0.9.0

Telefon 0.9.0 (code 15), mevcut `org.iz.navigation` kimliğini kullanır. Aynı imzayla yerinde güncelleme kayıtları, izinleri ve kişisel TomTom anahtarını korur. Saat 0.6.0 (code 10) ve v5 iletişimi değişmez.

## Yolculuk havasında trafik

Trafik açıksa araba, yolcu ve motosikletin ilk hava planı TomTom güzergâhını ve süresini kullanır. Bir temel rota üzerinden yarım saat aralıklı yedi **yaklaşık** hava seçeneği çıkarılır. Bunlar yedi ayrı trafik sorgusu değildir. Hava açısından önerilen seçenek kendiliğinden seçilmez.

Başka bir kalkış satırına dokununca o saatin rotası, trafik süresi ve hava örnekleri yeniden hesaplanır; tamamlandıklarında birlikte gösterilir. İstek başarısızsa önceki tamamlanmış seçim korunur. Aynı girdilerin sonucu en fazla iki dakika kullanılabilir; kullanılan gerçek kalkış zamanı korunur. Süresi geçen, önbellekte bulunmayan seçenek yeniden planlama ister. **Şimdi başlat** her zaman güncel konum ve zamanla hesaplar.

Bisiklet, yürüyüş ve koşu mevcut Valhalla profillerini kullanır. Trafik servisi çalışmazsa kullanıcı açıkça trafik hariç devam etmeyi seçebilir. Eksik hava verisi sıfır yağış veya güvenli hava olarak yorumlanmaz.

## Matrix ile durak sırası önerisi

Yol tarifi veya Yolculuk havasında başlangıç ve varışa ek olarak en az iki ara durak ekle. **Durak sırası öner** düğmesi, TomTom Matrix v2 ile ara durakları karşılaştırır. Başlangıç ve varış değişmez. En fazla üç ara durak için 9–16 hücre ve en fazla altı sıralama değerlendirilir; dönüş yönüne göre süreler ayrı ele alınır.

Mevcut sıra ve önerilen sıra tam rotayla karşılaştırılır. Sonuç kartı karşılaştırmanın kalkış saatini, sürelerini ve mesafelerini gösterir. **Sırayı uygula** yalnız durak sırasını değiştirir. Matrix arka planda sürekli çalışmaz; eşit sürelerde mevcut sıra korunur. Başarısız hücreler ücretsiz/sıfır süreli yol sayılmaz.

Matrix v2 motosiklet profili sunmadığından sıralama **otomobil yaklaşımı** ile bulunur ve açıkça etiketlenir. Son iki tam rota gerçek motosiklet profiliyle doğrulanır. Karşılaştırma zamanı, art arda istekler sırasında geçmişte kalmaması için seçilen zaman veya en az iki dakika sonrasıdır; hava planının seçili kalkış saati kendiliğinden değişmez.

## Hızım ve yolun hız sınırı

Araba ve motosiklet yolculuklarında, kayıt açık veya kapalı navigasyonda ve hedefsiz sürüşte telefon ve Android Auto ayrı hız kutuları gösterir. Yolcu, bisiklet, yürüyüş ve koşuda bu panel yoktur.

- **Hızım:** mevcut GPS ölçümü. Ölçülmüş sıfır `0` olarak görünür. Beş saniyeden eski veya güvenilir olmayan ölçüm `—` olur.
- **Hız sınırı:** önce çevredeki OSM yol geometrisi ve hız etiketleri kullanılır. Paralel yollar, belirsiz yön, koşullu/şeride bağlı sınırlar veya zayıf eşleşme durumunda değer gösterilmez. Desteklenen sınırsız etiket `∞` görünür.
- OSM'de etiket eksikse, ayrıca etkinleştirilmiş TomTom erişimi uygun aktif rota bölümünden veya güvenle eşleşmiş yoldaki Reverse Geocoding sonucundan yararlanabilir. Serbest sürüş sonucu **Genel yol sınırı · TomTom** diye yazılır; motosiklete özel sınır olduğu iddia edilmez. OSM ve TomTom çelişirse sınır boş bırakılır.

Kaynak, yol, yön, oturum ve verinin yaşı kontrol edilir. Genel TomTom gözlemi en fazla 10 saniye veya 100 metre tutulur. Hız aşımı alarmı yoktur. Bilgi bulunamadığında tahmini ulusal sınır uydurulmaz; yol işaretleri esas alınır.

## Kaydedilen rotanın renkleri

Bütün yolculuk türlerinde çizgi, **o yolculuğun kendi ölçülmüş hız aralığına** göre mavi → turkuaz → sarı → kırmızı geçişiyle çizilir. En düşük hız mavi, en yüksek hız kırmızıdır. Hızlar birbirine çok yakınsa tek mavi ton kullanılır. Eksik veya geçersiz hız gri kalır; GPS kopuklukları birleştirilmez. Haritadaki küçük açıklama o kaydın km/sa aralığını gösterir.

Canlı yolculukta yeni en düşük/en yüksek ölçüm gelirse renk ölçeği yenilenir. Geçmiş kayıtlardaki mevcut hızlar da kullanılır. Farklı yolculukların kırmızı bölümleri aynı mutlak hızı ifade etmek zorunda değildir. Bu renkler hız sınırının aşıldığını göstermez.

## Biriktirilen yerlerin sırası

**Menü → Yerler → Sırala** yoluyla tutamacı sürükle veya yukarı/aşağı oklarını kullan. **Kaydet** kalıcılaştırır; **Vazgeç** değişiklikleri atar. Arama filtresi açıkken sıralama kapalıdır. Rota noktası seçme listeleri aynı sırayı kullanır. Yeni yer sona eklenir; yer düzenlemek veya ziyaret eklemek sırayı değiştirmez.

İlk güncellemede eski listenin son ziyaret sırası korunur. Günlük veritabanı ve ZIP yedek sürümü 6 olur; eski 1–5 yedekleri okunur. Eşzamanlı liste değişikliği eski sıralamayla sessizce üzerine yazılmaz. Mevcut tam yedek geri yükleme davranışı değişmez.

## TomTom erişimi, kota ve veri

**Trafik ve hız sınırı** ayarında kayıtlı anahtar korunabilir. Yeni özellikler için anahtarda **Matrix Routing v2 API** ve **Reverse Geocoding API** erişimi gerekir; Routing API mevcut kalır. Ücretsiz hesap, ilgili API kotası ve ücretli bakiye/otomatik yükleme bulunmadığı doğrulandıktan sonra Matrix ve hız sınırı ayrı açılır. Bu kontroller yeni kurulumlarda varsayılan kapalıdır.

Kotalar TomTom hesabı genelindedir; başka anahtarların tüketimi de etkilidir. İz ödeme planı açmaz. Yerel sorgu sayacı hesap genelinde ücret garantisi sağlamaz. Kota veya servis reddinde otomatik tekrar döngüsü kurulmaz. Güncel şartlar için [TomTom fiyatlandırması](https://docs.tomtom.com/pricing), [Matrix v2 belgeleri](https://docs.tomtom.com/matrix-routing-v2-api/documentation/synchronous-matrix), [Reverse Geocoding](https://docs.tomtom.com/reverse-geocoding-api/documentation/tomtom-maps/v1/reverse-geocode) ve [kullanım koşulları](https://docs.tomtom.com/legal/terms-and-conditions) incelenmelidir.

Durak koordinatları Matrix/rota hesabında, konum ve yön gerektiğinde ters adres sorgusunda TomTom'a gönderilir. TomTom rota ve hız gözlemleri yalnız bellektedir; OSM önbelleğine, katkı taslağına veya Supabase'e yazılmaz. Anahtar APK'ya veya kaynak koduna eklenmez. OSM hız sorguları POI sorgularıyla aynı günlük 90 sorgu/9 MB bütçesini paylaşır; yerel yol önbelleği 24 saat ve 16 MB ile sınırlıdır. Genel Overpass sunucularındaki bu kullanım kişisel ve deneyseldir.

Sahte sunucu ve cihaz testleri eşleşme kurallarını doğrular; bölgenin hız etiketi kapsamını veya gerçek yolculuk trafik tahmininin doğruluğunu garanti etmez.
