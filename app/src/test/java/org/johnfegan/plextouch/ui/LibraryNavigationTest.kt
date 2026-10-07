package org.johnfegan.plextouch.ui

import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The back-gesture and mini-player decisions `LibraryShell` makes, as pure functions. These stand in for Compose UI tests,
 * which cannot be built offline (no `ui-test-junit4` in the Gradle cache).
 */
class LibraryNavigationTest {
    private val book = PlexAlbum("10", "Dune", "Frank Herbert", 1965, 3)

    /** Applies each back action the way `LibraryShell` does, returning the order they fired in. */
    private fun unwind(start: PlexTouchUiState): List<BackAction> {
        var state = start
        val order = mutableListOf<BackAction>()
        while (true) {
            val action = backAction(state) ?: return order
            order += action
            state = when (action) {
                BackAction.CLOSE_PLAYER -> state.copy(showPlayer = false)
                BackAction.CLOSE_SETTINGS -> state.copy(showSettings = false)
                BackAction.CLOSE_ALBUM -> state.copy(selectedAlbum = null)
                BackAction.CLEAR_ARTIST -> state.copy(selectedArtist = null)
                BackAction.CLOSE_BOOK_GROUP -> state.copy(shelves = state.shelves.copy(selected = null))
                BackAction.HOME_TAB -> state.copy(tab = LibraryTab.HOME)
            }
        }
    }

    @Test fun backUnwindsThePlayerThenSettingsThenTheAlbumThenTheTab() {
        val layered = PlexTouchUiState(showPlayer = true, showSettings = true, selectedAlbum = book, tab = LibraryTab.LIBRARY, playback = PlaybackState(ready = true, album = book))
        assertEquals(listOf(BackAction.CLOSE_PLAYER, BackAction.CLOSE_SETTINGS, BackAction.CLOSE_ALBUM, BackAction.HOME_TAB), unwind(layered))
    }

    @Test fun backLeavesTheAppOnlyFromAnUnlayeredHomeTab() {
        assertNull(backAction(PlexTouchUiState()))
        assertNull(backAction(PlexTouchUiState(tab = LibraryTab.HOME, selectedArtist = "x")))
        assertEquals(BackAction.HOME_TAB, backAction(PlexTouchUiState(tab = LibraryTab.DOWNLOADS)))
        assertEquals(BackAction.CLOSE_ALBUM, backAction(PlexTouchUiState(selectedAlbum = book)))
    }

    @Test fun anOpenArtistClosesBeforeTheLibraryTabDoes() {
        val artist = PlexTouchUiState(tab = LibraryTab.LIBRARY, selectedArtist = "radiohead", musicCollection = MusicCollectionTab.ARTISTS)
        assertEquals(listOf(BackAction.CLEAR_ARTIST, BackAction.HOME_TAB), unwind(artist))
        assertEquals("an artist key left over on another tab does not block it", BackAction.HOME_TAB, backAction(artist.copy(tab = LibraryTab.SEARCH)))
    }

    @Test fun miniPlayerShowsOnlyWhilePlaybackMatchesTheModeBeingBrowsed() {
        val playingBook = PlaybackState(ready = true, album = book, mode = LibraryMode.AUDIOBOOK, playing = true)
        assertTrue(showsMiniPlayer(PlexTouchUiState(mode = LibraryMode.AUDIOBOOK, playback = playingBook)))
        assertFalse("a book playing under the music tab yields to the last-played bar", showsMiniPlayer(PlexTouchUiState(mode = LibraryMode.MUSIC, playback = playingBook)))
        assertFalse("nothing loaded, nothing shown", showsMiniPlayer(PlexTouchUiState(mode = LibraryMode.AUDIOBOOK, playback = PlaybackState(ready = true))))
        assertTrue("paused still counts as loaded", showsMiniPlayer(PlexTouchUiState(mode = LibraryMode.AUDIOBOOK, playback = playingBook.copy(playing = false))))
    }
}
