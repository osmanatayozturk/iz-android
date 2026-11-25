package org.iz.navigation.moto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

private const val MOTORCYCLE_QA_PACKAGE = "org.iz.navigation.qamotosiklet20261125"
private const val GARAGE_MENU_QA_PACKAGE = "org.iz.navigation.qagarajmenu20261125"
private const val FUEL_HISTORY_QA_PACKAGE = "org.iz.navigation.qayakitgecmis20261126"

internal fun requireMotorcycleQaTarget(): Context = requireOfflineQaTarget(setOf(MOTORCYCLE_QA_PACKAGE))

internal fun requireGarageMenuQaTarget(): Context =
    requireOfflineQaTarget(setOf(GARAGE_MENU_QA_PACKAGE, FUEL_HISTORY_QA_PACKAGE))

internal fun requireFuelHistoryQaTarget(): Context = requireOfflineQaTarget(setOf(FUEL_HISTORY_QA_PACKAGE))

internal fun requireGarageQaTarget(): Context =
    requireOfflineQaTarget(setOf(MOTORCYCLE_QA_PACKAGE, GARAGE_MENU_QA_PACKAGE, FUEL_HISTORY_QA_PACKAGE))

private fun requireOfflineQaTarget(allowedPackages: Set<String>): Context {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue("Unexpected QA package", app.packageName in allowedPackages)
    assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
    assertEquals(1, Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
    assertEquals(0, Settings.Global.getInt(app.contentResolver, Settings.Global.WIFI_ON))
    @Suppress("DEPRECATION")
    val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions.orEmpty()
    assertFalse(Manifest.permission.INTERNET in permissions)
    return app
}
