package org.iz.navigation.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import org.iz.navigation.wearprotocol.*
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun WeatherCard(weather: WearRouteWeather, now: Long, connectionFresh: Boolean, paused: Boolean) {
    val readyAndFresh = connectionFresh && weather.status == WearWeatherStatus.READY && now < weather.validUntil
    val accent = if (readyAndFresh && weather.threshold == WearWeatherThreshold.EXCEEDED) {
        MaterialTheme.colors.error
    } else if (readyAndFresh) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colors.surface, RoundedCornerShape(20.dp)).padding(12.dp)
            .testTag("weather-card"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            !connectionFresh -> {
                Text("Telefon bağlantısı/verisi güncel değil", color = MaterialTheme.colors.onSurface, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Tahmin: ${measurementTime(weather.calculatedAt)}", color = MaterialTheme.colors.onSurface,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
                Text("Geçerlilik: ${measurementTime(weather.validUntil)}", color = MaterialTheme.colors.onSurface,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
            }
            paused && weather.status == WearWeatherStatus.LOADING -> {
                Text(weather.headline.ifBlank { "Yönlendirme duraklatıldı" }, color = MaterialTheme.colors.onSurface,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(weather.detail.ifBlank { "Varış zamanı ve yol havası bekletiliyor." }, color = MaterialTheme.colors.onSurface,
                    fontSize = 11.sp, textAlign = TextAlign.Center)
            }
            weather.status == WearWeatherStatus.LOADING -> {
                Text(weather.headline.ifBlank { "Hava hazırlanıyor" }, color = MaterialTheme.colors.onSurface,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Tahmin telefonda hesaplanıyor.", color = MaterialTheme.colors.onSurface, fontSize = 11.sp,
                    textAlign = TextAlign.Center)
            }
            weather.status == WearWeatherStatus.ERROR -> {
                Text(weather.headline.ifBlank { "Hava verisi alınamadı" }, color = MaterialTheme.colors.onSurface,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (weather.detail.isNotBlank()) Text(weather.detail, color = MaterialTheme.colors.onSurface, fontSize = 11.sp,
                    textAlign = TextAlign.Center)
                Text("Tahmin kullanılamıyor", color = MaterialTheme.colors.onSurface, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text("Tahmin: ${measurementTime(weather.calculatedAt)}", color = MaterialTheme.colors.onSurface,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
            }
            !readyAndFresh -> {
                Text("Tahmin süresi doldu", color = MaterialTheme.colors.onSurface, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Tahmin: ${measurementTime(weather.calculatedAt)}", color = MaterialTheme.colors.onSurface,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
            }
            paused -> {
                Text("Duraklama nedeniyle ilerideki varış saatleri belirsiz.", color = MaterialTheme.colors.onSurface,
                    fontSize = 12.sp, textAlign = TextAlign.Center)
                Text("Son tahmin: ${measurementTime(weather.calculatedAt)}", color = MaterialTheme.colors.onSurface,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
            }
            else -> {
                Text(weather.headline, color = accent, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (weather.detail.isNotBlank()) Text(weather.detail, color = MaterialTheme.colors.onSurface, fontSize = 11.sp,
                    textAlign = TextAlign.Center)
                if (weather.remainingMeters > 0) Text(
                    "Kalan rota: ${String.format(Locale.forLanguageTag("tr"), "%.1f km", weather.remainingMeters / 1_000.0)}",
                    color = MaterialTheme.colors.onSurface, fontSize = 11.sp, textAlign = TextAlign.Center)
                if (weather.threshold == WearWeatherThreshold.EXCEEDED) weather.hazardStartsAt?.let {
                    Text("Eşik yaklaşık ${measurementTime(it)}", color = accent, fontSize = 11.sp,
                        textAlign = TextAlign.Center)
                }
                weather.arrivalAt?.let {
                    Text("Varış yaklaşık ${measurementTime(it)}", color = MaterialTheme.colors.onSurface, fontSize = 11.sp,
                        textAlign = TextAlign.Center)
                }
                Text("Tahmin: ${measurementTime(weather.calculatedAt)}", color = MaterialTheme.colors.onSurface,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun measurementTime(value: Long?): String = value?.let {
    SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("tr")).format(Date(it))
} ?: "Ölçüm yok"
