# İz Watch 0.5.1 - Watch8 Classic sağlık verileri

## Kullanım

Telefon İz 0.8.0 / code 12 ve saat İz Watch 0.5.1 / code 8 birlikte kullanılır. Saatte İz içindeki **Otomatik ölçümü aç** seçeneği ilk kez etkinleştirilir. Sensör, fiziksel aktivite ve ayrı ekranda **her zaman / arka plan sensörü** izinleri gerekir. Bildirim izni de açılmalıdır. Saatte sessiz bir hazır olma bildirimi görünür; bu bildirim sürekli ölçüm yapıldığı anlamına gelmez. Bildirimdeki **Kapat** veya uygulamadaki **Otomatik ölçümü kapat** seçeneği otomatik ölçümü durdurur.

Bu tercih açıkken kayıt yoksa İz'in nabız, adım ve bilekte olma sensörlerinin tümü kapalıdır; **Kayıt yok · Sensörler kapalı** gösterilir. Sessiz bildirim ve telefon bağlantısını bekleyen servis sonraki yolculuk için hazır kalır. Elle başlatılan veya otomatik olarak kesinleşen kayıt için önce **Bilekte olma doğrulanıyor** gösterilir; yeni bir bilekte olma bilgisi alındığında nabız ve adım ölçümü açılır ve **Yolculuk ölçülüyor** gösterilir. Araba, motosiklet, bisiklet, yürüyüş, koşu ve yolcu aynı koşulları kullanır. Geçici otomatik başlangıç adayında ölçüm başlamaz.

Saat yolculuk sırasında çıkarılırsa nabız ve adım ölçümü durur; tekrar takılmasını algılamak için yalnız bilekte olma sensörü açık kalır. Kayıt bitişi saate ulaştığında üç sensör de kapatılır ve canlı değerler temizlenir. Sonraki yolculuk yeni bilekte olma doğrulaması ve adım başlangıcı gerektirir. Aynı yolculuğun bağlantı yenilemesi adımları sıfırlamaz. Desteklenmeyen sensör yerine değer uydurulmaz. Bağlantı kopukken telefondaki bitiş hemen öğrenilemeyebilir: son telefon doğrulamasından 15 dakika sonra ölçüm yetkisi biter; dinleyiciler bir sonraki servis kontrolünde bırakılır (normalde 5 saniyelik döngü; işletim sistemi çalışmayı geciktirebilir). Bu sınırdan itibaren yeni ölçüm kabul edilmez.

Bu ayarlar yalnız İz'in sensör kullanımını yönetir. Samsung Health'in kendi sürekli ölçüm ayarları ve sonradan Health Connect üzerinden okunan kayıtları bağımsızdır.

Tercih normal saat yeniden başlatma ve uygulama güncellemesinde korunur. Sistem izin verirse sağlık hizmeti geri açılır. İşletim sistemi başlatmayı engellerse veya uygulama zorla kapatılmışsa saatte İz'i açmak gerekir. Kullanıcının kapattığı otomatik ölçüm kendiliğinden açılmaz. Saatin üretici güç yönetimi altında kesintisiz çalışma garanti edilmez; gerçek Watch8 Classic üzerinde ekran kapalı ve bağlantı kesilmesi testleri gereklidir.

## Kaynaklar ve zamanlar

İzin adları saatin Android sürümüne göre seçilir. Wear OS 6 / Android API 36 ve sonrasında açıkça `android.permission.health.READ_HEART_RATE` ve ayrı istekte `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` kullanılır. API 33–35'te `BODY_SENSORS` ile `BODY_SENSORS_BACKGROUND`, daha eski sürümlerde `BODY_SENSORS` kullanılır. Fiziksel aktivite izni bütün desteklenen sürümlerde gereklidir. Eski BODY izinleri manifestte API 35 ile sınırlıdır; uygulama güncellemesi sonrası API 36'da bu eski izinlerin kaldırılmış olması, verilmiş modern sağlık izinlerini geçersiz saymaz. Hedef SDK 35 korunur.

