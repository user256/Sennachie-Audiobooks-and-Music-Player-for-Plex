package org.johnfegan.plextouch.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.data.belongsToScope
import org.johnfegan.plextouch.ui.favouriteAlbums

/**
 * The titles whose track lists are worth fetching before they are tapped, most likely first:
 *
 * 1. up to [PrewarmTracks.RECENT_LIMIT] most recently played titles for this server, account and mode (in music mode this
 *    includes recently played playlists), limited to titles in the current catalogue;
 * 2. up to [PrewarmTracks.FAVOURITE_LIMIT] five-star favourites from the current catalogue;
 *
 * never more than [PrewarmTracks.LIMIT] in total. Downloaded titles are left out: their download is authoritative and opens
 * without a Plex request, so there is nothing to warm. An empty catalogue (or offline-only mode) selects nothing.
 */
fun prewarmCandidates(
    history: List<ListeningProgress>, albums: List<PlexAlbum>, downloads: List<DownloadStatus>,
    server: String, scope: String?, mode: LibraryMode, offlineOnly: Boolean,
): List<String> {
    if (offlineOnly || albums.isEmpty()) return emptyList()
    val catalogue = albums.mapTo(HashSet()) { it.id }
    val local = downloads.filter { it.record.server == server }.mapTo(HashSet()) { it.record.album.id }
    val recent = history.asSequence()
        .filter { it.server == server && it.mode == mode && it.belongsToScope(scope) }
        .sortedByDescending { it.updatedAt }
        .map { it.album.id }
        .filter { it in catalogue || (mode == LibraryMode.MUSIC && it.startsWith("playlist:")) }
        .filterNot { it in local }
        .distinct().take(PrewarmTracks.RECENT_LIMIT).toList()
    val favourites = favouriteAlbums(albums).asSequence().map { it.id }
        .filterNot { it in local || it in recent }
        .take(PrewarmTracks.FAVOURITE_LIMIT).toList()
    return (recent + favourites).take(PrewarmTracks.LIMIT)
}

/**
 * Fetches a bounded set of track lists into the browsing cache so opening one of those titles needs no Plex request while the
 * entry stays fresh. It runs one request at a time on [dispatcher], skips titles whose cached copy is still fresh, stops at
 * the first failure (an unreachable server is not retried per title), and stops as soon as its job is cancelled or
 * `stillCurrent` reports that the account, server, library or mode has changed. It never runs in offline-only mode, never
 * reads listening progress and never downloads audio.
 */
class PrewarmTracks(private val gateways: PlexGateways, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    /** Returns the ids it actually fetched from Plex. */
    suspend fun prewarm(connection: PlexConnection, ids: List<String>, offlineOnly: Boolean, stillCurrent: () -> Boolean = { true }): List<String> {
        if (offlineOnly || ids.isEmpty()) return emptyList()
        return withContext(dispatcher) {
            val api = gateways.open(connection)
            val fetched = mutableListOf<String>()
            for (id in ids.distinct().take(LIMIT)) {
                currentCoroutineContext().ensureActive()
                if (!stillCurrent()) break
                try {
                    if (api.cachedTrackList(id)?.fresh == true) continue
                    api.albumTracks(id)
                    fetched += id
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { break }
            }
            fetched
        }
    }

    companion object {
        const val RECENT_LIMIT = 4
        const val FAVOURITE_LIMIT = 4
        /** At most this many track lists per catalogue load: well inside the cache's 100-entry bound. */
        const val LIMIT = 8
    }
}
