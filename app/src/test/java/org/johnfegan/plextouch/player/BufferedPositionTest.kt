package org.johnfegan.plextouch.player

import androidx.media3.common.C
import org.johnfegan.plextouch.ui.SeekSegments
import org.johnfegan.plextouch.ui.seekSegments
import org.junit.Assert.assertEquals
import org.junit.Test

class BufferedPositionTest {
    @Test fun streamReportsMedia3BufferedPosition() {
        assertEquals(PlaybackPosition(10_000, 60_000, 25_000), playbackPosition(10_000, 60_000, 25_000, local = false))
    }

    @Test fun bufferedNeverPassesAKnownDuration() {
        assertEquals(60_000L, playbackPosition(10_000, 60_000, 90_000, local = false).bufferedMs)
    }

    @Test fun unknownDurationIsZeroAndKeepsBufferedNonNegative() {
        val position = playbackPosition(-5, C.TIME_UNSET, C.TIME_UNSET, local = false)
        assertEquals(PlaybackPosition(0, 0, 0), position)
        assertEquals(4_000L, playbackPosition(1_000, C.TIME_UNSET, 4_000, local = false).bufferedMs)
    }

    @Test fun localFileIsFullyBuffered() {
        assertEquals(60_000L, playbackPosition(10_000, 60_000, 12_000, local = true).bufferedMs)
        // Until the file's duration is known there is nothing to fill.
        assertEquals(0L, playbackPosition(0, C.TIME_UNSET, C.TIME_UNSET, local = true).bufferedMs)
    }

    @Test fun segmentsAreFractionsOfTheDuration() {
        assertEquals(SeekSegments(.25f, .5f), seekSegments(15_000, 30_000, 60_000))
        assertEquals(SeekSegments(1f, 1f), seekSegments(60_000, 60_000, 60_000))
    }

    @Test fun segmentsClampAndBufferedNeverTrailsPlayed() {
        assertEquals(SeekSegments(.5f, .5f), seekSegments(30_000, 10_000, 60_000))
        assertEquals(SeekSegments(1f, 1f), seekSegments(90_000, 120_000, 60_000))
        assertEquals(SeekSegments(0f, .5f), seekSegments(-1, 30_000, 60_000))
    }

    @Test fun unknownDurationDrawsAnEmptyTrack() {
        assertEquals(SeekSegments(0f, 0f), seekSegments(10_000, 20_000, 0))
    }
}
