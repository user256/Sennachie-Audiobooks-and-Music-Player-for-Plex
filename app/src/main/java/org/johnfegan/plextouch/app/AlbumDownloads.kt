package org.johnfegan.plextouch.app

import kotlinx.coroutines.CancellationException
import org.johnfegan.plextouch.data.DownloadsGateway
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText

/** Queueing and removing a phone copy of an album; each returns the error to show, or null when it went through. */
class AlbumDownloads(private val downloads: DownloadsGateway) {
    suspend fun download(connection: PlexConnection, library: String, mode: LibraryMode, album: PlexAlbum, tracks: List<PlexTrack>, accountScope: String?): UiText? = try {
        downloads.download(connection, library, mode, album, tracks, accountScope); null
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { uiText(R.string.download_queue_failed) }

    suspend fun remove(server: String, albumId: String, accountScope: String?): UiText? = try {
        downloads.remove(server, albumId, accountScope); null
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { uiText(R.string.download_remove_failed) }
}
