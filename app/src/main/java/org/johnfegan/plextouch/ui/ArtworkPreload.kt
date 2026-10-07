package org.johnfegan.plextouch.ui

import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.belongsToScope

/**
 * The server URL of an album's Plex cover, or null for a missing or non-relative thumb. The token is never part of it:
 * requests carry it as the `X-Plex-Token` header, so the URL is also the image loader's memory and disk cache key.
 */
fun plexArtworkUrl(thumb: String?, connection: PlexConnection?): String? {
    if (connection == null) return null
    val path = thumb?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: return null
    return "${connection.serverUrl.trimEnd('/')}$path"
}

/** Listening history for the albums of the current catalogue, newest first, as Home's continue and recently-played shelves read it. */
fun homeHistory(history: List<ListeningProgress>, albums: List<PlexAlbum>, server: String?, mode: LibraryMode, scope: String?): List<ListeningProgress> {
    val shown = albums.mapTo(HashSet()) { it.id }
    return history.filter { it.mode == mode && it.server == server && it.belongsToScope(scope) && it.album.id in shown }
}

/**
 * Home's rails in screen order, each limited to what the screen draws: the featured card, the favourite (music) or
 * listen-again (books) shelf, recently played (music), then recently added. Rows further down the page are left out.
 */
fun homeShelves(albums: List<PlexAlbum>, history: List<ListeningProgress>, saved: List<PlexAlbum>, server: String?, mode: LibraryMode, scope: String?): List<List<PlexAlbum>> {
    val book = mode == LibraryMode.AUDIOBOOK
    val recent = presentAlbums(albums, "", AlbumSort.RECENT)
    val played = homeHistory(history, albums, server, mode, scope)
    val continuing = if (book) played.firstOrNull { !it.finished } else played.firstOrNull()
    val featured = listOfNotNull(continuing?.album ?: recent.firstOrNull())
    return listOf(
        featured,
        saved.take(HOME_SAVED_SHELF),
        if (!book && played.size > 1) played.map { it.album }.take(HOME_PLAYED_SHELF) else emptyList(),
        recent.take(HOME_RECENT_SHELF),
    )
}

/**
 * The Plex cover URLs worth warming before the user scrolls: the first [perShelf] albums of each shelf, in shelf order,
 * each URL once, at most [limit] in all. A downloaded album's local cover is what the screen draws, so it is never
 * fetched from Plex; with no connection (offline-only mode) nothing is selected.
 */
fun artworkPreloadUrls(shelves: List<List<PlexAlbum>>, connection: PlexConnection?, perShelf: Int = PRELOAD_PER_SHELF, limit: Int = PRELOAD_LIMIT): List<String> {
    if (connection == null || perShelf <= 0 || limit <= 0) return emptyList()
    val urls = LinkedHashSet<String>()
    for (shelf in shelves) {
        for (album in shelf.take(perShelf)) {
            if (album.localThumb != null) continue
            plexArtworkUrl(album.thumb, connection)?.let(urls::add)
            if (urls.size >= limit) return urls.toList()
        }
    }
    return urls.toList()
}

const val HOME_SAVED_SHELF = 12
const val HOME_PLAYED_SHELF = 8
const val HOME_RECENT_SHELF = 12
/** A phone shows two or three 144 dp tiles per rail; the fourth is the next to scroll in. */
const val PRELOAD_PER_SHELF = 4
const val PRELOAD_LIMIT = 16
