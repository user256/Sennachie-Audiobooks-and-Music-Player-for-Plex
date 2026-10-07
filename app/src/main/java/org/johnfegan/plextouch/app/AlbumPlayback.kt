package org.johnfegan.plextouch.app

import kotlinx.coroutines.CancellationException
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.data.ProgressStore
import org.johnfegan.plextouch.data.belongsToScope
import org.johnfegan.plextouch.data.effectiveSpeed
import org.johnfegan.plextouch.player.BookPoint
import org.johnfegan.plextouch.player.PlaybackController
import org.johnfegan.plextouch.player.PlaybackState
import org.johnfegan.plextouch.player.resumePoint
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText

/** Where a play request should start. */
sealed interface PlayPlan {
    data class Refused(val message: UiText) : PlayPlan
    /** The album is already loaded in the player: resume it rather than restart it. */
    data object ResumeCurrent : PlayPlan
    data class Start(val index: Int, val positionMs: Long) : PlayPlan
}

sealed interface PlayOutcome {
    data object Started : PlayOutcome
    /** Nothing to play (no tracks); the screens stay as they are. */
    data object Ignored : PlayOutcome
    data class Refused(val message: UiText) : PlayOutcome
}

/** Saved progress for `album` worth resuming: this server and account, and not yet finished. */
fun resumableProgress(history: List<ListeningProgress>, album: PlexAlbum, server: String, scope: String?): ListeningProgress? =
    history.firstOrNull { it.album.id == album.id && it.server == server && it.belongsToScope(scope) && !it.finished }

/**
 * The pure part of pressing play. An unfinished audiobook resumes at its saved chapter and offset unless a chapter was chosen
 * explicitly (mapped by [resumePoint]: the saved track id wins, and a checkpoint on a file's end resumes at the next file);
 * a finished one, music, shuffle and infinite play start from the first (or a random) track. `offsetMs` only applies
 * with an explicit `index`. Offline, an album with any streamed track is refused rather than left to fail mid-queue.
 */
fun playPlan(
    album: PlexAlbum, tracks: List<PlexTrack>, mode: LibraryMode, playback: PlaybackState, history: List<ListeningProgress>,
    server: String, scope: String?, offlineOnly: Boolean, infinite: Boolean = false, index: Int? = null, shuffled: Boolean = false,
    offsetMs: Long = 0, random: (IntRange) -> Int = { it.random() },
): PlayPlan {
    if (offlineOnly && tracks.any { it.localUri == null }) {
        return PlayPlan.Refused(uiText(R.string.play_incomplete_download))
    }
    if (playback.album?.id == album.id && index == null && !shuffled && !infinite && !album.id.startsWith("playlist:")) return PlayPlan.ResumeCurrent
    val progress = resumableProgress(history, album, server, scope)?.takeIf { mode == LibraryMode.AUDIOBOOK && index == null }
    if (index != null) return PlayPlan.Start(index, offsetMs.coerceAtLeast(0))
    if (progress != null) return resumePoint(tracks, progress.trackIndex, progress.positionMs, progress.trackId).let { PlayPlan.Start(it.trackIndex, it.offsetMs) }
    return PlayPlan.Start(if (shuffled) random(tracks.indices) else 0, 0)
}

/** Starting, resuming and loading albums: the [playPlan] decisions applied to the real player with the book's effective speed (its own override, else the mode's default) and account scope. */
class AlbumPlayback(private val gateways: PlexGateways, private val player: PlaybackController, private val store: ProgressStore) {
    fun play(
        connection: PlexConnection, album: PlexAlbum, tracks: List<PlexTrack>, mode: LibraryMode, playback: PlaybackState,
        history: List<ListeningProgress>, scope: String?, offlineOnly: Boolean,
        infinite: Boolean = false, index: Int? = null, shuffled: Boolean = false, offsetMs: Long = 0,
    ): PlayOutcome {
        when (val plan = playPlan(album, tracks, mode, playback, history, connection.serverUrl, scope, offlineOnly, infinite, index, shuffled, offsetMs)) {
            is PlayPlan.Refused -> return PlayOutcome.Refused(plan.message)
            PlayPlan.ResumeCurrent -> if (!playback.playing) player.toggle()
            is PlayPlan.Start -> {
                val accountScope = store.progressScope(connection)
                val speed = store.effectiveSpeed(accountScope, connection.serverUrl, album.id, mode)
                player.play(album, tracks, connection, mode, accountScope, plan.index, plan.positionMs, infinite, shuffled, speed)
            }
        }
        return PlayOutcome.Started
    }

    /** The last-played bar: a finished title starts again from the beginning, and offline needs a complete download. */
    suspend fun resume(connection: PlexConnection, progress: ListeningProgress, local: DownloadStatus?, offlineOnly: Boolean): PlayOutcome {
        if (offlineOnly && local?.ready != true) return PlayOutcome.Refused(uiText(R.string.resume_needs_download))
        val tracks = local?.tracks ?: gateways.open(connection).albumTracks(progress.album.id)
        if (tracks.isEmpty()) return PlayOutcome.Ignored
        val start = if (progress.finished) BookPoint(0, 0) else resumePoint(tracks, progress.trackIndex, progress.positionMs, progress.trackId)
        val accountScope = store.progressScope(connection)
        player.play(
            local?.album ?: progress.album, tracks, connection, progress.mode, accountScope,
            start.trackIndex, start.offsetMs,
            speed = store.effectiveSpeed(accountScope, connection.serverUrl, progress.album.id, progress.mode),
        )
        return PlayOutcome.Started
    }

    /**
     * An album's (or playlist's) track list. A fresh cached copy (for example one [PrewarmTracks] fetched) is shown and Plex is
     * not asked again; a stale copy is shown first and then replaced by the server's. If that refresh fails the stale copy
     * stays on screen and [TracksLoaded.STALE_KEPT] is returned; with nothing cached a failure is thrown as before.
     */
    suspend fun tracks(connection: PlexConnection, albumId: String, present: suspend (List<PlexTrack>) -> Unit): TracksLoaded {
        val api = gateways.open(connection)
        val cached = api.cachedTrackList(albumId)
        if (cached != null) {
            present(cached.tracks)
            if (cached.fresh) return TracksLoaded.FRESH_CACHE
        }
        val tracks = try { api.albumTracks(albumId) } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { if (cached != null) return TracksLoaded.STALE_KEPT else throw error }
        present(tracks)
        return TracksLoaded.SERVER
    }
}

enum class TracksLoaded {
    /** A fresh cached copy was shown; Plex was not asked. */
    FRESH_CACHE,
    /** Plex's current list was shown (after any stale copy). */
    SERVER,
    /** Plex could not be reached; the last saved (stale) list was shown and left in place. */
    STALE_KEPT,
}
