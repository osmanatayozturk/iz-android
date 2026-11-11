package org.iz.navigation.car

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NavigationIntentsTest {
    @Test fun originalHttpsAppActionHasNarrowPackageVisibility() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"),File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val queries = document.getElementsByTagName("queries").item(0) as org.w3c.dom.Element
        val intents = queries.getElementsByTagName("intent")
        assertTrue((0 until intents.length).any { index ->
            val element = intents.item(index) as org.w3c.dom.Element
            val actions = element.getElementsByTagName("action")
            val data = element.getElementsByTagName("data")
            (0 until actions.length).any { (actions.item(it) as org.w3c.dom.Element).getAttribute("android:name") == "android.intent.action.VIEW" } &&
                (0 until data.length).any { (data.item(it) as org.w3c.dom.Element).getAttribute("android:scheme") == "https" }
        })
        val permissions=document.getElementsByTagName("uses-permission")
        assertFalse((0 until permissions.length).any {
            (permissions.item(it) as org.w3c.dom.Element).getAttribute("android:name") == "android.permission.QUERY_ALL_PACKAGES"
        })
    }
    @Test fun explicitGoogleDestinationIsAcceptedWithoutUsingCameraCenter() {
        val target = NavigationIntents.parse("https://www.google.com/maps/dir/?api=1&destination=41.02%2C29.01&center=5%2C6")
        assertNotNull(target)
        assertEquals(41.02, target!!.coordinate!!.latitude, 0.00001)
        assertEquals(29.01, target.coordinate.longitude, 0.00001)
    }
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
