package org.iz.navigation.integration

import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionsPlaceRankingTest {
    private val origin = WeatherCoordinate(41.012345, 29.012345)
    private val ankara = WeatherCoordinate(39.93, 32.85)
    private val local = place(363072750, "Yahya Çavuş İlkokulu", 39.94, 32.86, "Ankara")
    private val distant = place(6, "Yahya Çavuş", 41.01, 29.01, "İstanbul")

    @Test fun ranksSameNameByDistanceWithoutRemovingOtherCityOrChangingOsmIdentity() {
        val sameName = distant.copy(name = local.name)
        assertEquals(listOf(local, sameName), rankDirectionsPlaces(listOf(sameName, local), ankara))
        assertEquals(listOf(sameName, local), rankDirectionsPlaces(listOf(sameName, local), null))
        assertEquals(OsmRef(OsmType.WAY, 363072750), local.osmRef)
    }

    @Test fun fartherExactNameRemainsAfterTheNearerPartialMatch() {
        assertEquals(listOf(local, distant), rankDirectionsPlaces(listOf(distant, local), ankara))
    }

    @Test fun equalDistanceKeepsServiceOrderAndTheSameNameIsNotADuplicate() {
        val samePoint = local.copy(osmRef = OsmRef(OsmType.WAY, 8))
        assertEquals(listOf(samePoint, local), rankDirectionsPlaces(listOf(samePoint, local), ankara))
    }

    @Test fun roundsTheCenterAndSkipsInvalidOrCrossingBoxes() {
        assertEquals("28.75,41.20,29.25,40.80", DirectionsViewbox.around(origin)?.value)
        assertEquals("-29.35,-40.90,-28.85,-41.30",
            DirectionsViewbox.around(WeatherCoordinate(-41.05, -29.05))?.value)
        assertNull(DirectionsViewbox.around(WeatherCoordinate(89.9, 1.0)))
        assertNull(DirectionsViewbox.around(WeatherCoordinate(41.0, 179.9)))
    }

    private fun place(id: Long, name: String, lat: Double, lon: Double, city: String) =
        SelectedOsmPlace(name, lat, lon, OsmRef(OsmType.WAY, id), "$name, $city")
}
