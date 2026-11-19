package org.iz.navigation.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.IzApplication
import org.iz.navigation.weather.RideWeatherStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class RedesignCarTemplateTest {
    @Test fun weatherFallbackAndFinishConfirmationHaveExplicitRecoveryActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as IzApplication
        assertEquals("org.iz.navigation.qaredesign20261119", app.packageName)
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.INTERNET))
        instrumentation.runOnMainSync {
            assertNull(app.navigation.state.value.sessionId)
            assertEquals(RideWeatherStatus.OFF, app.weatherManager.state.value.status)
            val context = TestCarContext.createCarContext(app)
            val weather = CarWeatherScreen(context, refreshWeather = {}).onGetTemplate() as MessageTemplate
            assertEquals(Action.BACK, weather.headerAction)
            assertTrue(weather.message.toString().contains("Hava takibi kapalı"))
            assertEquals(listOf("Yenile", "Telefonda aç"), weather.actions.map { it.title.toString() })

            val finish = CarFinishScreen(context, "synthetic-drive").onGetTemplate() as MessageTemplate
            assertEquals(Action.BACK, finish.headerAction)
            assertEquals(listOf("Bitir", "Geri dön"), finish.actions.map { it.title.toString() })
            assertNull(app.navigation.state.value.sessionId)
        }
    }
}
