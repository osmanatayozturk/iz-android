# Yolculuk havası kod sözleşmeleri

Bu belge `org.iz.navigation.weather` paketindeki temel veri ve servis sınırlarını açıklar. Kaynak kod değiştiğinde imzalarla birlikte güncellenmelidir.

```kotlin
data class WeatherCoordinate(val latitude: Double, val longitude: Double)
data class RouteStop(val label: String, val coordinate: WeatherCoordinate)
data class RouteVertex(val coordinate: WeatherCoordinate, val elapsedSeconds: Double)
enum class RouteProvider { VALHALLA, TOMTOM }
data class RouteTrafficInfo(
 val fetchedAt: Long,
 val delaySeconds: Double,
 val noTrafficDurationSeconds: Double?,
 val experimental: Boolean = false,
)
data class RouteManeuver(
 val type: Int,
 val instruction: String,
 val verbalInstruction: String,
 val streetNames: List<String>,
 val beginShapeIndex: Int,
 val endShapeIndex: Int,
 val beginElapsedSeconds: Double,
 val endElapsedSeconds: Double,
 val roundaboutExit: Int? = null,
)
data class PlannedRoute(
 val id: String,
 val stops: List<RouteStop>,
 val vertices: List<RouteVertex>,
 val distanceMeters: Double,
 val durationSeconds: Double,
 val createdAt: Long,
 val stopElapsedSeconds: List<Double> = emptyList(),
 val transport: org.iz.navigation.data.Transport = org.iz.navigation.data.Transport.MOTORCYCLE,
 val travelSpeedKmh: Double? = null,
 val maneuvers: List<RouteManeuver> = emptyList(),
 val provider: RouteProvider = RouteProvider.VALHALLA,
 val traffic: RouteTrafficInfo? = null,
 val trafficUnavailableReason: String? = null,
)
data class WeatherThresholds(val precipitationProbabilityPercent: Double = 50.0, val precipitationMm: Double = 0.2, val windKmh: Double = 30.0, val gustKmh: Double = 50.0, val coldC: Double = 5.0, val hotC: Double = 35.0)
enum class WeatherHazard { RAIN, WIND, COLD, HEAT }
data class WeatherReading(val temperatureC: Double?, val precipitationProbabilityPercent: Double?, val precipitationMm: Double?, val windKmh: Double?, val gustKmh: Double?, val windDirectionDegrees: Double?)
data class WeatherHour(val time: Long, val reading: WeatherReading)
data class LocationForecast(val coordinate: WeatherCoordinate, val hours: List<WeatherHour>, val fetchedAt: Long)
data class RouteWeatherSample(val coordinate: WeatherCoordinate, val elapsedSeconds: Double, val arrivalAt: Long, val reading: WeatherReading, val hazards: Set<WeatherHazard>, val complete: Boolean)
data class WeatherAssessment(val departureAt: Long, val samples: List<RouteWeatherSample>, val exceededSeconds: Double, val complete: Boolean, val minTemperatureC: Double?, val maxTemperatureC: Double?, val maxPrecipitationProbabilityPercent: Double?, val maxWindKmh: Double?, val maxGustKmh: Double?, val fetchedAt: Long?)
interface RoutePlanner {
 suspend fun plan(
  stops: List<RouteStop>,
  departureAt: Long,
  transport: org.iz.navigation.data.Transport = org.iz.navigation.data.Transport.MOTORCYCLE,
  travelSpeedKmh: Double? = null,
 ): PlannedRoute
}
interface WeatherProvider { suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> }
class ValhallaRoutePlanner(context: android.content.Context, endpoint: String = DEFAULT_ROUTE_ENDPOINT) : RoutePlanner
class OpenMeteoWeatherProvider(context: android.content.Context, endpoint: String = DEFAULT_WEATHER_ENDPOINT) : WeatherProvider
object WeatherEngine {
 fun sampleVertices(route: PlannedRoute): List<RouteVertex>
 fun assess(route: PlannedRoute, departureAt: Long, forecasts: List<LocationForecast>, thresholds: WeatherThresholds, elapsedSeconds: Double = 0.0): WeatherAssessment
 fun compare(route: PlannedRoute, departureAt: Long, forecasts: List<LocationForecast>, thresholds: WeatherThresholds): List<WeatherAssessment>
 fun hazards(reading: WeatherReading, thresholds: WeatherThresholds): Set<WeatherHazard>
 fun distanceMeters(a: WeatherCoordinate, b: WeatherCoordinate): Double
}
```
Engine assess uses stable sampleVertices(route), filters elapsed>=elapsedSeconds for live; departureAt in live = now-progressSeconds*1000, so the future sample ETAs shift without moving sampled coordinates. complete requires all core values except wind direction. Missing forecast matches stays incomplete; match nearest response coordinates <=100m to account for URL rounding, never unrelated grid cells. Caller samples once and provider must return requested coordinate, not model-grid coordinate. fetchedAt is minimum used forecast fetch timestamp. compare returns chronological 7 candidates (not sorted); UI recommendation via complete candidates minBy(exceededSeconds, departureAt). Use following interval's samples conservatively for segment exposedSeconds; no 0-duration omission of adverse endpoint. API index matching must preserve null-location results.

