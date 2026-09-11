package com.atay.iz

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.atay.iz.tracking.TrackingController
import com.atay.iz.ui.DiaryApp
import com.atay.iz.ui.IzTheme
import kotlinx.coroutines.launch

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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                (application as IzApplication).community.refreshNotificationSchedule()
                TrackingController(this@MainActivity).restoreDetection()
                val key = (application as IzApplication).wearBridge.pendingKey()
                if (key != null) startActivity(Intent(this@MainActivity, com.atay.iz.wear.WearStartActivity::class.java)
                    .putExtra("requestKey", key))
            }
        }
    }
    override fun onStart() {
        super.onStart()
        (application as IzApplication).healthManager.setForeground(true)
    }
    override fun onStop() {
        (application as IzApplication).healthManager.setForeground(false)
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
