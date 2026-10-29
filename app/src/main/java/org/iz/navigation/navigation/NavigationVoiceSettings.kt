package org.iz.navigation.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/** Only called on explicit action; the UI owns the transient result/entry binding and silent retry. */
object NavigationVoiceSettings {
    fun open(context: Context, launch: (Intent) -> Unit): Boolean {
        val actions = listOf("com.android.settings.TTS_SETTINGS", Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS)
        for ((index, action) in actions.withIndex()) {
            try {
                launch(Intent(action))
                if (index > 0) Toast.makeText(context,
                    "Seslendirme sayfası açılamadı. Ayarlarda metin okuma veya metinden konuşma bölümünü bul.", Toast.LENGTH_LONG).show()
                return true
            } catch (_: ActivityNotFoundException) {
                // Vendor settings may not expose the AOSP speech action; use the supported general fallback.
            } catch (_: SecurityException) {
                // An exported settings surface is not guaranteed on every device/profile.
            }
        }
        Toast.makeText(context, "Ayarlar açılamadı. Telefon ayarlarından metin okuma bölümünü kontrol et.", Toast.LENGTH_LONG).show()
        return false
    }
}