**İz · Saat ölçümleri**, SensorManager üzerinden gerçek nabız ve adım sayacı değişimlerini içerir. Yeni yolculukta, saat çıkarılıp tekrar takıldığında, sensör yeniden kaydedildiğinde veya önemli telefon saat düzeltmesinde yeni adım başlangıcı alınır. Bir saniyeden sık nabız örnekleri kaydedilmez. Temas olmayan/güvenilmez/sıfır nabız ölçümleri alınmaz. Son 30 saniyede alınmayan nabız canlı diye gösterilmez. Ölçüm boşlukları doldurulmaz.

**Samsung Health · Saat kaynaklı ölçümler**, Health Connect üzerinden daha sonra okunur. İz açıkken 60 saniyede bir; izin verilmiş arka plan eşitlemesiyle işletim sisteminin uygun gördüğü aralıkta kontrol edilir. Samsung Health paylaşımı gecikebilir. Yalnız Samsung Health kaynaklı ve cihazı saat olarak belirtilmiş kayıtlar kabul edilir. Cihazı belirtilmeyen veya telefon kaynaklı kayıtlar tanı sayılarında ayrılır. Aralık ölçümleri yalnız tamamı yolculuğun içinde ise eklenir.

Telefon adımları, doğrudan saat adımları ve Samsung Health adımları **toplanmaz**. Kalori doğrudan sensörden hesaplanmaz. Samsung Health'in sağladığı toplam enerji (dinlenme enerjisi dahil) ayrı gösterilir. İz Samsung Health egzersizini başlatmaz veya devralmaz.

## Aktarım ve saklama

Saat-telefon v4 Data Layer aktarımı uygulama kimliğine, telefon düğümüne ve saat kurulum kimliğine bağlıdır. Telefon doğrulanmış yolculuk için yeni rastgele oturum kimliği verir. Zaman, telefon yanıtının saat üzerindeki monoton alınma anına bağlanır; 5 saniyeden yavaş el sıkışma kabul edilmez. Belirgin telefon saati değişimi yeni oturum/başlangıç gerektirir.

Ölçümler önce saatin özel SQLite kuyruğuna yazılır. Mesaj gönderiminin başarılı görünmesi kaydı silmez: telefon veritabanı işlemi tamamlandıktan sonra ACK gerekir. Aynı sıra numarası aynı veriyle tekrar gelebilir; farklı veri reddedilir. Hafif ileri zaman farkında ACK ertelenir. Kesin olarak bitmiş yolculuk dışında kalan örnekler eklenmez. Bekleyen veri 7 gün ve 100.000 örnekle sınırlıdır; dolan/eski veriler kayıpta boşluk yaratabilir.

Telefon değişiminde **Telefon sağlık bağlantısını sıfırla** açıklamasını onaylamak bekleyen saat kuyruğunu siler ve yeni bağlantı kurar; telefona ulaşmış kayıtları silmez. Doğrudan ölçümler Samsung Health tablolarından ayrıdır. Sağlık verisi temizleme eski kuyrukların silinen veriyi yeniden oluşturmasına izin vermez. Yedekten yüklenen oturumlar yeni yükleme kabul etmez. Sağlık ölçümleri yalnız sağlık dahil yedekleme seçildiğinde yedeklenir.

## Gerçek cihaz doğrulaması

Watch8 Classic ile ilk izinler, arka plan sensörü izni, kalıcı bildirim, bilekte/çıkarılmış durum, ekran kapalı sensör akışı, Samsung Health ile eş zamanlı kullanım, otomatik kesinleşen yolculuk, uçak modu/yeniden bağlanma, yeniden başlatma ve kullanıcı kapatma senaryoları denenmelidir. Emülatör testleri gerçek nabız/temas sensörü veya Samsung Health aktarımını doğrulamaz.

## Platform kaynakları

- [Android health foreground service koşulları](https://developer.android.com/develop/background-work/services/fgs/service-types#health)
- [Wear OS 6 izin uyumluluğu](https://developer.android.com/training/wearables/versions/6/changes)
- [Samsung: ekran kapalıyken SensorManager nabız ölçümü](https://developer.samsung.com/galaxy-watch/blog/en/2026/04/23/continuous-heart-rate-tracking-on-galaxy-watch-even-with-the-screen-off)
- [Health Connect eşitleme](https://developer.android.com/health-and-fitness/health-connect/sync-data)
