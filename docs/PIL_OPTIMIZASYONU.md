# Telefon ve saat pili: geliştirme adayı

**Durum:** 0.10.2 / code 21, Wear OS 0.6.1 / code 11 ve iletişim v6 için geliştirme notu. Yayımlanmış sürüm veya test raporu değildir. Pil tasarrufu miktarı ölçülmemiştir.

## Sürüş sırasında

Yol tarifi, sesli yönlendirme, periyodik rota ve trafik yenilemesi, hava durumu, grup takibi, hız/hız sınırı ve mevcut ekranın açık kalması korunur. Ham GPS ve saat sensör kayıtları devam eder. Geçilen yolun hız renkleri, ısı haritası ve ayrıntılı istatistikler sürüş sırasında hesaplanmaz. Saatte kayıt/navigasyon durumu gösterilir; henüz hazırlanmamış özetler güncelmiş gibi sunulmaz.

## Ertelenen işler

Hesaplama ve özet işleri ancak aktif/geçici kayıt ve navigasyon sona erdikten sonra, telefon pili **%20 veya üzerindeyse** çalışabilir. Tam %20 uygundur; şarjda olma şartı yoktur. Yolculuk veya navigasyon yeniden başlarsa ya da pil eşik altına düşerse kuyruk güvenli noktada bekler. Tamamlanan sonuçlar korunur ve aynı yolculuk gereksiz yere yeniden işlenmez. WorkManager işi başlatabilir, ancak Android başlama zamanını geciktirebilir; pil değişimini sürekli izleyen yeni bir servis veya detay ekranını açınca eşiği aşan işlem yoktur.

## Yerel ve görünmeyen tanılama

Menü, kart, bildirim veya gizli ayar ekranı eklenmez. Küçük tanılama kaydı yalnız uygulamanın yedek dışı özel alanında tutulur; en fazla **7 gün ve toplam 2 MiB** saklanır. Örnekleme normalde dakikada birdir ve önemli durum geçişleri ayrıca yazılır. Yeni GPS isteği veya yalnız tanılama için sürekli çalışan servis kullanılmaz.

Kaydedilebilecek alanlar sürüm/zaman, pil yüzdesi/şarj/sıcaklık, cihaz destekliyorsa akım ve şarj sayacı, ekran/ön plan/kayıt/navigasyon durumu ve işlem sayısı/süreleridir. Desteklenmeyen ölçüm “ölçülemedi” olarak kalır. Koordinat, adres, mesaj, anahtar ve nabız/adım değerleri bu tanılama kaydına yazılmaz; kayıt ağla veya GitHub'la paylaşılmaz.

Yetkilendirilmiş USB hata ayıklama bağlantısında, uygulamanın mevcut Activity dump çıktısı salt okunur alınabilir:

```text
adb -s CIHAZ_KIMLIGI shell dumpsys activity org.iz.navigation/.MainActivity iz-battery-diagnostics
```

Çıktı yerelde incelenir. Android pil ölçümleri cihazın toplam durumudur; tek başına İz'e ait tüketimi veya tasarruf yüzdesini kanıtlamaz. Uygulama testi, derleme ve cihaz kabulü kendi raporlarında belirtilmelidir.

## Uyumluluk ve kaynaklar

Günlük kayıtları, ham GPS/saat sensör verileri ve yedek biçimi korunur. Tam ertelenmiş-istatistik pil davranışı, telefon 0.10.2 ile eşleşen Wear OS 0.6.1 ve iletişim v6 gerektirir. Eski saat uyumluluğu yalnız gerçekten erişilebilen eski bir saat saptandığında kullanılır; eski eşin yetenek bilgisi 60 saniye önbelleklenir, v6 düğümleri bu yola katılmaz. Bekleyen eski günlük/sağlık özetleri gösterilmez; ham kayıt, gönderim kuyruğu ve zaman aşımı korunur. Ölçülmemiş değerler sıfır gibi sunulmaz. Fiziksel saat kabulü yapılmış değildir; adayın kaynak/test kabulü ayrıca raporlanacaktır. [Android BatteryManager](https://developer.android.com/reference/android/os/BatteryManager) · [Activity.dump](https://developer.android.com/reference/android/app/Activity#dump(java.lang.String,%20java.io.FileDescriptor,%20java.io.PrintWriter,%20java.lang.String[])) · [WorkManager iş koşulları](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
