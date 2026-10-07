package org.johnfegan.plextouch.app

import org.johnfegan.plextouch.data.DownloadsGateway
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.InvalidStateException
import org.johnfegan.plextouch.ui.UserFacing
import org.johnfegan.plextouch.ui.checkText
import org.johnfegan.plextouch.ui.uiText

/** Writes the edited display fields to Plex and reads the album back; anything Plex did not confirm is an error, not a guess. */
class EditAlbumMetadata(private val gateways: PlexGateways, private val downloads: DownloadsGateway) {
    /** Returns the album as Plex now reports it; otherwise throws, with a [UserFacing] text where the reason is known. */
    suspend fun save(connection: PlexConnection, library: String, album: PlexAlbum, title: String, artist: String, year: Int?): PlexAlbum {
        val api = gateways.open(connection, force = true)
        api.editAlbumMetadata(library, album.id, title, artist, year)
        val saved = api.albums(library).firstOrNull { it.id == album.id }
            ?: throw InvalidStateException(uiText(R.string.metadata_album_missing))
        checkText(saved.title == title.trim() && saved.artist == artist.trim() && (year == null || saved.year == year)) { uiText(R.string.metadata_not_confirmed) }
        downloads.updateAlbumMetadata(connection.serverUrl, library, listOf(saved))
        return saved
    }
}
