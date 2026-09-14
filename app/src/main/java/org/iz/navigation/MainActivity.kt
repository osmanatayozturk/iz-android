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
        (application as IzApplication).healthManager.setForeground(true)
        org.iz.navigation.health.DailyActivityManager.get(this).setForeground(true)
    }
    override fun onStop() {
        (application as IzApplication).healthManager.setForeground(false)
        org.iz.navigation.health.DailyActivityManager.get(this).setForeground(false)
        super.onStop()
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
