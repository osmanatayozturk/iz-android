package org.iz.navigation

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.iz.navigation.tracking.TrackingController
import org.iz.navigation.ui.DiaryApp
import org.iz.navigation.ui.IzTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import org.iz.navigation.power.PostJourneyAnalysis
import org.iz.navigation.power.PowerDiagnostics
import java.io.FileDescriptor
import java.io.PrintWriter

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Intent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveIntent(intent)
        lifecycleScope.launch {
            runCatching { TrackingController(this@MainActivity).recoverInterrupted() }
        }
        setContent { IzTheme { DiaryApp(incoming, consumeIntent = { incoming = null }) } }
        // Own the window policy here so internal screens and dialogs share it.
        lifecycleScope.launch {
            val guidance = (application as IzApplication).navigation.state.map { it.guidance }
            lifecycle.keepScreenOnWhileGuiding(guidance) { keepScreenOn ->
                if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                (application as IzApplication).community.refreshNotificationSchedule()
                TrackingController(this@MainActivity).restoreDetection()
                val key = (application as IzApplication).wearBridge.pendingKey()
                if (key != null) startActivity(Intent(this@MainActivity, org.iz.navigation.wear.WearStartActivity::class.java)
                    .putExtra("requestKey", key))
            }
        }
    }
    override fun onStart() {
        super.onStart()
        PowerDiagnostics.foreground(true)
        PostJourneyAnalysis.get(this).setForeground(true)
        (application as IzApplication).healthManager.setForeground(true)
        org.iz.navigation.health.DailyActivityManager.get(this).setForeground(true)
    }
    override fun onStop() {
        PowerDiagnostics.foreground(false)
        PostJourneyAnalysis.get(this).setForeground(false)
        (application as IzApplication).healthManager.setForeground(false)
        org.iz.navigation.health.DailyActivityManager.get(this).setForeground(false)
        super.onStop()
    }
    // No UI action or new exported component. The platform's existing Activity.dump path is read-only.
    // https://developer.android.com/reference/android/app/Activity#dump(java.lang.String,%20java.io.FileDescriptor,%20java.io.PrintWriter,%20java.lang.String[])
    override fun dump(prefix: String, fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        if (args?.contentEquals(arrayOf(PowerDiagnostics.DUMP_ARGUMENT)) == true) PowerDiagnostics.dump(writer)
        else super.dump(prefix, fd, writer, args)
    }
    private fun receiveIntent(value: Intent?) {
        val uri = value?.data
        if (uri?.scheme == "iz" && uri.host == "group") {
            (application as IzApplication).groups.handleInvite(uri)
            incoming = null
        } else {
            incoming = value
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIntent(intent)
    }
}
