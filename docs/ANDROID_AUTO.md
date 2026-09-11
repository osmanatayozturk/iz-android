# İz Android Auto

İz 0.8.1, telefondaki günlüğe bağlı bir Android Auto araç arayüzü içerir. Hedef seçmek zorunlu değildir. Araç ekranı aktif yolculuğun konumunu, kaydedilmiş izini ve istatistiklerini gösterir. Kayıt yoksa **Sürüşe başla** ile araba, motosiklet veya yolcu kaydı açılabilir. Bağlanmak tek başına kayıt başlatmaz.

## Kullanım

- **Hedefsiz sürüş:** Mevcut kayıt otomatik gösterilir. Geçmiş yolculuklar haritaya eklenmez. Ana ekranda mesafe, süre ve güncel hız; menüde ayrıntılı istatistikler bulunur.
- **Navigasyon:** Menüden hedef ara, kayıtlı/son yerlerden seç veya telefonda hazırlanmış planı kullan. Arama yalnızca gönderildiğinde yapılır. Rotayı inceleyip başlatınca aynı yolculuğa sesli yönlendirme eklenir.
- **Kayıt ve yönlendirme:** Rotayı bitirmek veya hedefe varmak yolculuk kaydını bitirmez. **Sürüşü bitir** kaydı kapatır. Telefon/saat/araç aynı yolculuğu yönetir.
- **Telefon:** Üst çubuktaki araç simgesi sürüş ve navigasyon sayfasını açar. Android Auto bağlantısı kesilse de kayıt ve yönlendirme telefonda sürer.
- **Hava:** Hedefsiz sürüşte mevcut konum; rota seçilince rota boyunca beklenen hava gösterilir. Hava servisi hatası navigasyonu engellemez. Dönüş anonsları hava anonslarından önceliklidir.
- **İnternet yokken:** Önceden yüklenmiş rota ve talimatlar devam eder. Yeni rota hesaplama ve çevrimdışıyken rotadan sapma için bağlantı gerekir. Harita yalnızca önbellekteki alanlarda görünür; bölge indirme yoktur.
- **Kesinti:** Eski GPS konumundan yeni dönüş komutu üretilmez. Telefon uygulamasının süreci kapanırsa eksik zaman kesintisiz kayıtmış gibi gösterilmez; son hedefe yeniden başlatma sunulur.

## Bilgisayarda Android Auto testi

Desktop Head Unit (DHU), Android SDK Manager üzerinden kurulan Android Auto Desktop Head Unit paketinin içindedir. Aşağıdaki örnekte `ANDROID_SDK_ROOT` kendi Android SDK dizininizi, `PHONE_SERIAL` ise test telefonunun `adb devices` çıktısındaki kimliğini temsil eder. Telefon emülatöründeki şablon testleri DHU testinin yerine geçmez.

1. İz test APK'sını telefona kur. Telefonun USB hata ayıklamasını açıp bilgisayar bağlantısını telefonda onayla.
2. Telefonda Android Auto ayarlarının sürüm bölümüne 10 kez dokunarak geliştirici seçeneklerini aç.
3. Android Auto geliştirici menüsünden **Ana birim sunucusunu başlat** seçeneğini aç.
4. PowerShell'de aşağıdaki komutları çalıştır. Birden fazla cihaz varsa `PHONE_SERIAL` yerine fiziksel telefonun `adb devices` çıktısındaki kimliğini yaz.

```powershell
$sdkRoot = $env:ANDROID_SDK_ROOT
& "$sdkRoot\platform-tools\adb.exe" devices -l
& "$sdkRoot\platform-tools\adb.exe" -s PHONE_SERIAL forward tcp:5277 tcp:5277
& "$sdkRoot\extras\google\auto\desktop-head-unit.exe"
```

DHU ekranında İz'i aç. Önce hedef girmeden aktif kaydı kontrol et; sonra hedef seçerek navigasyonu dene. Harita gece/gündüz görünümü, farklı ekran boyutları, ses, konum kaybı ve bağlantıyı kesip tekrar bağlama senaryolarını doğrula. Özel bir deneme sürüşü gerçek yolculuk kaydına veya saat sağlık verisine yazılmamalıdır.

Test bitince Android Auto ana birim sunucusunu telefondan kapat ve yalnızca eklediğin yönlendirmeyi kaldır:

```powershell
& "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe" -s PHONE_SERIAL forward --remove tcp:5277
```

## Gerçek araç ve dağıtım

Car App Library tabanlı navigasyon uygulamalarının gerçek araç dağıtım koşulları Google Play ve Android for Cars kurallarına tabidir. Bu depo yalnız kaynak kodu yayımlar; APK veya mağaza sürümü içermez.

Bir ikili dağıtım hazırlanırsa telefon ve saat paketlerinin imza eşleşmesi, günlük yedeği, Android Auto uygunluğu ve kullanılan bağımlılıkların lisans koşulları ayrıca doğrulanmalıdır.

Kaynaklar: [DHU kurulumu](https://developer.android.com/training/cars/testing/dhu), [gerçek araç test koşulları](https://developer.android.com/training/cars/testing), [harita yüzeyi](https://developer.android.com/training/cars/apps/library/draw-maps), [navigasyon entegrasyonu](https://developer.android.com/training/cars/apps/navigation).
