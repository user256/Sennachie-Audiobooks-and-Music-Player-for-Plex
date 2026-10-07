package org.johnfegan.plextouch.player

import androidx.media3.common.C
import androidx.media3.common.Player
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.johnfegan.plextouch.ui.UiText

class PlexPlayerTest {
    @Test fun musicRepeatCyclesFromOffToAlbumToTrackThenOff() {
        assertEquals(Player.REPEAT_MODE_ALL, nextMusicRepeatMode(Player.REPEAT_MODE_OFF))
        assertEquals(Player.REPEAT_MODE_ONE, nextMusicRepeatMode(Player.REPEAT_MODE_ALL))
        assertEquals(Player.REPEAT_MODE_OFF, nextMusicRepeatMode(Player.REPEAT_MODE_ONE))
    }

    @Test fun skipClampsToKnownDuration() {
        assertEquals(60_000L, skipTarget(30_000, 30_000, 120_000))
        assertEquals(120_000L, skipTarget(100_000, 30_000, 120_000))
        assertEquals(0L, skipTarget(10_000, -30_000, 120_000))
    }

    @Test fun streamedMediaItemUriNeverCarriesTheToken() {
        val track = PlexTrack("11", "One", "Narrator", "Book", 1000, "/library/parts/1/file.mp3?download=1", "mp3")
        val connection = PlexConnection("http://plex.local:32400/", "secret-token")
        val uri = playbackUri(track, connection.serverUrl)
        assertEquals("http://plex.local:32400/library/parts/1/file.mp3?download=1", uri)
        assertFalse(uri.contains("X-Plex-Token") || uri.contains("secret-token"))
        // The Sonos handoff is the only place the token belongs in a URL.
        assertTrue(connection.streamUrl(track.streamPath).contains("&X-Plex-Token=secret-token"))
    }

    @Test fun skipWhileDurationUnknownSeeksRelativeToPosition() {
        assertEquals(60_000L, skipTarget(30_000, 30_000, C.TIME_UNSET))
        assertEquals(60_000L, skipTarget(30_000, 30_000, 0))
        assertEquals(0L, skipTarget(10_000, -30_000, C.TIME_UNSET))
    }

    @Test fun historyIsReloadedOnlyWhenTheServiceWillHaveSavedProgress() {
        val track = PlexTrack("11", "One", "Narrator", "Book", 1000, "/file.mp3", "mp3")
        val album = PlexAlbum("1", "Book", "Narrator", null, 2)
        val playing = PlaybackState(ready = true, album = album, track = track, playing = true, trackCount = 2)
        assertFalse(progressSaved(playing, playing))
        // Speed, repeat, shuffle, the sleep timer and an error banner change nothing the service persists.
        assertFalse(progressSaved(playing, playing.copy(speed = 1.5f, repeat = true, shuffle = true, sleepEndsAt = 9, error = UiText.Raw("x"))))
        assertTrue(progressSaved(playing, playing.copy(playing = false)))
        assertTrue(progressSaved(playing, playing.copy(buffering = true)))
        assertTrue(progressSaved(playing, playing.copy(trackIndex = 1)))
        assertTrue(progressSaved(playing, playing.copy(track = track.copy(id = "12"))))
        assertTrue(progressSaved(playing, playing.copy(album = album.copy(id = "2"))))
        assertTrue(progressSaved(PlaybackState(), playing))
    }
}
