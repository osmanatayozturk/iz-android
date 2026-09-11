package com.atay.iz.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-registers movement observations after reboot/update, never starts a location service. */
class DetectionRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = TrackerSettings(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) settings.resetActivitySessionAfterBoot()
        if (!settings.enabled) return
        val pending = goAsync()
        TrackingController(context).restoreDetection { pending.finish() }
    }
}
