# İz kartı ve kadran alanı

Telefon 0.8.2 (code 14) ve Wear OS 0.6.0 (code 10), aynı uygulama kimliği ve imzayla güncellenir. Günlük veritabanı, ZIP yedek biçimi, kayıtlar ve telefondaki TomTom ayarı değişmez.

## Ekleme

Saatin kart ekleme ekranından **İz · yol ve gün** kartını seçin. Kadranı düzenlerken uyumlu bir bilgi alanına **İz** sağlayıcısını ekleyin. Kadranın desteklediği alanlar modele göre değişir; kısa metin, uzun metin ve yalnız simge desteklenir. Kart veya kadran alanına dokunmak İz'deki ilgili ayrıntıyı açar. Başlatma ve bitirme, mevcut saat uygulamasının onaylı kayıt akışını kullanır.

## Görünümler

| Durum | Kart | Kadran alanı |
| --- | --- | --- |
| Navigasyon | Dönüş, dönüşe mesafe, kalan yol ve tahmini varış | Son bildirilen tahmini varış |
| Kesinleşmiş kayıt | Süre, mesafe, uygun canlı nabız, adım ve varsa gecikmeli kalori | Son bildirilen kayıt süresi |
| Kayıt ve navigasyon yok | Günlük araç, yürüyüş/koşu ve varsa bisiklet özeti | Samsung Health günlük adımları |

Navigasyon günlük kaydı olmadan da görünür. Simülasyon, bağlantı kesintisi, eski GPS ve yeniden hesaplama belirtilir; eski dönüş talimatı gösterilmez. Otomatik algılamanın 500 metreye ulaşmamış adayı günlük özette küçük bir durum olarak kalır.

Canlı yön bilgisinin geçerliliği gerçek GPS ölçüm zamanına bağlıdır. Aynı noktayı yeniden aktarmak konumu yenilemez. Kadran değeri anlık yönlendirme değildir: son aktarılan varış/süre gösterilir, beş dakikayı geçen aktif veri **İz'i aç** durumuna döner. Bağlantı yokken süre ilerletilmez. Sistem güncelleme sıklığını ve gecikmeleri sınırlayabilir.

## Samsung Health ve günlük kaynaklar

Telefonda İz → Ayarlar → Samsung Health üzerinden adım, egzersiz ve mesafe okuma izinlerini verin. Samsung Health'in Health Connect paylaşımı da açık olmalıdır. Arka plan okuma izni ayrıca verilmişse günlük özet 15 dakikalık dönemsel işle güncellenebilir; Android bunu geciktirebilir. Uygulama açıkken okuma en fazla dakikada birdir. Son sağlık eşitleme zamanı gösterilir.

- **Araç:** İz'deki araba, motosiklet ve yolcu kayıtları.
- **Samsung Health:** gün boyu toplulaştırılmış adımlar; yalnız aktarılmış yürüyüş/koşu egzersizlerine ait süre ve mesafe. Samsung Health tüm günlük hareketlerin süre/mesafesini dışarı aktarmayabilir.
- **İz kayıtları:** İz'deki yürüyüş ve koşu GPS mesafesi ve süresi; Samsung değerlerine eklenmez.
- **Bisiklet:** yalnız o gün İz'de bisiklet kaydı varsa görünür.

İzin yokluğu, veri yokluğu ve kısmi veri sıfır ölçüm gibi gösterilmez. Samsung telefon ve saat adımları ayrıca toplanmaz. Adımdan süre veya mesafe tahmini yapılmaz. Gün, telefonun saat dilimindeki gece yarısıyla başlar. Gece yarısını aşan İz kayıtlarının süre ve geçerli GPS parçaları günlere bölünür; GPS boşlukları tamamlanmaz. Önceki günün özeti bugün gösterilmez.

## Gizlilik ve pil

Kartı, kadran alanını veya ayrıntıyı açmak sensör ölçümü başlatmaz. İz'in nabız/adım sensörleri yalnız kesinleşmiş kayıt sırasında mevcut bilekte olma ve izin kontrolleriyle çalışır. Canlı nabız aynı telefon, kayıt ve ölçüm oturumundan gelen, bilekte alınmış en fazla 30 saniyelik ölçümdür. Kayıt biterse, izin kalkarsa veya oturum değişirse temizlenir. Samsung Health'in kendi ölçüm ayarları değişmez.

Günlük özet önbelleği özel yedek dışı dizindedir; günlük sağlık verileri Supabase'e gönderilmez ve ZIP yedeğine eklenmez. Mevcut yolculuk sağlık okuyucusu ayrı kalır. Ortak protokol v5, eski v1–v4 ile iletişimi korur; eski sürümler yeni kart verilerini alamaz.

## Kontroller

```powershell
.\gradlew.bat :wear-protocol:test :app:testDebugUnitTest :wear:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :wear:assembleDebug :app:lintDebug :wear:lintDebug
.\gradlew.bat :wear:connectedDebugAndroidTest
```

Gerçek saat kontrolünde kartın üç görünümünü, uzun Türkçe talimatları, günlük kaynak ayrımını, kadran türlerini ve dokunarak açılan ekranları doğrulayın. Kayıt dışında sensör kapalı olmalı; kayıt açılınca bilekte olma doğrulanmalı, bitişte ölçüm sonlanmalıdır. Sistem kart/kadran seçimi ve sağlık izinleri cihazda kullanıcı onayı gerektirebilir.
