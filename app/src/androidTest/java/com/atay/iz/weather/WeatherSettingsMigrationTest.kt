package com.atay.iz.weather

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.data.Transport
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeatherSettingsMigrationTest {
    private lateinit var context: Context
    private lateinit var app: Context
    private val createdPreferenceNames = mutableSetOf<String>()

    @Before fun createIsolatedPreferences() {
        app = ApplicationProvider.getApplicationContext()
        val prefix = "weather-settings-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = prefix + name
                createdPreferenceNames += isolatedName
                return app.getSharedPreferences(isolatedName, mode)
            }
        }
    }

    @After fun deleteIsolatedPreferences() {
        createdPreferenceNames.forEach { app.deleteSharedPreferences(it) }
    }

    @Test fun approvedThresholdResetPreservesLegacyVoiceAndServices() {
        val legacy = """{"version":1,"voice":true,"routeEndpoint":"https://example.com/routes",
            "weatherEndpoint":"https://example.com/forecast","probability":65,"precipitation":0.5,
            "wind":24,"gust":44,"cold":2,"hot":33}"""
        val preferences = context.getSharedPreferences("ride_weather_settings_v1", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("settings", legacy).commit())
        val store = WeatherSettingsStore(context)
        val motorcycle = store.read()
        assertEquals(30.0, motorcycle.thresholds.windKmh, 0.0)
        assertTrue(motorcycle.voiceEnabled)
        assertTrue(motorcycle.alertsEnabled)
        assertEquals("https://example.com/routes", motorcycle.routeEndpoint)
        assertEquals("https://example.com/forecast", motorcycle.weatherEndpoint)
        assertNull(motorcycle.travelSpeedKmh)
        listOf(Transport.CAR, Transport.PASSENGER, Transport.BICYCLE, Transport.WALK, Transport.RUN).forEach {
            assertEquals(defaultWeatherSettings(it), store.read(it))
            assertFalse(store.read(it).voiceEnabled)
            assertEquals(DEFAULT_ROUTE_ENDPOINT, store.read(it).routeEndpoint)
        }
        store.save(motorcycle.copy(voiceEnabled = false))
        assertFalse(WeatherSettingsStore(context).read().voiceEnabled)
        assertEquals(legacy, preferences.getString("settings", null))
        assertEquals(30.0, WeatherSettingsStore(context).read().thresholds.windKmh, 0.0)
    }

    @Test fun allOldProfilesResetThresholdsExactlyOnceAndKeepOtherPreferences() {
        val preferences = context.getSharedPreferences("ride_weather_settings_v1", Context.MODE_PRIVATE)
        val modes = Transport.entries.filter { it != Transport.UNKNOWN }
        val editor = preferences.edit()
        modes.forEach { mode ->
            editor.putString("settings_${mode.name}", WeatherSettingsCodec.encode(defaultWeatherSettings(mode).copy(
                thresholds = WeatherThresholds(windKmh = 77.0), voiceEnabled = true, alertsEnabled = false,
                routeEndpoint = "https://example.com/route", weatherEndpoint = "https://example.com/weather",
                travelSpeedKmh = if (mode == Transport.RUN) 12.0 else defaultWeatherSettings(mode).travelSpeedKmh,
            )))
        }
        assertTrue(editor.commit())
        modes.forEach { mode ->
            val actual = WeatherSettingsStore(context).read(mode)
            assertEquals(defaultWeatherSettings(mode).thresholds, actual.thresholds)
            assertTrue(actual.voiceEnabled)
            assertFalse(actual.alertsEnabled)
            assertEquals("https://example.com/route", actual.routeEndpoint)
            if (mode == Transport.RUN) assertEquals(12.0, actual.travelSpeedKmh!!, 0.0)
        }
        val store = WeatherSettingsStore(context)
        val custom = store.read(Transport.RUN).copy(thresholds = WeatherThresholds(windKmh = 19.0))
        store.save(custom, Transport.RUN)
        assertEquals(custom, WeatherSettingsStore(context).read(Transport.RUN))
    }

    @Test fun everyModePersistsItsOwnSettingsAcrossStoreRecreation() {
        val modes = listOf(Transport.CAR, Transport.MOTORCYCLE, Transport.BICYCLE, Transport.WALK, Transport.RUN, Transport.PASSENGER)
        modes.forEachIndexed { index, mode ->
            WeatherSettingsStore(context).save(defaultWeatherSettings(mode).copy(
                thresholds = WeatherThresholds(windKmh = 15.0 + index),
                alertsEnabled = index % 2 == 0,
                voiceEnabled = index % 2 != 0,
                routeEndpoint = "https://example.com/${mode.name.lowercase()}",
            ), mode)
        }
        val restored = WeatherSettingsStore(context)
        modes.forEachIndexed { index, mode ->
            val actual = restored.read(mode)
            assertEquals(15.0 + index, actual.thresholds.windKmh, 0.0)
            assertEquals(index % 2 == 0, actual.alertsEnabled)
            assertEquals(index % 2 != 0, actual.voiceEnabled)
            assertEquals("https://example.com/${mode.name.lowercase()}", actual.routeEndpoint)
        }
        restored.save(restored.read(Transport.WALK).copy(travelSpeedKmh = 6.5), Transport.WALK)
        assertEquals(6.5, WeatherSettingsStore(context).read(Transport.WALK).travelSpeedKmh!!, 0.0)
        assertEquals(10.0, WeatherSettingsStore(context).read(Transport.RUN).travelSpeedKmh!!, 0.0)
        assertEquals(18.0, WeatherSettingsStore(context).read(Transport.BICYCLE).travelSpeedKmh!!, 0.0)
    }

    @Test fun invalidSpeedsCannotReplacePreviouslySavedModeSettings() {
        val store = WeatherSettingsStore(context)
        val invalid = listOf(
            Transport.WALK to 0.49, Transport.WALK to 25.01, Transport.RUN to 26.0,
            Transport.BICYCLE to 4.99, Transport.BICYCLE to 60.01,
            Transport.RUN to Double.NaN, Transport.CAR to 10.0,
        )
        invalid.forEach { (mode, speed) ->
            val original = defaultWeatherSettings(mode).copy(alertsEnabled = false)
            store.save(original, mode)
            assertThrows(IllegalArgumentException::class.java) { store.save(original.copy(travelSpeedKmh = speed), mode) }
            assertEquals(original, WeatherSettingsStore(context).read(mode))
        }
        listOf(Transport.WALK to 0.5, Transport.RUN to 25.0, Transport.BICYCLE to 5.0, Transport.BICYCLE to 60.0).forEach { (mode, speed) ->
            store.save(defaultWeatherSettings(mode).copy(travelSpeedKmh = speed), mode)
            assertEquals(speed, WeatherSettingsStore(context).read(mode).travelSpeedKmh!!, 0.0)
        }
    }

    @Test fun unknownTransportCannotReadOrOverwriteAMode() {
        val store = WeatherSettingsStore(context)
        val motorcycle = RideWeatherSettings(voiceEnabled = true)
        store.save(motorcycle)
        assertThrows(IllegalArgumentException::class.java) { store.read(Transport.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) { store.save(RideWeatherSettings(), Transport.UNKNOWN) }
        assertEquals(motorcycle, store.read())
    }
}
