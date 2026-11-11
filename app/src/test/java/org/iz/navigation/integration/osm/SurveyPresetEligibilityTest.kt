package org.iz.navigation.integration.osm

import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyPresetEligibilityTest {
    @Test fun existingBathHospitalAndUniversityNodesAndClosedWaysSupportSurvey() {
        for (amenity in listOf("public_bath", "hospital", "university")) {
            for (type in listOf(OsmType.NODE, OsmType.WAY)) {
                val element = OsmCorrectionElement(OsmRef(type, 7), 8, 22, 41,
                    if (type == OsmType.NODE) "39.93" else null,
                    if (type == OsmType.NODE) "32.85" else null,
                    if (type == OsmType.WAY) listOf(1, 2, 3, 1) else emptyList(), mapOf("amenity" to amenity))
                assertEquals(amenity, element.preset().value)
            }
        }
    }
}
