package org.johnfegan.plextouch.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The watch app's single activity (ticket 141). Each time it comes to the front it checks how the phone can be reached,
 * asks the phone for fresh state and delivers any queued positions; while it is closed nothing runs.
 */
class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchRepository.load(this)
        setContent { WatchApp() }
    }

    override fun onStart() {
        super.onStart()
        val app = applicationContext
        lifecycleScope.launch {
            if (PhoneLink.refresh(app) == PhoneReach.CONNECTED) {
                PhoneLink.requestState(app)
                PhoneLink.flushPending(app)
            }
        }
    }
}
