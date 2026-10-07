package org.johnfegan.plextouch.app

import androidx.compose.runtime.Immutable
import org.johnfegan.plextouch.data.BookSpeeds
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexStore
import org.johnfegan.plextouch.data.ProgressStore
import org.johnfegan.plextouch.player.PlaybackController
import org.johnfegan.plextouch.player.PlaybackState

/** The audiobook default speed and, while a book is loaded, that book's own override (null when it follows the default). */
@Immutable
data class SpeedProfile(val defaultSpeed: Float = 1f, val bookOverride: Float? = null) {
    val overridden: Boolean get() = bookOverride != null
}

/**
 * Speed choices (ticket 133). In an audiobook the player's speed menu saves that book's own speed; Settings changes the
 * default every other book follows. Music keeps a single per-mode speed. The book is identified by the account scope and
 * server carried by the playing queue, so a change can never land on another account's copy of the same album.
 */
class PlaybackSpeeds(private val store: ProgressStore, private val player: PlaybackController) {
    private fun book(playback: PlaybackState): Triple<String, String, String>? {
        if (playback.mode != LibraryMode.AUDIOBOOK) return null
        val album = playback.album ?: return null
        val scope = playback.accountScope?.takeIf { it.isNotBlank() } ?: return null
        val server = playback.server?.takeIf { it.isNotBlank() } ?: return null
        return Triple(scope, server, album.id)
    }

    fun profile(playback: PlaybackState): SpeedProfile {
        val default = store.playbackSpeed(LibraryMode.AUDIOBOOK)
        val override = book(playback)?.let { (scope, server, albumId) -> store.bookSpeed(scope, server, albumId) }
        return SpeedProfile(default, override)
    }

    /** The speed menu in the player. */
    fun choose(value: Float, playback: PlaybackState): SpeedProfile {
        val speed = value.coerceIn(PlexStore.MIN_SPEED, PlexStore.MAX_SPEED)
        val book = book(playback)
        if (book == null) store.savePlaybackSpeed(playback.mode, speed)
        else store.saveBookSpeed(book.first, book.second, book.third, BookSpeeds.overrideFor(speed, store.playbackSpeed(LibraryMode.AUDIOBOOK)))
        player.speed(speed)
        return profile(playback)
    }

    /** The one-tap reset in the player: forget the book's own speed and play at the default again. */
    fun reset(playback: PlaybackState): SpeedProfile {
        val (scope, server, albumId) = book(playback) ?: return profile(playback)
        store.saveBookSpeed(scope, server, albumId, null)
        player.speed(store.playbackSpeed(LibraryMode.AUDIOBOOK))
        return profile(playback)
    }

    /** Settings' default audiobook speed; a loaded book without its own speed switches to it straight away. */
    fun saveDefault(value: Float, playback: PlaybackState): SpeedProfile {
        val speed = value.coerceIn(PlexStore.MIN_SPEED, PlexStore.MAX_SPEED)
        store.savePlaybackSpeed(LibraryMode.AUDIOBOOK, speed)
        val profile = profile(playback)
        if (book(playback) != null && !profile.overridden) player.speed(speed)
        return profile
    }
}
