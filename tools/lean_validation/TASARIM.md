# Gidona takılı telefonla yolculuk sonrası yatış raporu

Gidon seçiliyken dik motosiklette düz–sol–sağ–düz park kalibrasyonu tamamlanır. Her adımda ilerleme ve ses veya titreşim geri bildirimi verilir. Aynı montajda iptal edilen yenileme önceki geçerli profili korur. Bağlantı değişikliği profili geçersiz kılar. Sürüş sonunda güvenilir virajlar ve bunlardan elde edilen en yüksek sol/sağ tahminleri gösterilir. Geçerli viraj bulunmazsa nedeni ve kapsama süresi gösterilir. Gidon ve gövde oturumları, kalibrasyonları ve sonuçları ayrılır. Eski gövde kayıtları ve yedekleri okunur.

Gidon QA paketinde ayrı hesap kullanır. Parkta direksiyon hareketi sahte viraj üretmemelidir. Normal pakete açılması bağımsız, zaman eşlenmiş şasi referansıyla saha doğrulamasına bağlıdır. Hem düşük hızlı hem akıcı sınıfta, doğrulama için ayrılmış sürüşlerde en az 40 referans virajı, iki yön, en az %50 kapsama, doğru yön ve raporlanan virajların en az %95’inde en fazla 3° mutlak tepe hatası gerekir.

## Araştırma dayanağı

Android sensör eksenleri telefonun fiziksel koordinat sistemine bağlıdır. Parkta dik referans düşeyi belirler. Bu projede yönlü gidon dönüşü eksen tanıma için ayrıca kullanılır. [Android sensör eksenleri](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-coords)

Telefonla gövde yatışının çevrimdışı tahmini, dinamik geçişlerde kararlı virajlara göre daha yüksek hata gösterebilir. Yayımlanan gövde montajı ölçümleri gidon kabulünün yerine geçmez. [Stanglmayr ve diğerleri, IMC 2020](https://www.ifz.de/IMC/Session8/IMC2020-Session8-IFZ_2020_TUD_Paper_Smartphones_MotorcycleDynamics.pdf)

Düşük hızlı sürüş deneylerinde direksiyon açısı ile şasi yatış açısı ayrı ölçülmüştür. Bu nedenle düşük hız için doğruluk ve kapsama bağımsız hesaplanır. [Study on Low-Speed Stability of a Motorcycle](https://www.mdpi.com/2076-3417/9/11/2278)
