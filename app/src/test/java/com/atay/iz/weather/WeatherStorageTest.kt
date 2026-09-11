package com.atay.iz.weather

import com.atay.iz.data.Transport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WeatherStorageTest {
    @Test fun versionTwoSettingsPreserveExistingCustomThresholds() {
        val raw = """{"version":2,"voice":true,"routeEndpoint":"https://example.com/routes",
            "weatherEndpoint":"https://example.com/forecast","probability":65,"precipitation":0.4,
            "wind":22,"gust":48,"cold":3,"hot":35,"alertsEnabled":false,"travelSpeedKmh":12}"""
        val settings = WeatherSettingsCodec.decode(raw)
        assertEquals(22.0, settings.thresholds.windKmh, 0.0)
        assertTrue(settings.voiceEnabled)
        assertEquals("https://example.com/routes", settings.routeEndpoint)
        assertFalse(settings.alertsEnabled)
        assertEquals(12.0, settings.travelSpeedKmh!!, 0.0)
    }

    @Test fun versionTwoPlanRestoresStopsWithoutLosingTheDraft() {
        val raw = """{"version":2,"transport":"RUN","departureAt":1800000000000,"stops":[
            {"name":"Park","lat":41.0,"lon":29.0},{"name":"Finish","lat":41.1,"lon":29.1}]}"""
        val plan = WeatherPlanCodec.decode(raw)
        assertNotNull(plan)
        assertEquals(listOf("Park", "Finish"), plan!!.stops.map { it.label })
        assertEquals(1_800_000_000_000L, plan.departureAt)
        assertEquals(Transport.RUN, plan.transport)
    }

    @Test fun oldSettingsKeepCustomValuesWhileNewFieldsUseCompatibleDefaults() {
        val raw = """{"version":1,"voice":true,"routeEndpoint":"https://example.com/route",
            "weatherEndpoint":"https://example.com/weather","probability":70,"precipitation":0.7,
            "wind":24,"gust":45,"cold":2,"hot":32}"""
        val settings = WeatherSettingsCodec.decode(raw)
        assertEquals(24.0, settings.thresholds.windKmh, 0.0)
        assertTrue(settings.voiceEnabled)
        assertTrue(settings.alertsEnabled)
        assertNull(settings.travelSpeedKmh)
        assertEquals("https://example.com/weather", settings.weatherEndpoint)
    }

    @Test fun settingsRetainIndependentAlertSwitchAndCustomTravelSpeed() {
        val settings = RideWeatherSettings(voiceEnabled = true, alertsEnabled = false, travelSpeedKmh = 14.5)
        val encoded = WeatherSettingsCodec.encode(settings)
        assertEquals(2, JSONObject(encoded).getInt("version"))
        assertEquals(settings, WeatherSettingsCodec.decode(encoded))
    }

    @Test fun oldPlansBelongToMotorcycleAndEveryExplicitModeSurvivesSaving() {
        val oldPlan = """{"version":1,"departureAt":12,"stops":[
            {"name":"A","lat":41,"lon":29},{"name":"B","lat":41.1,"lon":29.1}]}"""
        val migrated = WeatherPlanCodec.decode(oldPlan)!!
        assertEquals(Transport.MOTORCYCLE, migrated.transport)
        Transport.entries.filter { it != Transport.UNKNOWN }.forEach { transport ->
            val plan = migrated.copy(transport = transport)
            assertEquals(plan, WeatherPlanCodec.decode(WeatherPlanCodec.encode(plan)))
        }
    }

    @Test fun unknownOrMissingVersionTwoPlanModeCannotBecomeAMotorcyclePlan() {
        val stops = """[{"name":"A","lat":41,"lon":29},{"name":"B","lat":41.1,"lon":29.1}]"""
        listOf("UNKNOWN", "FLY", "walk").forEach { transport ->
            assertNull(WeatherPlanCodec.decode("""{"version":2,"transport":"$transport","departureAt":1,"stops":$stops}"""))
        }
        assertNull(WeatherPlanCodec.decode("""{"version":2,"departureAt":1,"stops":$stops}"""))
        val plan = SavedWeatherPlan(listOf(
            RouteStop("A", WeatherCoordinate(41.0, 29.0)),
            RouteStop("B", WeatherCoordinate(41.1, 29.1)),
        ), 1, Transport.UNKNOWN)
        assertThrows(IllegalArgumentException::class.java) { WeatherPlanCodec.encode(plan) }
    }

    @Test fun savedPlanRoundTripDoesNotLoseStopOrderOrUtcDeparture() {
        val plan = SavedWeatherPlan(listOf(
            RouteStop("Başlangıç", WeatherCoordinate(40.9906, 29.0233)),
            RouteStop("Mola", WeatherCoordinate(41.05, 29.3)),
            RouteStop("Şile", WeatherCoordinate(41.1764, 29.6128)),
        ), 1_800_000_000_000L)
        assertEquals(plan, WeatherPlanCodec.decode(WeatherPlanCodec.encode(plan)))
        assertNull(WeatherPlanCodec.decode("""{"version":1,"departureAt":1,"stops":[]}"""))
        assertNull(WeatherPlanCodec.decode("""{"version":2}"""))
    }
    @Test fun corruptedSettingsRecoverToVoiceOffAndValidDefaults() {
        assertFalse(WeatherSettingsCodec.decode("broken").voiceEnabled)
        val changed = RideWeatherSettings(WeatherThresholds(windKmh = 22.0), true)
        assertEquals(changed, WeatherSettingsCodec.decode(WeatherSettingsCodec.encode(changed)))
    }
    @Test fun endpointsRejectInsecureSchemesCredentialsAndEmbeddedQueries() {
        assertTrue(WeatherSettingsCodec.validEndpoint("https://example.com/weather"))
        listOf("http://example.com", "https://a:b@example.com/api", "https://example.com?secret=1", "https://example.com/#x", "file:///tmp/a")
            .forEach { assertFalse(it, WeatherSettingsCodec.validEndpoint(it)) }
    }
    @Test(expected = IllegalArgumentException::class)
    fun invertedTemperatureThresholdsCannotBeSaved() {
        WeatherSettingsCodec.encode(RideWeatherSettings(WeatherThresholds(coldC = 30.0, hotC = 20.0)))
    }
}
