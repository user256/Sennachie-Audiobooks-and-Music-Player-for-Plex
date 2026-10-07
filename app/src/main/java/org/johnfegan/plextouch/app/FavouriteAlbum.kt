package org.johnfegan.plextouch.app

import kotlinx.coroutines.CancellationException
import org.johnfegan.plextouch.data.DownloadsGateway
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText

sealed interface FavouriteResult {
    /** Plex accepted the rating; `albums` is the refreshed library carrying it. */
    data class Saved(val notice: UiText, val albums: List<PlexAlbum>) : FavouriteResult
    data class Failed(val error: UiText) : FavouriteResult
}

/** The five-star heart: shown immediately through `apply`, confirmed with Plex, and put back if Plex refuses. */
class FavouriteAlbum(private val gateways: PlexGateways, private val downloads: DownloadsGateway) {
    suspend fun toggle(connection: PlexConnection, library: String, album: PlexAlbum, favourite: Boolean, apply: (PlexAlbum) -> Unit): FavouriteResult {
        val rated = album.copy(userRating = if (favourite) 10f else null)
        apply(rated)
        val api = gateways.open(connection, force = true)
        try {
            api.setAlbumFavourite(library, album.id, favourite)
        } catch (cancelled: CancellationException) {
            apply(album); throw cancelled
        } catch (_: Exception) {
            apply(album)
            return FavouriteResult.Failed(ERROR)
        }
        // Plex holds the rating now; a failed refresh must not undo the heart, only ask for a refresh.
        return try {
            downloads.updateAlbumMetadata(connection.serverUrl, library, listOf(rated))
            val albums = api.albums(library)
            downloads.updateAlbumMetadata(connection.serverUrl, library, albums)
            FavouriteResult.Saved(uiText(if (favourite) R.string.favourite_saved else R.string.favourite_cleared), albums)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { FavouriteResult.Failed(ERROR) }
    }

    private companion object {
        val ERROR = uiText(R.string.favourite_failed)
    }
}
