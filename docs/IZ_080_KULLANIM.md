# İz 0.8.1 kullanım

İz haritayla açılır. Üstte **Nereye?**, yanda **Konumum** ve **Katmanlar**, altta **Kaydet / Grup / Menü** bulunur. Hava durumu küçük bir kısayoldur. Eski yolculuk çizgileri ana haritada görünmez; Menü → Yolculuklar veya Isı haritasından incelenir.

## Navigasyon ve kayıt

1. Nereye? ile hedef seçin. Başlangıç güncel konumdur; isterseniz değiştirin. Araba, motosiklet, bisiklet, yürüyüş, koşu veya yolcu modunu seçip ara durak ekleyebilirsiniz.
2. Önizleme güzergâhı ve süreyi gösterir; kayıt başlatmaz. Rota planlayıcı açıkken de harita hareket ettirilebilir.
3. Yeni başlangıçta **Yolculuğu kaydet** açıktır. Kapatırsanız yol tarifi çalışır, günlük kaydı ve saat sağlık ölçümü başlamaz. Bu oturumda otomatik kayıt adayı da oluşturulmaz.
4. Aktif bir kayıt varken kayıtsız devam etmek için önce o kaydı açıkça durdurun. Aynı türdeki kayıt navigasyona bağlanabilir; iki ayrı günlük kaydı oluşturulmaz.

**Yol tarifini durdur** sesli yönlendirmeyi durdurur. **Kaydı durdur** günlüğü ve buna bağlı sağlık ölçümünü bitirir; açık yönlendirme veya grup paylaşımı konumu kullanmayı sürdürebilir. **Yolculuğu bitir** yönlendirme, kayıt ve kendi grup paylaşımınızı birlikte sonlandırır. Saatten bitirme de tüm yolculuğu bitirir.

Otomatik kayıt, kayıtsız navigasyon dışında mevcut 500 metre / 15 dakika kuralını kullanır. Yürüyüş ve koşuda telefon adımları için hareket izni gerekir. Saatin İz sensörleri yalnız kesinleşmiş kayıtla açılır; Samsung Health'in kendi ölçüm ayarları ayrıdır.

Yüklenmiş güzergâh ve yerel kayıt bağlantı kesilince korunur. Yeni rota, yeniden rota hesaplama, güncel hava ve grup bağlantısı internet gerektirir. Çevrimdışı bölge indirme bu sürümde yoktur. Trafik tahmini mevcut isteğe bağlı sağlayıcı ayarını kullanır: [kurulum](YOL_TARIFI_VE_TRAFIK.md).

## Yolculuk grubu

Supabase grup arka ucu kaynak yayınında etkin değildir. [Sunucu kurulumu](GROUP_SETUP.md) tamamlanınca grup özellikleri açılır; yapılandırma yokken harita, navigasyon, hava durumu ve yerel günlük çalışmaya devam eder.

- Bir rota planlayıp gruba aktarın; ortak ara duraklar ve hedef sabitlenir. Her kişi kendi konumundan, kendi ulaşım türüyle yol tarifi alır.
- Davet kodu veya QR ile katılım istenir; kurucu onaylar. Kurucu dahil en fazla 10 kişi bulunabilir. Davet 15 dakika, yolculuk grubu en fazla 24 saat geçerlidir.
- Katılmak konum paylaşımını açmaz. Gerçek bir navigasyon veya kayıt oturumunda **Paylaşımı aç** seçimi gerekir. Paylaşım açıkken diğer paylaşan üyeler de görülebilir; yeni yolculuk yeniden onay ister.
- Konum hareketliyken en sık 10, dururken 30 saniyede gönderilir. 30 saniyeden eski işaret gecikmiş gösterilir; 5 dakikadan sonra haritadan kalkar, kişi listede kalır.
- Paylaşımı kapatma, gruptan ayrılma veya yolculuğu bitirme yerel gönderimi durdurur. Kurucu grubu bitirebilir ve üyeyi çıkarabilir. Sunucu yanıtı belirsizse arayüz bunu tamamlanmış saymaz.

Canlı koordinatlar geçmiş olarak saklanmaz veya yeniden gönderim kuyruğuna alınmaz. Sunucuda görünen adlar, üyelik, ortak duraklar ve izin/zaman bilgileri bulunur. Sağlık, fotoğraf ve günlük verileri gruba yüklenmez. OSM Topluluğu mesajlaşması ayrı kalır.

## Mevcut veriler

Menüden Yolculuklar, İstatistikler, Yerler, Isı haritası, OSM Topluluğu, OSM katkıları ve Ayarlar açılır. Günlük veritabanı ve yedek biçimi değişmez. Telefon sürümü 0.8.1 / code 13; saat sürümü 0.5.2 / code 9 ile aynı iletişim biçimi korunur.
