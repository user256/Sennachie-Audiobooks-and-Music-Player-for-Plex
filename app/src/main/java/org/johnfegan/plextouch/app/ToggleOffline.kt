package org.johnfegan.plextouch.app

import org.johnfegan.plextouch.data.SessionStore
import org.johnfegan.plextouch.player.PlaybackController

/** Offline mode cannot stream: a queue holding any streamed track stops, while a fully downloaded one keeps playing. */
class ToggleOffline(private val store: SessionStore, private val player: PlaybackController) {
    /** Saves the preference and returns true when playback had to stop. */
    fun apply(offlineOnly: Boolean): Boolean {
        store.saveOfflineOnly(offlineOnly)
        val stop = offlineOnly && player.queue().any { it.localUri == null }
        if (stop) player.stop()
        return stop
    }
}
