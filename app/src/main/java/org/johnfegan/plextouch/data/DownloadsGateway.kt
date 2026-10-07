package org.johnfegan.plextouch.data

/**
 * The offline catalogue as the orchestration sees it; [OfflineDownloadManager] is the Android implementation. Records are
 * scoped to the server and the account scope that downloaded them (ticket 136).
 */
interface DownloadsGateway {
    suspend fun snapshots(): List<DownloadStatus>
    suspend fun updateAlbumMetadata(server: String, libraryId: String, albums: List<PlexAlbum>)
    suspend fun download(connection: PlexConnection, libraryId: String, mode: LibraryMode, album: PlexAlbum, tracks: List<PlexTrack>, accountScope: String?)
    suspend fun remove(server: String, albumId: String, accountScope: String?)
    /** Gives pre-136 unscoped records for [server] to [accountScope]; called on sign-in, server choice and restore, never on a Home switch. */
    suspend fun adoptLegacy(server: String, accountScope: String)
}
