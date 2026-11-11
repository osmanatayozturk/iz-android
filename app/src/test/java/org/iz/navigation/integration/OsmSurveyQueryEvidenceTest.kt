package org.iz.navigation.integration

import java.io.File
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmSurveyQueryEvidenceTest {
    @Test fun productionBuilderExportsOneRepresentativeQueryWithSyntheticPublicOrigin() {
        val origin = WeatherCoordinate(39.93,32.85)
        val query = osmSurveyQuery(origin)
        assertTrue(query.contains("[timeout:12]") && query.contains("[maxsize:16777216]"))
        assertTrue(query.contains("out meta center 100"))
        val output = File("build/osm-survey/production-query.json")
        requireNotNull(output.parentFile).mkdirs()
        output.writeText(JSONObject().put("originKind","SYNTHETIC_PUBLIC_NOT_DEVICE_GPS")
            .put("originLatitude",origin.latitude).put("originLongitude",origin.longitude)
            .put("ql",query).toString(2))
    }
}
