# Koşu ve sağlık verisi mimarisi

İz 0.8.1'de koşu ayrı bir yolculuk türüdür. Otomatik koşu adayı, diğer otomatik kayıtlar gibi ilk 15 dakika içinde 500 metre ölçülünce kalıcılaşır. Ortalama tempo, duraklamalar dâhil toplam süreyi ölçülmüş GPS mesafesine böler. Yürüyüş ve koşu telefonun adım sensörü desteğini paylaşır; aynı yolculuğun bu iki tür arasında düzeltilmesi adımları korur.

## Telefon ve Health Connect

Telefon, Health Connect istemcisi üzerinden yalnız okuma yapar. Samsung Health kaynaklı ve cihaz türü saat olarak işaretlenmiş nabız, toplam kalori ve adım kayıtları onaylanmış yolculuk zamanlarıyla eşleştirilir. Gözlenmeyen değerler üretilmez, aralıklar oranlanmaz ve telefon, doğrudan saat ile Samsung Health adımları birbirine eklenmez.

Bağlantı kullanıcı tarafından açılır. Arka plan okuma desteği ve izni varsa dönemsel eşitleme yapılır; yoksa uygulama öndeyken eşitleme kullanılır. İlk bağlantıda erişilebilir son 30 gün taranabilir, ardından değişiklik belirteçleriyle güncellemeler alınır. Bağlantıyı kesmek yeni okumaları durdurur; sağlık kayıtlarını silmek ayrı ve açık bir işlemdir.

## Wear OS doğrudan ölçümü

Wear OS 0.5.2, kullanıcı **Otomatik ölçümü aç** tercihini etkinleştirdiğinde yalnız telefonda onaylanmış bir yolculuk sırasında ölçüm yapar. Bilekte olma doğrulandıktan sonra nabız ve adım sensörleri açılır; saat çıkarılınca veya kayıt bitince kapatılır. Saat tek başına egzersiz oturumu başlatmaz ve bağımsız GPS rotası kaydetmez.

Ölçümler önce saatin yerel kuyruğuna yazılır. Telefon veritabanına kabul ettiğini bildirmeden kuyruktan silinmez. Oturum ve sıra kimlikleri tekrarları ayıklar; bitmiş veya başka bir telefona ait oturumlara sonradan veri eklenmez. Telefon-saat aktarımı yalnız ölçüm ve gerekli yolculuk kimliklerini taşır; rota, fotoğraf ve özel günlük metni taşımaz.

## Saklama ve dışa aktarma

Günlük Room şeması ve ZIP yedek biçimi sürüm 5'tir; yedek sürümleri 1–4 okunabilir. Sağlık verisini ZIP yedeğine ekleme her dışa aktarımda varsayılan olarak kapalıdır. İzinler ve eşitleme belirteçleri yedeklenmez. Sağlık verisi OSM katkılarına, GPX dosyalarına, fotoğraflara veya grup sunucusuna eklenmez.

Emülatör testleri izin ve veri akışı kurallarını doğrulayabilir; gerçek nabız sensörü, bilekte olma algısı, Samsung Health gecikmesi, ekran kapalı çalışma ve pil etkisi fiziksel telefon ve saat üzerinde ayrıca doğrulanmalıdır.
