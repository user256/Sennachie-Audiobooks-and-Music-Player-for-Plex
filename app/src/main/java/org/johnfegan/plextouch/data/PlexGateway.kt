package org.johnfegan.plextouch.data

/**
 * The Plex Media Server calls the app's orchestration makes, so it can be driven by a fake on the JVM.
 * One instance serves one connection; [PlexClient] is the real one.
 */
interface PlexGateway {
    suspend fun libraries(): List<PlexSection>
    suspend fun cachedLibraries(): List<PlexSection>?
    suspend fun albums(libraryId: String): List<PlexAlbum>
    suspend fun cachedAlbumList(libraryId: String): CachedAlbumList?
    suspend fun albumTracks(albumId: String): List<PlexTrack>
    suspend fun cachedTrackList(albumId: String): CachedTrackList?
    suspend fun setAlbumFavourite(libraryId: String, albumId: String, favourite: Boolean)
    suspend fun editAlbumMetadata(libraryId: String, albumId: String, title: String, artist: String, year: Int?)
    suspend fun audiobookProgress(albumId: String): List<PlexChapterProgress>
    suspend fun sendAudiobookCheckpoint(checkpoint: PhoneCheckpoint)
    suspend fun playlists(): List<PlexPlaylist>
    suspend fun cachedPlaylists(): List<PlexPlaylist>?
    suspend fun createPlaylist(title: String, tracks: List<PlexTrack>): PlexPlaylist
    suspend fun addToPlaylist(id: String, tracks: List<PlexTrack>)
    suspend fun renamePlaylist(id: String, title: String)
    suspend fun deletePlaylist(id: String)
    suspend fun removePlaylistItem(id: String, itemId: String)
    suspend fun movePlaylistItem(id: String, itemId: String, after: String?)

    // Ticket 131: read-only audiobook collections. Defaults keep gateways that never browse collections (test fakes) unchanged.
    /** The library's Plex collections, in Plex's order. */
    suspend fun collections(libraryId: String): List<PlexCollection> = emptyList()
    suspend fun cachedCollections(libraryId: String): CachedCollectionList? = null
    /** A collection's albums from `libraryId` only, in the collection's own order; other media types and libraries are dropped. */
    suspend fun collectionBooks(libraryId: String, collectionId: String): List<PlexAlbum> = emptyList()
    suspend fun cachedCollectionBooks(libraryId: String, collectionId: String): CachedAlbumList? = null
}

/** Opens a gateway per connection; `force` bypasses the browsing cache for that gateway's reads. */
interface PlexGateways {
    fun open(connection: PlexConnection, force: Boolean = false): PlexGateway

    /** A short-timeout library read, used to pick the first reachable of a server's advertised addresses. */
    suspend fun probeLibraries(connection: PlexConnection): List<PlexSection>
}

/** The production factory: a cached [PlexClient] per request, identified to Plex by the phone's stable client id. */
class PlexClientGateways(private val cache: PlexCache, private val clientIdentifier: () -> String) : PlexGateways {
    override fun open(connection: PlexConnection, force: Boolean): PlexGateway =
        PlexClient(connection, cache = cache, forceRefresh = force, clientIdentifier = clientIdentifier())

    override suspend fun probeLibraries(connection: PlexConnection): List<PlexSection> = PlexClient(connection, timeoutMillis = 5_000).libraries()
}
