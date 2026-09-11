package com.atay.iz.car

import org.junit.Assert.*
import org.junit.Test

class NavigationIntentsTest {
    @Test fun coordinateIntentPreservesLatitudeLongitudeAndLabel() {
        val target = NavigationIntents.parse("geo:0,0?q=41.02%2C29.01%28Kad%C4%B1k%C3%B6y%29")!!
        assertEquals(41.02, target.coordinate!!.latitude, 0.00001)
        assertEquals(29.01, target.coordinate.longitude, 0.00001)
        assertEquals("Kadıköy", target.query)
    }
    @Test fun geoCoordinatesWithoutQueryAndAltitudeParameter() {
        val target = NavigationIntents.parse("geo:-33.86,151.20;u=35")!!
        assertEquals(-33.86, target.coordinate!!.latitude, 0.00001)
    }
    @Test fun addressNeedsExplicitSearchAndDoesNotInventCoordinate() {
        val target = NavigationIntents.parse("google.navigation:q=Ba%C4%9Fdat+Caddesi+%C4%B0stanbul&mode=d")!!
        assertNull(target.coordinate)
        assertEquals("Bağdat Caddesi İstanbul", target.query)
    }
    @Test fun invalidInputsAreRejectedWithoutException() {
        listOf(null, "https://maps.example/41,29", "geo:91,29", "geo:NaN,2", "geo:1,Infinity",
            "geo:1", "google.navigation:", "geo:0,0?q=%XX", "geo:0,0?q=91,29",
            "geo:0,0?q=%0aevil", "geo:0,0?q=" + "a".repeat(201)).forEach { assertNull(it, NavigationIntents.parse(it)) }
    }
    @Test fun zeroZeroRemainsARealCoordinate() {
        assertEquals(0.0, NavigationIntents.parse("geo:0,0")!!.coordinate!!.latitude, 0.0)
    }
}
