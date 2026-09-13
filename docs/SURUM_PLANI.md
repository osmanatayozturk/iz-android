# İz sürüm değişikliği planı

Bu dosya her İz güncellemesiyle güncellenir ve kaynak koduyla birlikte GitHub'a gönderilir. Tamamlanan değişiklikler ayrıca [değişiklik geçmişine](../CHANGELOG.md) yazılır.

## Güncel sürümler

| Bileşen | versionName | versionCode | Durum |
|---|---|---|---|
| Telefon | 0.9.2 | 17 | Derlendi, test edildi ve telefona yerinde kuruldu |
| Wear OS | 0.6.0 | 10 | Telefon 0.9.2 ile iletişim biçimi değişmedi |
| Ortak iletişim | v5 | — | Eski v1–v4 desteği korunuyor |

## Tamamlanan plan: telefon 0.9.1 → 0.9.2

**Amaç:** Ana haritada İz'in adını, küçük logosunu ve kısa sözünü geri getirmek.

- 36 dp mevcut yeşil logo, İz adı ve “Küçük yollar, güzel anılar.” sözü, Nereye? kartının üstüne yerleştirildi.
- Marka alanı boşta görünür; rota planlayıcı veya yolculuk açıkken gizlenir. Ayrı bir bekleme ekranı eklenmedi.
- Ölçülen başlık yüksekliği harita kontrolleri ve konumun görünür alanına uygulandı. Kısa yatay ekranda düğmeler ve harita atfı için yerleşim düzeltildi.
- Büyük yazı, erişilebilirlik, konuma dönme, rota sığdırma, haritada gezinme ve yolculuk geçişleri doğrulandı.
- Telefon sürümü 0.9.2 / code 17 oldu. Saat sürümü, günlük veritabanı, yedek ve iletişim biçimi değiştirilmedi.

**Kabul sonucu:** 551 telefon birim testi ve 19 Android ekran/harita testi geçti; debug/release derlemeleri ve lint hata vermedi. Lint uyarıları sürüyor. Mevcut imzayla telefona güncelleme ve yeni ana ekran doğrulandı. 0.9.1'in ince yol izleri bu pakete dahildir.

## 2026-09-13: APK ve uygulama görsellerinin yayını

- **Amaç:** Telefon 0.9.2 / code 17 için GitHub Releases üzerinden indirilebilir paket ve README'de gerçek uygulama görselleri sunmak.
- **Paket:** Mevcut kaynak kodundan, kişisel servis yapılandırmaları ve kullanıcı verileri eklenmeden oluşturulur. Uygulama kimliği, günlük/yedek biçimi ve saat iletişimi değişmez; saat sürümü 0.6.0 / code 10 kalır.
- **Görseller:** Emülatörde örnek konumlarla alınır; gerçek yolculuk, sağlık, hesap ve cihaz verileri yayımlanmaz. Harita kaynak gösterimi korunur.
- **Kabul:** Paket sürümü, imza, hata ayıklama durumu ve içeriği incelenir; emülatörde açılışı doğrulanır. Yayın varlıkları ve görseller GitHub'dan kontrol edilir. İndirme, kurulum ve yapılandırma açıklamaları güncellenir.
- **Sürüm etkisi:** Bu teslim mevcut 0.9.2'nin dağıtımıdır; yeni uygulama özelliği veya sürüm numarası üretmez.

**Yerel doğrulama:** 551 release birim testi geçti; release derlemesi başarılı, lint 0 hata / 90 uyarı ile tamamlandı. Minify edilmiş release APK, mevcut geliştirme sertifikasıyla imzalandı; hata ayıklama kapalıdır. Kişisel sağlayıcı değerleri ve veri dosyaları pakette bulunmadı. Emülatörde yerinde kurulum, ana harita, konuma dönme ve örnek Valhalla yürüyüş rotası (2,6 km / 34 dk) doğrulandı; APK kurulumundan sonraki çökme kaydı boş kaldı. İki görselde örnek konumlar ve görünür OpenStreetMap atfı kullanıldı. [GitHub sürümü ve indirme dosyaları](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.9.2).

## Sonraki uygulama güncellemesinin numarası

Sonraki özellik kapsamı henüz seçilmedi. Aşağıdaki numaralar değişiklik türüne göre alternatiflerdir; iki ayrı sürüm taahhüdü değildir.

| Değişiklik | Telefon için sonraki sürüm | Saat de değişirse sonraki saat sürümü |
|---|---|---|
| Hata düzeltmesi veya mevcut ekranın küçük iyileştirmesi | 0.9.3 / code 18 | 0.6.1 / code 11 |
| Yeni kullanıcı özelliği veya yeni modül | 0.10.0 / code 18 | 0.7.0 / code 11 |
| Yalnız dokümantasyon veya teslim süreci | 0.9.2 / code 17 korunur | 0.6.0 / code 10 korunur |

- Numara, çalışmaya başlarken gerçek kapsam ve depodaki en son sürüm esas alınarak kesinleştirilir. Yayımlanan/teslim edilen yeni APK için ilgili modülün `versionCode` değeri monoton artar; art arda test derlemeleri ayrı sürüm sayılmaz.
- Telefon ve saat bağımsız numaralandırılır. Değişmeyen modülün sürümü artırılmaz. Değerlerin kaynağı `app/build.gradle.kts` ve `wear/build.gradle.kts` dosyalarıdır.
- Veri, yedek, uygulama kimliği veya iletişimde uyumsuz değişiklik gerekiyorsa başlamadan önce somut geçiş planı eklenir. 1.0.0 için ayrıca bir kararlı sürüm kapsamı belirlenir.

## Her güncellemede uygulanacak akış

1. Bu plandaki hedef sürüm ve amacı yeni kapsamla güncelle; kullanıcıya yansıyan değişiklikleri, veri/saat uyumluluğunu ve kabul kontrollerini yaz.
2. Uygulamayı geliştir ve ilgili kontrolleri çalıştır. Sonuçları plana ve değişiklik geçmişine işle; belge değişikliğinde APK üretmek gerekmez.
3. Gönderilecek commitleri incele; yalnız görevle ilgili kaynak ve gözden geçirilmiş belgeleri dahil et. Kişisel anahtarlar, imzalama malzemesi, yerel ayarlar, cihaz günlükleri/yedekleri ve kişisel APK'lar kaynak deposuna girmez.
4. Kod, README, bu plan ve değişiklik geçmişini mevcut GitHub CLI/OAuth yetkisiyle `osmanatayozturk/iz-android` deposuna gönder. Kullanıcı farklı bir akış istemedikçe hedef `main` dalıdır.
5. Uzak commit kimliğini ve GitHub'daki plan dosyasını kontrol et; teslimde ilgili bağlantıyı ver. Fiziksel cihaz kurulumu yapıldıysa ayrıca belirt.

Bu talimatlar [AGENTS.md](../AGENTS.md) içinde de kayıtlıdır. Zamanlanmış bir görev kurulmaz; gönderim her geliştirme çalışmasının tamamlanma adımıdır.
