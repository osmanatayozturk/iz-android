package org.iz.navigation.integration

import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class RouteCorridorPlacesTest {
    private fun route(count: Int = 201): PlannedRoute {
        val vertices = (0 until count).map { RouteVertex(WeatherCoordinate(0.0, it * 0.001), it.toDouble()) }
        return PlannedRoute("route", listOf(RouteStop("A", vertices.first().coordinate), RouteStop("B", vertices.last().coordinate)),
            vertices, WeatherEngine.distanceMeters(vertices.first().coordinate, vertices.last().coordinate),
            vertices.last().elapsedSeconds, 1)
    }
    @Test fun forwardSliceStopsAtTenKilometersAndAtMost32QueryVertices() {
        val slice = requireNotNull(RouteCorridorPlaces.slice(route(), 50.0))
        assertEquals("Only the next ten kilometres may be queried", 10_000.0, slice.distanceMeters, 0.1)
        assertTrue(slice.points.size in 2..32)
        assertEquals(0.05, slice.points.first().longitude, 0.000001)
        assertTrue(slice.points.last().longitude < 0.15)
    }
    @Test fun queryIsExplicitFuelAndRestAlongTheBoundedForwardLine() {
        val slice = requireNotNull(RouteCorridorPlaces.slice(route(), 50.0))
        val query = RouteCorridorPlaces.query(slice)
        assertTrue(query.contains("fuel"))
        assertTrue(query.contains("services") && query.contains("rest_area"))
        assertTrue(query.contains("around:"))
        assertTrue("Node coordinates must be requested", query.endsWith("out body center qt 200;"))
        assertTrue(query.length < 6000)
        assertFalse(query.contains("nominatim") || query.contains("shop"))
    }
    @Test fun passedAndDistantResultsAreDroppedAndOsmIdentityIsDeduplicated() {
        val slice = requireNotNull(RouteCorridorPlaces.slice(route(31), 10.0))
        val forward = SelectedOsmPlace("Fuel", 0.0, 0.011, OsmRef(OsmType.NODE, 2))
        val result = RouteCorridorPlaces.project(slice, listOf(
            SelectedOsmPlace("Passed", 0.0, 0.009, OsmRef(OsmType.NODE, 1)),
            forward, forward.copy(name = "Duplicate"),
            SelectedOsmPlace("Far", 0.02, 0.012, OsmRef(OsmType.NODE, 3)),
        ))
        assertEquals(listOf(forward), result.map { it.place })
        assertEquals(0.0, result.single().offsetMeters, 1.0)
        assertEquals(111.2, result.single().forwardMeters, 2.0)
    }
    @Test fun sameNumericIdDifferentOsmTypesRemainDifferentCandidates() {
        val slice = requireNotNull(RouteCorridorPlaces.slice(route(31), 10.0))
        val node = SelectedOsmPlace("Fuel", 0.0, 0.011, OsmRef(OsmType.NODE, 2))
        val way = node.copy(osmRef = OsmRef(OsmType.WAY, 2))
        assertEquals(2, RouteCorridorPlaces.project(slice, listOf(node, way)).size)
    }
    @Test fun parseIncludesUnnamedRestAreasAndOnlyActualReadableAddress() {
        val result = RouteCorridorPlaces.parse("""{"elements":[
          {"type":"node","id":1,"lat":0,"lon":0.011,"tags":{"amenity":"fuel","name":"Yakıt","addr:street":"Örnek Sokak","addr:housenumber":"3"}},
          {"type":"way","id":2,"center":{"lat":0,"lon":0.012},"tags":{"highway":"rest_area"}},
          {"type":"node","id":3,"lat":0,"lon":0.013,"tags":{"shop":"books","name":"Not a stop"}}
        ]}""")
        assertEquals(2, result.size)
        assertTrue(result.first().displayName!!.contains("Örnek Sokak"))
        assertTrue(result.first().displayName!!.contains("3"))
        assertEquals("rest area", result[1].name)
        assertNull(result[1].displayName)
    }
    @Test fun invalidOrFinishedProgressNeverBecomesAPublicQuery() {
        for (elapsed in listOf(Double.NaN, -1.0, 201.0))
            assertThrows(IllegalArgumentException::class.java) { RouteCorridorPlaces.slice(route(), elapsed) }
        assertNull(RouteCorridorPlaces.slice(route(), 200.0))
        val crossing = route(2).copy(vertices = listOf(RouteVertex(WeatherCoordinate(0.0, 179.99), 0.0),
            RouteVertex(WeatherCoordinate(0.0, -179.99), 1.0)))
        assertThrows(IllegalArgumentException::class.java) { RouteCorridorPlaces.slice(crossing, 0.0) }
    }
    @Test fun categoryUsesOnlyActualTagsEvenWhenLocalizedNamesSuggestAnotherKind() {
        val typed = RouteCorridorPlaces.parseTyped("""{"elements":[
          {"type":"node","id":1,"lat":0,"lon":0.011,"tags":{"amenity":"fuel","name:tr":"Dinlenme yeri"}},
          {"type":"way","id":1,"center":{"lat":0,"lon":0.012},"tags":{"highway":"rest_area","name":"Yakıt"}},
          {"type":"node","id":3,"lat":0,"lon":0.013,"tags":{"name":"fuel rest_area"}}
        ]}""")
        assertEquals(listOf(RouteCorridorKind.FUEL, RouteCorridorKind.REST), typed.map { it.kind })
        assertEquals(listOf(OsmRef(OsmType.NODE, 1), OsmRef(OsmType.WAY, 1)), typed.map { it.place.osmRef })
    }

    @Test fun projectionDoesNotInferKindFromNameOrAnotherOsmTypeWithTheSameId() {
        val slice = requireNotNull(RouteCorridorPlaces.slice(route(31), 10.0))
        val node = SelectedOsmPlace("Yakıt fuel", 0.0, 0.011, OsmRef(OsmType.NODE, 2))
        val way = node.copy(osmRef = OsmRef(OsmType.WAY, 2))
        assertEquals(RouteCorridorKind.UNKNOWN, RouteCorridorPlaces.project(slice, listOf(node)).single().kind)
        val typed = RouteCorridorPlaces.project(slice, listOf(node, way),
            mapOf(OsmRef(OsmType.WAY, 2) to RouteCorridorKind.REST))
        assertEquals(mapOf(node.osmRef to RouteCorridorKind.UNKNOWN, way.osmRef to RouteCorridorKind.REST),
            typed.associate { it.place.osmRef to it.kind })
    }
    @Test fun incompleteOrErrorResponsesAreNeverCachedAsSuccessfulEmptySearches() {
        assertThrows(IllegalArgumentException::class.java) { RouteCorridorPlaces.parseTyped("{}") }
        assertThrows(IllegalArgumentException::class.java) {
            RouteCorridorPlaces.parseTyped("""{"elements":[],"remark":"runtime error: Query timed out"}""")
        }
        assertTrue(RouteCorridorPlaces.parseTyped("""{"elements":[]}""").isEmpty())
    }
}
