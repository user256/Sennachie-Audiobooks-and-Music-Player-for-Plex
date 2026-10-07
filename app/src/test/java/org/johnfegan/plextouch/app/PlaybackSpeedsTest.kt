package org.johnfegan.plextouch.app

import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.effectiveSpeed
import org.johnfegan.plextouch.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedsTest {
    private val store = FakeStore().apply { speeds[LibraryMode.AUDIOBOOK] = 1.25f }
    private val player = FakePlayer()
    private val speeds = PlaybackSpeeds(store, player)
    private val book = album("10", "Dune")
    private val other = album("20", "Emma")
    private val tracks = listOf(track("11"), track("12"))
    private val scope = store.progressScope(connection)
    private fun playing(album: org.johnfegan.plextouch.data.PlexAlbum = book, mode: LibraryMode = LibraryMode.AUDIOBOOK) =
        PlaybackState(ready = true, album = album, mode = mode, server = connection.serverUrl, accountScope = scope)

    @Test fun choosingASpeedInABookSavesItForThatBookOnly() {
        val profile = speeds.choose(1.75f, playing())
        assertEquals(SpeedProfile(1.25f, 1.75f), profile)
        assertTrue(profile.overridden)
        assertEquals("the default is untouched", 1.25f, store.playbackSpeed(LibraryMode.AUDIOBOOK))
        assertEquals(listOf("speed:1.75"), player.commands)
        assertEquals(1.25f, store.effectiveSpeed(scope, connection.serverUrl, other.id, LibraryMode.AUDIOBOOK))
    }

    @Test fun choosingTheDefaultOrResettingReturnsTheBookToTheDefault() {
        speeds.choose(1.75f, playing())
        assertFalse(speeds.choose(1.25f, playing()).overridden)
        speeds.choose(2f, playing())
        val reset = speeds.reset(playing())
        assertNull(reset.bookOverride)
        assertEquals("speed:1.25", player.commands.last())
        assertEquals(1.25f, store.effectiveSpeed(scope, connection.serverUrl, book.id, LibraryMode.AUDIOBOOK))
    }

    @Test fun aNewDefaultReachesBooksWithoutTheirOwnSpeed() {
        speeds.choose(2f, playing(other))
        speeds.saveDefault(1.5f, playing())
        assertEquals("the loaded book follows the new default at once", "speed:1.5", player.commands.last())
        assertEquals(1.5f, store.effectiveSpeed(scope, connection.serverUrl, book.id, LibraryMode.AUDIOBOOK))
        player.commands.clear()
        assertEquals(SpeedProfile(1.5f, 2f), speeds.saveDefault(1.5f, playing(other)))
        assertTrue("an overridden book keeps its speed", player.commands.isEmpty())
    }

    @Test fun musicKeepsItsSingleSpeed() {
        val profile = speeds.choose(1.5f, playing(mode = LibraryMode.MUSIC))
        assertEquals(1.5f, store.playbackSpeed(LibraryMode.MUSIC))
        assertFalse(profile.overridden)
        assertTrue(store.prefs.isEmpty())
    }

    @Test fun aQueueWithoutAnAccountScopeChangesTheDefaultRatherThanGuessingTheBook() {
        speeds.choose(1.5f, playing().copy(accountScope = null))
        assertTrue(store.prefs.isEmpty())
        assertEquals(1.5f, store.playbackSpeed(LibraryMode.AUDIOBOOK))
    }

    @Test fun playResumeAndTheLastPlayedBarStartAtTheBooksOwnSpeed() = runBlocking {
        val gateway = FakeGateway().apply { trackList = { tracks } }
        val playback = AlbumPlayback(FakeGateways(gateway), player, store)
        speeds.choose(2f, playing())
        playback.play(connection, book, tracks, LibraryMode.AUDIOBOOK, PlaybackState(ready = true), emptyList(), scope, offlineOnly = false)
        assertEquals(2f, player.plays.last().speed)
        playback.play(connection, other, tracks, LibraryMode.AUDIOBOOK, PlaybackState(ready = true), emptyList(), scope, offlineOnly = false)
        assertEquals(1.25f, player.plays.last().speed)
        playback.resume(connection, progress(book, 1, 9_000, scope = scope), null, offlineOnly = false)
        assertEquals(2f, player.plays.last().speed)
    }

    @Test fun anotherAccountPlayingTheSameAlbumUsesTheDefault() {
        speeds.choose(2f, playing())
        store.account = "another-account"
        val otherScope = store.progressScope(connection)
        assertEquals(1.25f, store.effectiveSpeed(otherScope, connection.serverUrl, book.id, LibraryMode.AUDIOBOOK))
        assertNull(speeds.profile(playing().copy(accountScope = otherScope)).bookOverride)
    }
}
