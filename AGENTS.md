# İz proje talimatları

## Tercihler

- Tarayıcı gerektiğinde önce Codex'in kendi tarayıcısını, sonra Edge'i kullan. Chrome son seçenek olsun veya kullanıcı açıkça istesin.
- Mümkün olduğunca açık kaynak projelerini kullan ve açık kaynak dünyasını destekle. Mevcut GPL lisansını, üçüncü taraf lisanslarını ve OpenStreetMap atıflarını koru.

## Her güncellemenin teslimi

Kullanıcı, İz güncellemelerinin GitHub'a gönderilmesini ve her güncellemeyle sürüm değişikliği planının yayımlanmasını açıkça istedi. Bu, rutin gönderimler için sürekli yetkidir; kullanıcı o güncellemenin gönderimini ertelerse onun talimatını uygula.

1. İşe başlarken `docs/SURUM_PLANI.md` dosyasında hedef sürümü, değişiklikleri, uyumluluğu ve gerekli doğrulamayı belirt. Uygulama sürümleri için bu dosyadaki numaralandırma kurallarını kullan.
2. Değişikliği tamamla; ilgili testleri ve gereken derleme/lint kontrollerini çalıştır. Sonuçları olduğundan başarılı gösterme.
3. `CHANGELOG.md`, `docs/SURUM_PLANI.md` ve gerekirse README'yi son uygulamaya göre güncelle. Yalnız belgeler değişiyorsa APK sürümünü artırma.
4. Gönderilecek dosya ve commitleri incele. Kişisel anahtarları, oturum bilgilerini, yerel ayarları, imzalama dosyalarını, cihaz günlüklerini, kişisel yedekleri ve ekran görüntülerini yayımlama. Kaynak deposunun mevcut kapsamına uygun olarak APK/AAB dosyalarını ekleme; kullanıcı ayrıca isterse ayrı değerlendir.
5. Yalnız görevle ilgili değişiklikleri anlamlı commitlere al. Başka bir çalışmadan kalan değişiklikleri izinsiz ekleme veya silme. GitHub CLI/OAuth oturumunu kullan; kullanıcıdan parola, token veya doğrulama kodu isteme.
6. Kullanıcı farklı bir dal veya PR akışı belirtmediyse doğrulanan değişikliği `osmanatayozturk/iz-android` deposunun `main` dalına gönder. Uzak dal ilerlemişse önce değişiklikleri uzlaştır; force push ile başkasının çalışmasını ezme.
7. Gönderimden sonra uzak commit kimliğini ve GitHub'daki sürüm planını doğrula. GitHub gönderimi ile telefon/saat kurulumunu ayrı ayrı raporla; biri diğerinin tamamlandığı anlamına gelmez. Gönderim engellenirse yerel sonucu ve somut engeli açıkça belirt.

Sürüm planı bir uygulama yol haritasıdır; zamanlanmış veya bağımsız bir arka plan görevi değildir. Kullanıcının onaylamadığı özellikleri sonraki sürüme taahhüt etme.
