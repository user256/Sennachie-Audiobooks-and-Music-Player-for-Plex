package org.johnfegan.plextouch.ui

/** What the system back gesture does in `LibraryShell`, outermost layer first. */
enum class BackAction { CLOSE_PLAYER, CLOSE_SETTINGS, CLOSE_ALBUM, CLEAR_ARTIST, CLOSE_BOOK_GROUP, HOME_TAB }

/** Null when back should leave the app: the home tab with nothing layered over it. */
fun backAction(state: PlexTouchUiState): BackAction? = when {
    state.showPlayer -> BackAction.CLOSE_PLAYER
    state.showSettings -> BackAction.CLOSE_SETTINGS
    state.selectedAlbum != null -> BackAction.CLOSE_ALBUM
    state.tab == LibraryTab.HOME -> null
    state.selectedArtist != null && state.tab == LibraryTab.LIBRARY -> BackAction.CLEAR_ARTIST
    // Ticket 131: an open collection or series goes back to its list first.
    state.tab == LibraryTab.LIBRARY && state.personalShelf in BOOK_SHELVES && state.currentShelves.selected != null -> BackAction.CLOSE_BOOK_GROUP
    else -> BackAction.HOME_TAB
}

/** The mini-player belongs to the mode being browsed; a book playing under the music tab shows the last-played bar instead. */
fun showsMiniPlayer(state: PlexTouchUiState): Boolean = state.playback.album != null && state.playback.mode == state.mode
