# Gidon yatışı saha değerlendirmesi

Gidondaki telefonun raporladığı virajları zaman eşlenmiş bağımsız şasi ölçümüyle karşılaştırmak için `python score.py saha.json` çalıştırın. Dereceler dikey dünyaya göre şasi yatışıdır. Sağ pozitif, sol negatiftir. Açı doğruluğu için bağımsız referansın hata payını, saat eşleme hatasını, cihazları ve montajı saha tutanağına kaydedin. Üç derecelik eşiğe yakın sonuçları yeni bir bağımsız sürüşte doğrulayın. Analiz aracının çıktısı uygulamadaki yayın ayarlarını değiştirmez.

Her `id` tam bir sürüşü tanımlar. Geliştirme ve doğrulama kümelerini sürüş düzeyinde ayırın. Referansa göre sınıflandırılan **bütün** uygun virajları, gidon sonuçlarını açmadan önce `reference` listesine alın. Düşük hız grubu dar manevralar, kavşaklar ve U dönüşlerini, akıcı grup kararlı yol virajlarını kapsar. Gidon uygulamasında görünen bütün sonuçları `reported` listesine ekleyin. Rota koordinatları ve sürücü kimlikleri değerlendirme girdisinde gerekmez. Zamanları aynı monoton başlangıca göre milisaniye cinsinden sıralayın. Parkta motosiklet dikken gidonun çevrildiği aralıkları ayrıca işaretleyin.

```json
{"source":"synthetic","tuningRideIds":["ayar-1"],"rides":[{"id":"kontrol-1","reference":[{"kind":"parked_steering","startMs":0,"endMs":7000},{"kind":"low_speed","startMs":20000,"endMs":24500,"signedPeakDegrees":-9.4}],"reported":[{"startMs":20100,"endMs":24400,"signedPeakDegrees":-10}]}]}
```

Gerçek karşılaştırmada `source` değeri `field` olur. Kod her iki zaman aralığının en az yarısı çakışan tek referans virajını eşler. Yön eşleşmesi ve tepe büyüklüğü hatası ayrı hesaplanır. Her sınıf için en az 40 referans virajı, her iki yöne ölçüm, en az %50 kapsama, bildirilenlerin en az %95’inde en fazla 3° mutlak tepe hatası ve doğru yön gerekir. Parkta üretilen veya bir referans virajına tekil olarak eşlenemeyen raporlar incelemede başarısız olur. Örnekteki veri yalnız biçimi gösterir. `FIELD_REVIEW_REQUIRED` saha kaydının kökeni, ölçüm kalitesi ve sonuçların bağımsız incelemesini gerektirir.
