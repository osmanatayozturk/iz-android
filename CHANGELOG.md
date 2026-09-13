# Değişiklik geçmişi

Telefon ve Wear OS sürümleri ayrı numaralandırılır. Plan ve teslim kuralları için [sürüm planına](docs/SURUM_PLANI.md) bakın. Buradaki tarihler değişikliklerin tamamlandığı tarihlerdir.

## Teslim süreci — 2026-09-13

- Doğrudan `main` gönderimi yerine görev dalı ve PR akışı tanımlandı. Bağımsız güvenlik incelemesi, bulgu düzeltmeleri ve PR head/base SHA'larına bağlı onay zorunlu oldu. Uygulama dağıtımı için birleşim sonrası SHA ve kaynak ağacı ayrıca doğrulanır, inceleyici son sürüm kaydını günceller.
- İnceleme eksikse veya sonucu `BLOCKED` / `INCOMPLETE` ise main birleştirmesi, canlı yayın ve APK/AAB dağıtımı durur. Açık bulgu istisnası yalnız kullanıcının sürüm, SHA ve bulgu bazında açık risk kabulüyle mümkündür.
- Public PR'da sır, kişisel veri ve kötüye kullanılabilir açık ayrıntılarının paylaşılmaması kaydedildi. Kurulu PR olay görevi ile henüz yapılandırılmamış teknik yayın kontrolleri [güvenlik yayın kuralında](docs/GUVENLIK_YAYIN_KURALI.md) ayrıldı.
- Bu değişiklik yalnız belgelerdedir; telefon 0.9.2 / code 17, saat 0.6.0 / code 10, uygulama kodu ve paketler değişmez.

## Telefon 0.9.2 - 2026-09-13

**GitHub önizleme dağıtımı:** Telefon APK'sı kişisel servis ayarları olmadan release olarak hazırlandı. README'ye kurulum bağlantısı, yapılandırma bilgisi ve örnek konumlarla alınmış iki gerçek ekran görüntüsü eklendi. İlgili lisanslar, bağımlılık kaynakları ve dosya özetleri sürüm dosyalarıyla sunulur. Bu dağıtım yeni uygulama özelliği veya sürüm numarası eklemez.

**versionCode: 17 · Uyumlu saat: 0.6.0 / code 10**

- Ana haritanın üstüne küçük yeşil logo, serif **İz** adı ve **“Küçük yollar, güzel anılar.”** sözü geri eklendi.
- Başlık, rota planlama ve aktif yolculuk sırasında gizlenir. Büyük yazı ayarında slogan satır kırabilir; ekran okuyucu marka bilgisini bir kez okur.
- Başlık yüksekliği harita düğmeleri ve görünür alan hesabına katılır. Kısa yatay ekranlarda düğme çakışmaları ve hava düğmesinin harita atfını kapatması giderildi.
- Mevcut konum, takip ve yeniden ortalama haritanın açıkta kalan bölümünü kullanır. Rota sığdırma ve kullanıcının haritada gezinmesi korunur.
- Günlük, yedek biçimi ve saat iletişimi değişmedi. 0.9.1 yenilikleri bu sürüme dahildir.

**Doğrulama:** 551 telefon birim testi, 19 Android ekran/gerçek harita testi; debug ve release derlemeleri, lint hata vermeden tamamlandı. Lint uyarıları devam ediyor. Aynı imzayla telefonda yerinde güncelleme ve yeni ana ekran doğrulandı.

Kaynak: [43b0f15](https://github.com/osmanatayozturk/iz-android/commit/43b0f15).

## Telefon 0.9.1 — 2026-09-13

**versionCode: 16 · Uyumlu saat: 0.6.0 / code 10**

- Isı haritasındaki geniş renk lekeleri, kaydedilen yollardan oluşan ince izlerle değiştirildi.
- Uzak/orta/yakın görünümde çizgiler 1/2/3 piksel; bulanıklık yok. Sabit ölçek: 1 yolculuk mavi, 2–3 turkuaz, 4–7 sarı, 8+ kırmızı.
- Yaklaşık 30 metrelik bölgelerden geçen farklı yolculuklar sayılır. Aynı yolculukta bekleme, dönüş ve tekrar noktaları sayıyı artırmaz; GPS kesintileri birleştirilmez.
- Ulaşım filtreleri, normal/tam ekran açıklaması ve veri yenilenirken kullanıcının harita konumu korunur. Hız renkleri, kayıt ve yedek biçimi değişmedi.

**Doğrulama:** 645 birim testi (telefon 551, saat 54, protokol 40), 15 Android ekran/harita testi; derleme ve lint hata vermeden tamamlandı. Telefona ayrı kurulmak yerine 0.9.2 güncellemesiyle birlikte ulaştı.

Kaynak: [28e8ade](https://github.com/osmanatayozturk/iz-android/commit/28e8ade).

## Telefon 0.9.0 — 2026-09-12

**versionCode: 15 · Uyumlu saat: 0.6.0 / code 10**

- Yolculuk havasına TomTom trafik hesabı ve Matrix v2 ile kullanıcı onaylı durak sırası önerisi eklendi.
- Araba ve motosiklette GPS hızı ile mevcut yolun desteklenen hız sınırı ayrı gösterilir; veri belirsizse sınır uydurulmaz.
- Kaydedilen yollar, yolculuğun ölçülmüş hız aralığına göre renklendirilir.
- Biriktirilen yerlerin sırası düzenlenebilir; rota seçim listesi aynı sırayı kullanır.
- Yer sıralaması için günlük ve ZIP yedek sürümü 6 oldu; eski 1–5 yedeklerinin okunması korundu.

[Ayrıntılar ve sınırlar](docs/IZ_090_YENILIKLER.md) · Kaynak: [4de11c8](https://github.com/osmanatayozturk/iz-android/commit/4de11c8).

## Telefon 0.8.2 ve saat 0.6.0 — 2026-09-12

**Telefon code 14 · Saat code 10**

- Wear OS için İz kartı ve kadran bilgi alanı eklendi; içerik navigasyon, kayıt ve günlük özet durumuna göre değişir.
- Telefon navigasyonu kayıtsız çalışırken de saate aktarılır; ortak protokol v5, eski v1–v4 iletişimini korur.
- Samsung Health günlük adımları ile İz kayıtları ayrı kaynaklar olarak sunulur; kart açmak sağlık sensörlerini başlatmaz.

[Saat kartı ve kadran ayrıntıları](docs/WATCH_SURFACES.md) · Kaynak: [194be42](https://github.com/osmanatayozturk/iz-android/commit/194be42).

Önceki değişiklikler [Git geçmişinde](https://github.com/osmanatayozturk/iz-android/commits/main/) bulunur.
