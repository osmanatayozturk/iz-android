package com.atay.iz.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal object WatchHealthResumePolicy {
    fun shouldResume(armed: Boolean, permissionsGranted: Boolean, serviceRunning: Boolean) =
        armed && permissionsGranted && !serviceRunning
    fun canReuseClock(savedBoot: Int, currentBoot: Int) = savedBoot >= 0 && savedBoot == currentBoot
}

/** Recovery preserves the user's opt-in; foreground restrictions can defer it until WatchActivity resumes. */
class WatchHealthRecovery : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            WatchHealthRuntime.resume(context)
    }
}
