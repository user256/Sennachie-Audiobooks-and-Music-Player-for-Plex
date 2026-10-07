package org.johnfegan.plextouch.app

import kotlinx.coroutines.CancellationException
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexCollection
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.data.PlexHttpException

/** How a collection list or a collection's books reached the screen (ticket 131). */
enum class ShelfLoad {
    /** A fresh cached copy was shown; Plex was not asked. */
    FRESH_CACHE,
    /** Plex's current list was shown (after any cached copy). */
    SERVER,
    /** Plex could not be reached; the last-known (stale) copy was shown and left in place. */
    STALE_KEPT,
    /** Offline-only mode: the last-known copy was shown without asking Plex. */
    OFFLINE_CACHE,
    /** Offline-only mode with nothing saved: an empty list was shown. */
    NOTHING_SAVED,
    /** Plex says the collection no longer exists: an empty list was shown. */
    GONE,
}

/**
 * Cached-then-fresh reads of a library's Plex collections and of one collection's books, through the same namespaced,
 * bounded and account-fingerprinted [org.johnfegan.plextouch.data.PlexCache] as the album list. A fresh cached copy is
 * shown and Plex is not asked; a stale one is shown first and then replaced. Offline-only mode shows the last-known copy
 * and never asks. A failed refresh keeps a cached copy on screen; with nothing cached the failure is thrown. `force`
 * (pull-to-refresh) always asks Plex, falling back to the cached copy if Plex cannot be reached. Nothing here touches the
 * player or a Plex playlist.
 */
class LoadCollections(private val gateways: PlexGateways) {
    suspend fun collections(connection: PlexConnection, library: String, force: Boolean = false, offlineOnly: Boolean, present: suspend (List<PlexCollection>) -> Unit): ShelfLoad {
        val api = gateways.open(connection, force)
        val cached = api.cachedCollections(library)
        return load(cached?.collections, cached?.fresh == true, force, offlineOnly, gone = false, present) { api.collections(library) }
    }

    /** A deleted collection (Plex answers 404) shows an empty list and reports [ShelfLoad.GONE] rather than an error. */
    suspend fun books(connection: PlexConnection, library: String, collectionId: String, force: Boolean = false, offlineOnly: Boolean, present: suspend (List<PlexAlbum>) -> Unit): ShelfLoad {
        val api = gateways.open(connection, force)
        val cached = api.cachedCollectionBooks(library, collectionId)
        return load(cached?.albums, cached?.fresh == true, force, offlineOnly, gone = true, present) { api.collectionBooks(library, collectionId) }
    }

    private suspend fun <T> load(
        cached: List<T>?, fresh: Boolean, force: Boolean, offlineOnly: Boolean, gone: Boolean,
        present: suspend (List<T>) -> Unit, fetch: suspend () -> List<T>,
    ): ShelfLoad {
        if (cached != null && (!force || offlineOnly)) {
            present(cached)
            if (offlineOnly) return ShelfLoad.OFFLINE_CACHE
            if (fresh) return ShelfLoad.FRESH_CACHE
        }
        if (offlineOnly) { present(emptyList()); return ShelfLoad.NOTHING_SAVED }
        val items = try { fetch() } catch (cancelled: CancellationException) { throw cancelled
        } catch (missing: PlexHttpException) {
            if (gone && missing.status == 404) { present(emptyList()); return ShelfLoad.GONE }
            if (cached == null) throw missing
            if (force) present(cached)
            return ShelfLoad.STALE_KEPT
        } catch (error: Exception) {
            if (cached == null) throw error
            if (force) present(cached)
            return ShelfLoad.STALE_KEPT
        }
        present(items)
        return ShelfLoad.SERVER
    }
}