## Çalışma zamanı sözleşmesi
```kotlin
data class RideWeatherSettings(
 val thresholds: WeatherThresholds = WeatherThresholds(),
 val voiceEnabled: Boolean = false,
 val routeEndpoint: String = DEFAULT_ROUTE_ENDPOINT,
 val weatherEndpoint: String = DEFAULT_WEATHER_ENDPOINT,
 val alertsEnabled: Boolean = true,
 val travelSpeedKmh: Double? = null,
)
class WeatherSettingsStore(context: android.content.Context) {
 fun read(transport: org.iz.navigation.data.Transport = org.iz.navigation.data.Transport.MOTORCYCLE): RideWeatherSettings
 fun save(value: RideWeatherSettings, transport: org.iz.navigation.data.Transport = org.iz.navigation.data.Transport.MOTORCYCLE)
}
data class SavedWeatherPlan(
 val stops: List<RouteStop>,
 val departureAt: Long,
 val transport: org.iz.navigation.data.Transport = org.iz.navigation.data.Transport.MOTORCYCLE,
)
class WeatherPlanStore(context: android.content.Context) { fun read(): SavedWeatherPlan?; fun save(value: SavedWeatherPlan) }
enum class RideWeatherStatus { OFF, LOADING, READY, ERROR }
data class RideWeatherLiveState(
 val status: RideWeatherStatus = RideWeatherStatus.OFF,
 val journeyId: String? = null,
 val route: PlannedRoute? = null,
 val assessment: WeatherAssessment? = null,
 val remainingMeters: Double = 0.0,
 val arrivalAt: Long? = null,
 val gpsStale: Boolean = false,
 val message: String? = null,
 val remainingSeconds: Double? = null,
 val timingUpdatedAt: Long? = null,
 val refreshFailed: Boolean = false,
)
class RideWeatherManager(context: android.content.Context) {
 val state: kotlinx.coroutines.flow.StateFlow<RideWeatherLiveState>
 suspend fun activateGuidance(route: PlannedRoute, forecasts: List<LocationForecast>): String
 suspend fun activate(route: PlannedRoute, forecasts: List<LocationForecast>): String
 fun stop() // weather only, never stop user's diary
 fun testVoice() // only explicit user action
 fun refresh(): kotlinx.coroutines.Job // throttled manual refresh, no GPS re-registration
 fun onAcceptedLocation(journeyId: String, coordinate: WeatherCoordinate, recordedAt: Long, accuracyMeters: Float)
 fun onTrackingStopped(journeyId: String?)
 fun wearWeather(journeyId: String): org.iz.navigation.wearprotocol.WearRouteWeather?
}
```
Uygulama tek bir `RideWeatherManager` örneği kullanır. Arayüz bağımsız bir yönetici oluşturmaz; güncel ayarları depodan okur. `stop()` yalnız hava takibini kapatır ve günlük kaydını bitirmez. Ağdan dönen geç yanıtlar oturum nesliyle denetlenir ve yeni bir oturumun durumunu değiştiremez.

## Wear OS sözleşmesi

Saat veri türleri `org.iz.navigation.wearprotocol` paketindedir:
```kotlin
enum class WearWeatherStatus { LOADING, READY, ERROR }
enum class WearWeatherThreshold { BELOW_THRESHOLD, EXCEEDED }
data class WearRouteWeather(val status: WearWeatherStatus, val calculatedAt: Long, val validUntil: Long, val remainingMeters: Double = 0.0, val arrivalAt: Long? = null, val hazardStartsAt: Long? = null, val threshold: WearWeatherThreshold = WearWeatherThreshold.BELOW_THRESHOLD, val headline: String = "", val detail: String = "")
```
Hava alanı yalnız ikili v3 `WearSnapshot` içinde isteğe bağlıdır; `null` kartı gizler. Başlık ve ayrıntı UTF-8 olarak sırasıyla 120 ve 300 baytla sınırlıdır. `calculatedAt` ve `validUntil`, paketin hazırlanma zamanını değil gerçek tahminin alınma ve geçerlilik zamanlarını taşır. Telefon seçili saatin desteklediği protokol sürümünü yayımlar; v1 ve v2 biçimleri değişmeden kalır.

## Arayüz ve test sınırları

Planlayıcı başlangıç ve hedefte mevcut konum, elle seçim, kayıtlı yer ve gönderilmiş aramayı destekler; en fazla üç ara durak kabul eder. Başlatma sırasında güncel konum ve tahmin yeniden alınır. Planlanan rota kaydedilmiş GPS izinden ayrı çizilir.

Testlerde ağ, saat ve bildirim/TTS çıkışları sınır arayüzlerinden değiştirilir; hava motoru, rota örnekleme ve yönetici durumu gerçek kodla çalıştırılır. Etkinleştirmede GPS, kabul edilmiş yeni bir konum gelene kadar eski sayılır. Elle yenileme en az 60 saniye, olağan hava yenilemesi 15 dakika ile sınırlandırılır. `stop()` devam eden istekleri geçersiz kılar ve günlük kaydını durdurmaz.
