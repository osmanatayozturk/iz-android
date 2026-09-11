package org.iz.navigation.integration.osm

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Private PendingIntent destination. AppAuth's receiver owns the exported browser redirect. */
class OsmOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        complete(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        complete(intent)
    }
    private fun complete(result: Intent) {
        OsmAuthManager.get(this).handleAuthorizationResult(result)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
