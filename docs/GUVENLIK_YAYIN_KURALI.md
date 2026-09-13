# İz güvenlik incelemesi ve yayın kuralı

Bu belge, kullanıcının 13 Eylül 2026 tarihli zorunlu bağımsız güvenlik incelemesi talimatını İz teslim sürecine uygular. [Proje talimatları](../AGENTS.md) ve [sürüm planı](SURUM_PLANI.md) ile birlikte uygulanır. Önceki doğrudan `main` gönderim alışkanlığı bu kuralla değiştirilmiştir.

## Temel koşul

Bir değişiklik, onu geliştirmemiş bağımsız bir inceleyici tarafından denetlenmeden; bulgular giderilip son commit yeniden incelenmeden `main` dalına birleştirilmez, canlıya yayımlanmaz veya APK/AAB olarak dağıtılmaz. Bağımsız inceleyici ajan, geliştiricinin kendi kontrolüne ek olarak kullanılır. İnceleme yapılamıyorsa süreç durur; geliştirici kendisine onay veremez.

Bu koşul kaynak, bağımlılık, yapılandırma ve teslim süreci değişiklikleri için geçerlidir. Yalnız belge değişikliği için APK üretmek veya uygulama sürümünü artırmak gerekmez; güvenlik yönergelerinin tutarlılığı ve yayımlanacak içerik yine incelenir.

13 Eylül 2026 tarihli ilk mevcut uygulama incelemesi bir yayın onayı değildir. Bu doküman güncellemesine verilen onay yalnız incelenen belge değişikliklerini kapsar; uygulama koduna veya daha önce onaylanmamış sürümlere güvenlik onayı vermez. Uygulamanın ayrı inceleme sonucu ve kapanmamış bulguları kendi kapsamında geçerliliğini korur.

## Görev dalından teslimata

1. Geliştirici görev dalını oluşturur, sürüm planını günceller ve değişikliği tamamlar. Gönderilecek diff, sır ve kişisel veri açısından kontrol edilir. Yalnız görevle ilgili dosyalar commitlenir.
2. Görev dalı gönderilir ve PR açılır. PR metni değişikliğin amacını, güvenli kapsam özetini ve test durumunu içerir. Sır değerleri, kişisel kayıtlar, ayrıntılı saldırı örnekleri ve henüz açık bir zafiyetin kötüye kullanılmasını kolaylaştıran bilgiler public PR'a veya kaynak deposuna konmaz. Ayrıntılı güvenlik değerlendirmesi Güvenlik sohbetinde ya da kullanıcı tarafından belirlenmiş uygun özel kanalda tutulur.
3. Geliştirmeye katılmamış inceleyici son commit'i ve gerekli çevre kaynaklarını bağımsız olarak değerlendirir. İnceleme isteğinde hedef sürüm, tam 40 karakterli PR head SHA'sı, hedef base SHA'sı, varsa aday birleşim kaynak ağacı kimliği, kapsam ve mevcut test kanıtları belirtilir. Bir ajanı çağırmak veya otomasyon yorumu almak incelemenin tamamlandığı anlamına gelmez.
4. Geliştirici doğrulanmış bulguları düzeltir ve yeni commitleri gönderir. İnceleyici son SHA'da düzeltmeleri, değişikliklerin getirdiği yeni riskleri ve açık kalan kapsamı yeniden değerlendirir. Eksik kanıtı başarılı test gibi yazmaz.
5. Birleştirme öncesinde PR head SHA'sı, hedef base SHA'sı ve kapsamın bağımsız `PASS` veya `PASS_WITH_ACCEPTED_RISK` kaydıyla aynı olduğu doğrulanır. Bu girdilerden biri değişmişse yeniden inceleme yapılır. Yeni birleşim içeriğine eski onay taşınmaz.
6. Merge/squash sonrasında oluşan tam SHA ve kaynak ağacı ayrıca doğrulanır. Bağımsız inceleyici bu karşılaştırma sonucuyla son sürüm kaydını günceller; bu adım tamamlanmadan canlı yayın veya APK/AAB dağıtımı yapılmaz. Kaynak SHA'sı, paket sürümü ve APK/AAB dosya özeti teslim kaydına alınır; paket, incelenen kaynak ve doğrulanan derleme girdileriyle ilişkilendirilir. İmza veya paket içeriği değişirse ilgili kontroller yenilenir.
7. Uzak commit ve dağıtım varlıkları son onay kaydıyla karşılaştırılır. Kaynak gönderimi, Release yayını ve cihaz kurulumu ayrı sonuçlar olarak raporlanır.

