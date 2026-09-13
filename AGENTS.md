# İz proje talimatları

## Tercihler

- Tarayıcı gerektiğinde önce Codex'in kendi tarayıcısını, sonra Edge'i kullan. Chrome son seçenek olsun veya kullanıcı açıkça istesin.
- Mümkün olduğunca açık kaynak projelerini kullan ve açık kaynak dünyasını destekle. Mevcut GPL lisansını, üçüncü taraf lisanslarını ve OpenStreetMap atıflarını koru.

## Güvenlik incelemesinin gizliliği

Güvenlik ajanı tam raporları, bulgu listelerini, tarama kanıtlarını, sonuçları ve durum geçmişini yalnız kullanıcıyla özel olarak paylaşır. Bunlar public PR, issue, yorum, check, log, site veya kaynak deposuna konmaz; güvenlik sonucu özeti de yayımlanmaz. Bu genel talimat belgesinin yayımlanması güvenlik raporu paylaşımı değildir.

Uygulama ekiplerine yalnız kendi uygulamaları için gereken en az düzeltme bilgisi, doğrulanmış özel ve yetkili kanaldan iletilir. Varsa uygulamanın koordinatör ajanıyla, yoksa ilgili geliştiriciyle iletişim kurulur. Uygun özel kanal yoksa kullanıcıya bildirilir; public kanala geçilmez. Bağımsız inceleyici ve geliştirici yalnız görevlerini yapmaları için gerekli bağlama erişir; bu erişim tam raporun veya diğer projelerin bulgularının paylaşılmasına izin vermez.

## Her güncellemenin teslimi

Kullanıcı, İz güncellemelerinin GitHub'a gönderilmesini ve her güncellemeyle sürüm değişikliği planının yayımlanmasını açıkça istedi. Bu, görev dalı ve PR üzerinden rutin gönderimler için sürekli yetkidir; kullanıcı o güncellemenin gönderimini ertelerse onun talimatını uygula. Kullanıcının zorunlu bağımsız güvenlik incelemesi talimatı, önceki doğrudan `main` gönderim akışının yerini alır. Ayrıntılar [güvenlik yayın kuralında](docs/GUVENLIK_YAYIN_KURALI.md).

1. İşe başlarken `docs/SURUM_PLANI.md` dosyasında hedef sürümü, değişiklikleri, uyumluluğu ve gerekli doğrulamayı belirt. Uygulama sürümleri için bu dosyadaki numaralandırma kurallarını kullan.
2. Değişikliği tamamla; ilgili testleri ve gereken derleme/lint kontrollerini çalıştır. Sonuçları olduğundan başarılı gösterme.
3. `CHANGELOG.md`, `docs/SURUM_PLANI.md` ve gerekirse README'yi son uygulamaya göre güncelle. Yalnız belgeler değişiyorsa APK sürümünü artırma.
4. Gönderilecek dosya ve commitleri incele. Kişisel anahtarları, oturum bilgilerini, yerel ayarları, imzalama dosyalarını, cihaz günlüklerini, kişisel yedekleri ve ekran görüntülerini yayımlama. Kaynak deposunun mevcut kapsamına uygun olarak APK/AAB dosyalarını ekleme; kullanıcı ayrıca isterse ayrı değerlendir.
5. Yalnız görevle ilgili değişiklikleri anlamlı commitlere al. Başka bir çalışmadan kalan değişiklikleri izinsiz ekleme veya silme. GitHub CLI/OAuth oturumunu kullan; kullanıcıdan parola, token veya doğrulama kodu isteme.
6. Değişikliği görev dalında hazırla ve `osmanatayozturk/iz-android` deposunda PR aç. Doğrudan `main` gönderimi yapma. Public PR açıklamasına veya yorumlarına sır, kişisel veri, güvenlik raporu, bulgu listesi, tarama kanıtı/sonucu ya da güvenlik durum özeti ekleme. Düzeltme koordinasyonunu yukarıdaki özel kanal kuralıyla yürüt.
7. Geliştirmeye katılmamış bağımsız bir inceleyici ajan kullan. İnceleme, düzeltme ve yeniden inceleme döngüsünü tamamla. `main` birleştirmesi için incelenen PR head SHA'sı, hedef base SHA'sı ve kapsam aynı kalmalı; sonuç `PASS` veya açık risk kabulüyle `PASS_WITH_ACCEPTED_RISK` olmalıdır. İnceleme yoksa, yarımsa veya `BLOCKED` / `INCOMPLETE` ise birleştirmeyi ve yayını durdur. Yeni commit, hedef dalın ilerlemesi, rebase, çatışma çözümü veya kaynak girdisi değişikliği yeniden inceleme gerektirir. Merge/squash sonrasında yeni SHA ve kaynak ağacı doğrulanıp bağımsız inceleyici son sürüm kaydını güncellemeden canlı yayın veya APK/AAB dağıtımı yapma.
8. Tüm doğrulanmış açıkları kapat. İstisna ancak kullanıcının hedef sürüm, tam SHA ve bulguları belirterek açık risk kabulü vermesidir; inceleyici kendi muafiyetini veremez. Genel “yayınla” talimatı güvenlik kontrolünü atlama veya risk kabulü sayılmaz. Kabul edilen riskler dahil son durum bağımsız inceleme kaydında gösterilir; incelenmemiş kapsam için onay verilmez.
9. Birleştirme öncesi incelenmiş head/base SHA'larını ve kapsamı, birleştirme sonrası oluşan SHA ve kaynak ağacını doğrula. Dağıtımın kaynağını inceleyicinin son sürüm kaydındaki tam SHA ile karşılaştır; GitHub'daki sürüm planını doğrula. Uzak dal ilerlemişse değişiklikleri görev dalında uzlaştır ve yeniden incelet; force push ile başkasının çalışmasını ezme. GitHub gönderimi ile telefon/saat kurulumunu ayrı ayrı raporla; biri diğerinin tamamlandığı anlamına gelmez. Gönderim engellenirse yerel sonucu ve somut engeli açıkça belirt.

Sürüm planı bir uygulama yol haritasıdır; kendiliğinden zamanlı veya arka plan görevi oluşturmaz. Ayrı kurulmuş **İz kod güvenlik incelemesi** görevi PR açılışını ve yeni commitleri kullanıcıya özel Güvenlik sohbetindeki inceleme için takip eder; zamanlı görev yoktur. Görev public güvenlik yorumu üretmez ve tek başına teknik yayın kilidi değildir. Zorunlu GitHub kontrolleri, dal koruması ve dağıtım kimlik bilgilerinin kısıtlanması henüz yapılandırılmadı. Bu durum, ajanın yukarıdaki yayın kuralını atlamasına izin vermez. Kullanıcının onaylamadığı özellikleri sonraki sürüme taahhüt etme.
