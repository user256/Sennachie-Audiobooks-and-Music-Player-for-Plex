package org.johnfegan.plextouch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Downloads screen's offline switch calls `PlexTouchViewModel.setOfflineOnly`, which applies this use case; these are
 * the JVM stand-in for the Compose switch test (no Compose UI test artifacts are available offline).
 */
class ToggleOfflineTest {
    private val store = FakeStore()
    private val player = FakePlayer()
    private val toggle = ToggleOffline(store, player)

    @Test fun offlineSwitchStopsAQueueHoldingAStreamedTrack() {
        player.queued = listOf(track("1", local = true), track("2", local = false))
        assertTrue(toggle.apply(true))
        assertEquals(listOf("stop"), player.commands)
        assertTrue(store.offline)
    }

    @Test fun offlineSwitchLeavesAFullyDownloadedQueuePlaying() {
        player.queued = listOf(track("1", local = true), track("2", local = true))
        assertFalse(toggle.apply(true))
        assertTrue(player.commands.isEmpty())
        assertTrue(store.offline)
    }

    @Test fun switchingBackOnlineNeverStopsPlayback() {
        store.offline = true
        player.queued = listOf(track("1", local = false))
        assertFalse(toggle.apply(false))
        assertTrue(player.commands.isEmpty())
        assertFalse(store.offline)
    }
}