## Onay incelenen commit ve sürüme bağlıdır

PR birleştirme onayı incelenen head SHA'sı, hedef base SHA'sı ve kapsam için geçerlidir; varsa aday birleşimin kaynak ağacı kimliği de kaydedilir. Yeni commit, hedef dalın ilerlemesi, rebase, çatışma çözümü, bağımlılık veya başka bir kaynak girdisi değişikliği yeniden bağımsız inceleme gerektirir. Genel bir dal adı veya “en son sürüm” ifadesi onay kimliği yerine kullanılamaz.

Normal GitHub merge/squash işlemi, incelenen kaynak içeriği değişmeden yeni bir commit SHA'sı üretebilir. Bu yeni SHA'nın önceden bilinmesi şart değildir. Birleştirme öncesi head/base/kapsam eşliği; birleştirme sonrası oluşan SHA ve kaynak ağacının incelenen birleşim içeriğiyle eşliği doğrulanır. İnceleyici son sürüm kaydını yeni SHA ile tamamlar. İçerik veya derleme girdileri farklıysa bu kayıt onayla kapatılamaz; değişen kapsam yeniden incelenir.

APK/AAB veya canlı yayının kaynağı, bu son kayıttaki **aynı tam commit SHA'sı ve sürüm** olmalıdır. Birleştirme sonrası doğrulama ve bağımsız kayıt tamamlanmadan uygulama dağıtılmaz. “Değişiklik küçük” ya da “yalnız yeniden derledim” açıklaması kimlik ve kapsam karşılaştırmasını kaldırmaz.

## İnceleme sonucu ve risk kabulü

| Sonuç | Anlamı | Teslim kararı |
|---|---|---|
| `PASS` | Belirtilen head/base SHA'ları ve gerekli kapsam incelendi; doğrulanmış açıklar kapandı. | Aynı girdilerle birleştirme yapılabilir; dağıtım için birleşim sonrası bağımsız son sürüm kaydı da tamamlanır. |
| `PASS_WITH_ACCEPTED_RISK` | Gerekli kapsam incelendi; kalan doğrulanmış bulgular için kullanıcı aşağıdaki koşullarla açık risk kabulü verdi. | Yalnız kabulün kapsadığı commit/sürüm ve sınırlar içinde; birleşim sonrası son sürüm doğrulamasıyla teslim yapılabilir. |
| `BLOCKED` | Kapanmamış, kullanıcı tarafından açıkça kabul edilmemiş doğrulanmış açık veya başarısız zorunlu kontrol var. | Main birleştirmesi, canlı yayın ve APK/AAB dağıtımı yapılmaz. |
| `INCOMPLETE` | Kaynak, test, ortam veya erişim kanıtı eksik; gerekli kapsam hakkında sonuca varılamıyor. | Main birleştirmesi, canlı yayın ve APK/AAB dağıtımı yapılmaz. |
| İnceleme yok / sürüyor | Son SHA için tamamlanmış bağımsız sonuç bulunmuyor. | Main birleştirmesi, canlı yayın ve APK/AAB dağıtımı yapılmaz. |

