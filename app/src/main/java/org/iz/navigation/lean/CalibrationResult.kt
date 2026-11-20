package org.iz.navigation.lean

internal sealed interface CalibrationProgress
internal data object StepAccepted : CalibrationProgress

internal sealed interface CalibrationResult : CalibrationProgress {
    data class Ready(val profile: LeanCalibration) : CalibrationResult
    data class Failed(val reason: CalibrationFailure) : CalibrationResult
}

internal enum class CalibrationFailure(val message: String) {
    SENSOR_SERVICE("Android hareket sensörlerine erişim sağlayamadı. Sensör erişimini kontrol edip yeniden dene."),
    ACCELEROMETER_MISSING("Telefonda kullanılabilir bir ivmeölçer bulunamadı."),
    GYROSCOPE_MISSING("Telefonda kullanılabilir bir jiroskop bulunamadı."),
    ACCELEROMETER_START("İvmeölçer ölçümü başlatılamadı. Sensör erişimini kontrol edip yeniden dene."),
    GYROSCOPE_START("Jiroskop ölçümü başlatılamadı. Sensör erişimini kontrol edip yeniden dene."),
    INSUFFICIENT_SAMPLES("Kalibrasyon için yeterli sensör örneği alınamadı."),
    UNRELIABLE_SAMPLES("Sensörün bildirdiği ölçüm güvenilirliği yetersizdi."),
    INVALID_SAMPLE("Sensör geçersiz bir ölçüm gönderdi."),
    SHORT_WINDOW("Sensör ölçümleri gerekli süreyi kapsamadı."),
    SAMPLE_GAP("Sensör verisinde zaman kesintisi veya sıralama hatası vardı."),
    BUFFER_FULL("Sensör örnekleri bu adım için ayrılan kapasiteyi aştı."),
    GRAVITY("Ölçülen ivme, sabit duran bir motosiklet için beklenen aralığın dışındaydı."),
    MOVEMENT("Ölçüm sırasında hareket veya titreşim algılandı. Motosikleti ve telefonu sabit tut."),
    GYRO_DRIFT("Motosiklet sabitken jiroskop sürekli dönme bildirdi. Sabit konumda yeniden dene."),
    TURN_RANGE("Ölçülen gidon dönüşü bu adım için gereken açı aralığının dışındaydı."),
    TURN_DIRECTION("Gidon hareketi önceki dönüşü karşı yönde yeterince geçmedi."),
    TURN_AXIS("Hareket tek bir gidon ekseniyle uyuşmadı. Telefonun bağlantısını kontrol et."),
    CENTER("Gidon başlangıç konumuna dönmedi."),
    MOUNT_SHIFT("Telefonun yönü gidon hareketiyle uyuşmadı. Montajı sabitle ve motosikleti dik tut."),
}
