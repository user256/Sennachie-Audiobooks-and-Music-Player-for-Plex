package org.johnfegan.plextouch.app

import org.johnfegan.plextouch.data.DownloadsGateway
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.data.PlexPlaylist
import org.johnfegan.plextouch.data.PlexSection

/**
 * Cached-then-fresh reads of a Plex library. Each stage is handed to `present` as it arrives, so a screen shows the last
 * known list at once and the server's list when it answers; album metadata is copied into the offline catalogue as it refreshes.
 */
class LoadLibrary(private val gateways: PlexGateways, private val downloads: DownloadsGateway) {
    /** A fresh cache entry is shown and nothing is fetched; a stale one is shown first and then replaced; `force` skips the cache. */
    suspend fun albums(connection: PlexConnection, library: String, force: Boolean = false, present: suspend (List<PlexAlbum>) -> Unit) {
        val api = gateways.open(connection, force)
        val cached = if (force) null else api.cachedAlbumList(library)
        if (cached != null) {
            present(cached.albums)
            if (cached.fresh) {
                downloads.updateAlbumMetadata(connection.serverUrl, library, cached.albums)
                return
            }
        }
        val albums = api.albums(library)
        downloads.updateAlbumMetadata(connection.serverUrl, library, albums)
        present(albums)
    }

    suspend fun sections(connection: PlexConnection, force: Boolean = false, present: suspend (List<PlexSection>) -> Unit) {
        val api = gateways.open(connection, force)
        api.cachedLibraries()?.let { present(it) }
        present(api.libraries())
    }

    /** Offline, only the cached playlists are shown; nothing is fetched. */
    suspend fun playlists(connection: PlexConnection, force: Boolean = false, offlineOnly: Boolean, present: suspend (List<PlexPlaylist>) -> Unit) {
        val api = gateways.open(connection, force)
        api.cachedPlaylists()?.let { present(it) }
        if (!offlineOnly) present(api.playlists())
    }
}
