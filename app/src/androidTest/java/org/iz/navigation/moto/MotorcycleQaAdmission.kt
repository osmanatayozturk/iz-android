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

internal fun requireMotorcycleQaTarget(): Context {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("org.iz.navigation.qamotosiklet20261125", app.packageName)
    assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
    assertEquals(1, Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
    assertEquals(0, Settings.Global.getInt(app.contentResolver, Settings.Global.WIFI_ON))
    @Suppress("DEPRECATION")
    val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions.orEmpty()
    assertFalse(Manifest.permission.INTERNET in permissions)
    return app
}
