package org.iz.navigation.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An opt-in for the phone display, independent of storing sensor data for a report. */
internal class LiveLeanDisplaySettings private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("live_lean_display", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = mutable.asStateFlow()

    @Synchronized
    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        mutable.value = value
    }

    companion object {
        @Volatile private var instance: LiveLeanDisplaySettings? = null

        fun get(context: Context): LiveLeanDisplaySettings = instance ?: synchronized(this) {
            instance ?: LiveLeanDisplaySettings(context.applicationContext).also { instance = it }
        }
    }
}
