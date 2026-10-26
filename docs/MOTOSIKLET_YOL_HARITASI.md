# Motosiklet, telefon ve saat yol haritası

**Durum:** 26 Ekim 2026 tarihli araştırmaya dayalı öneri. Bu belge ürün planı onayı, geliştirme ataması veya sürüm taahhüdü değildir. Çevrimdışı haritalar kapsam dışıdır.

## Önerilen sıra

1. **Pil ve bağlantı temeli:** Sürüşte gerekli navigasyon/kayıt işlevlerini korurken gereksiz tekrar hesaplarını ve telefon-saat aktarımını azalt. Ekranın açık kalma tercihi değişmeden kalsın. Tasarruf yüzdesi ancak benzer cihaz ve kullanım koşullarında ölçülürse raporlansın.
2. **Motosiklet sürüş yüzeyi:** Mevcut haritada büyük dönüş bilgisi, hız, biliniyorsa hız sınırı, kalan süre ve telefon/saat pilini sade biçimde sunmayı değerlendir. Planlama durumu ve bitirme teyitleri korunmalı; ekran eldivenle kusursuz kullanım veya güvenlik garantisi vermez.
3. **Molalı planlama:** Kullanıcı duraklara mola süresi ekleyebilsin; sonraki varış zamanı ve rota havası bu süreyi hesaba katsın. Sürüş süresi ve mola süresi ayrı gösterilsin.
4. **Motosiklet yol tercihleri:** Kaplamasız yoldan kaçınma veya yüzeyi bilinmeyen kesimleri gösterme seçeneğini sağlayıcı desteği doğrulandıktan sonra değerlendir. Eksik yol etiketi asfalt veya güvenli yol anlamına gelmez.
5. **Daha sonraki gezi fikirleri:** Desteklenen virajlı rota seçimi, rota havasında rüzgar/gün batımı bağlamı, bakım-yakıt günlüğü ve saatten GPX takibi ayrı önerilerdir; ilk teslimin parçası değildir.

## Sınırlar

İlk aşamada araç menzili/şarj durağı optimizasyonu, çevrimdışı harita, otomatik acil mesaj ve yeni bir motosiklet rota motoru önerilmiyor. Mevcut sağlayıcılarda motosiklet modu bulunması yüzey verisinin tamlığını veya yolun güvenli olduğunu kanıtlamaz. Valhalla/TomTom desteği ve sürüm uyumu her özellik için ayrıca doğrulanmalıdır. OSM verisi eksik veya eski olabilir; atıf ve lisans koşulları korunmalıdır.

Bu fikirlerin sırası ve kapsamı koordinatör/kullanıcı kararıyla değişebilir. Hiçbir öneri uygulandı, ölçüldü veya yayımlandı anlamına gelmez.

## Araştırma kaynakları

- [Wear OS güç ve pil rehberi](https://developer.android.com/training/wearables/apps/power)
- [Android konum ve pil kullanımı](https://developer.android.com/develop/sensors-and-location/location/battery)
- [OpenStreetMap `surface` etiketi](https://wiki.openstreetmap.org/wiki/Key:surface)
- [Valhalla rota API'si](https://valhalla.github.io/valhalla/api/route/api-reference/)
- [TomTom Calculate Route](https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/calculate-route)
- [Open-Meteo hava verileri](https://open-meteo.com/en/docs)