Doğrulanmış açıkların kapanması varsayılan koşuldur; düşük önem derecesi kendiliğinden muafiyet oluşturmaz. İstisna için kullanıcıya somut ve gözden geçirilebilir risk sunulur. Kullanıcının açık kabulü hedef sürümü, tam SHA'yı, kabul edilen bulgu kimliklerini, etkilerini ve varsa sınırlarını belirtmelidir. İnceleyici bu kabulü kayda geçirir; kendi kararıyla kullanıcı adına risk kabul edemez.

Genel bir “yayınla”, “devam et” veya toplu teslim talimatı güvenlik incelemesini atlama kabulü değildir. Risk kabulü incelenmemiş kapsamı incelenmiş yapmaz; belirsizliği gizleyen `PASS` veya `PASS_WITH_ACCEPTED_RISK` verilemez. Kaynak girdileri değişen yeni bir commit için önceki risk kabulü otomatik taşınmaz. Merge/squash yalnız commit kimliğini değiştiriyorsa inceleyici, kabul edilen bulguların ve kapsamın aynı kaldığını doğrulayarak son sürüm kaydında yeni SHA ile ilişkilendirir; kabul kapsamını genişletemez.

İnceleme kaydı en az hedef sürümü, PR head/base SHA'larını, varsa aday birleşim ağacı kimliğini, kapsamı, inceleyiciyi, doğrulanmış/olası bulguları, uygulanan düzeltmeleri, test kanıtını, taranmamış alanları, açık risk kabulünü ve son kararı içerir. Birleşim sonrası yeni SHA, kaynak ağacı karşılaştırması ve dağıtım kaydı eklenir. Public PR'da yalnız hassas ayrıntı içermeyen sonuç özeti paylaşılır.

## Kurulu olay görevi ve henüz yapılmayan teknik kontroller

**İz kod güvenlik incelemesi** adlı görev kurulmuştur; İz reposunda PR açılışını ve yeni commitleri takip ederek bu Güvenlik sohbetindeki incelemeyi tetikler. Bu PR olaylarına bağlı görevdir; zamanlı görev kurulmamıştır. Sürüm planındaki önceki “zamanlanmış görev kurulmaz” ifadesi, sürüm planının kendisinin görev üretmediğini anlatır; kurulmuş PR olay görevini veya kullanıcının ileride vereceği ayrı görev talimatlarını yasaklamaz.

**Bu doküman ve otomasyon yorumu teknik yayın kilidi değildir.** 13 Eylül 2026 tarihinde incelenen `60d1ea73920bf4db3a4c9a09f1fc9870f80a79f3` kaynak durumunda `main` dalı korumalı değildi (`protected=false`) ve depoda CI workflow'u yoktu. Bu belge değişikliği bunları kurmaz; dış ortamda doğrulanmamış bir korumanın varlığı da varsayılmaz.

Aşağıdaki teknik işler henüz yapılandırılmadı:

- Son incelenen SHA'ya bağlı zorunlu güvenlik durum kontrolü ve tamamlanmamış/başarısız kontrolde birleştirmeyi engelleyen dal koruması veya repository ruleset.
- Yeni commit sonrasında eski onayı geçersiz kılan ve bağımsız inceleme koşulunu uygulayan GitHub ayarları.
- APK/AAB derleme ve dağıtımında onaylı SHA, bağımlılık kaydı, test sonucu, imza ve dosya özetini ilişkilendiren zorunlu CI/release kontrolleri.
- Yayın ortamı izinleri, dağıtım kimlik bilgileri ve imzalama malzemesinin yalnız onaylı iş akışı tarafından kullanılmasını sağlayan erişim kısıtları.

Bu eksikler tamamlanıp doğrulanıncaya kadar her ajan yayın kuralını kendi eylemlerinde uygular ve eksik incelemede durur. Görevin çalışıyor olması, bir PR yorumu, testlerin geçmesi veya branch adı tek başına yayın izni sayılmaz. Teknik korumalar daha sonra kurulduğunda kapsamı ve gerçek doğrulama kanıtı bu bölümde güncellenir; kurulmadan “aktif” veya “yayını engelliyor” denmez.
