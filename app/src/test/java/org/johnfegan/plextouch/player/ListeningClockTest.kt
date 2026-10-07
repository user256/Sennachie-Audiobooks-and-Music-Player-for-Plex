package org.johnfegan.plextouch.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningClockTest {
    @Test fun measuresPlayingTimeBetweenTakesAndStopsWhenPaused() {
        val clock = ListeningClock(maxDeltaMs = 10_000)
        assertEquals(0, clock.take(1_000, playing = true)) // starts
        assertEquals(5_000, clock.take(6_000, playing = true))
        assertEquals(3_000, clock.take(9_000, playing = false)) // paused: the last stretch, then stopped
        assertEquals(0, clock.take(60_000, playing = false))
        assertEquals(0, clock.take(70_000, playing = true))
        assertEquals(5_000, clock.take(75_000, playing = true))
    }

    @Test fun aStalledHeartbeatIsCappedAndABackwardsClockAddsNothing() {
        val clock = ListeningClock(maxDeltaMs = 10_000)
        clock.take(0, playing = true)
        assertEquals(10_000, clock.take(3_600_000, playing = true))
        assertEquals(0, clock.take(3_500_000, playing = true))
    }
}
