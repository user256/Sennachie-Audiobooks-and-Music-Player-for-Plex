package org.johnfegan.plextouch.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.johnfegan.plextouch.BuildConfig
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.InvalidStateException
import org.johnfegan.plextouch.ui.requireText
import org.johnfegan.plextouch.ui.uiText

/** Tiny, dependency-light Plex Media Server client for the endpoints this app owns. */
class PlexClient(private val connection: PlexConnection, private val timeoutMillis: Int = 15_000, private val cache: PlexCache? = null, private val forceRefresh: Boolean = false, private val clientIdentifier: String = "plex-touch-android") : PlexGateway {
    private val gson = Gson()

    override suspend fun libraries(): List<PlexSection> = sections(get("/library/sections"))
    override suspend fun cachedLibraries(): List<PlexSection>? = cached("/library/sections")?.let(::sections)
    private fun sections(data: Container) = data.directories
        .filter { it.type == "artist" || it.type == "music" }
        .mapNotNull { directory -> directory.key?.let { PlexSection(it, directory.title) } }

    private fun albumsPath(id: String) = "/library/sections/${identifier(id)}/all?type=9&sort=titleSort:asc"
    override suspend fun albums(libraryId: String): List<PlexAlbum> = albums(get(albumsPath(libraryId)))
    suspend fun cachedAlbums(libraryId: String): List<PlexAlbum>? = cached(albumsPath(libraryId))?.let(::albums)
    override suspend fun cachedAlbumList(libraryId: String): CachedAlbumList? = cachedContainer(albumsPath(libraryId))?.let { CachedAlbumList(albums(it.data), it.fresh) }
    private fun albums(data: Container) = data.metadata.mapNotNull { item ->
            item.ratingKey?.let {
                PlexAlbum(it, item.title.orEmpty(), item.parentTitle.orEmpty(), item.year, item.leafCount ?: 0, item.thumb, item.addedAt ?: 0, userRating = item.userRating)
            }
        }

    // Ticket 131: collections are browsed through the same namespaced, bounded cache as albums; they are never edited.
    private fun collectionsPath(id: String) = "/library/sections/${identifier(id)}/collections"
    private fun collectionBooksPath(id: String) = "/library/collections/${identifier(id)}/children"
    override suspend fun collections(libraryId: String): List<PlexCollection> = collections(get(collectionsPath(libraryId)))
    override suspend fun cachedCollections(libraryId: String): CachedCollectionList? =
        cachedContainer(collectionsPath(libraryId))?.let { CachedCollectionList(collections(it.data), it.fresh) }
    /** A deleted collection (404) also drops its cached children, so a later offline view cannot resurrect it. */
    override suspend fun collectionBooks(libraryId: String, collectionId: String): List<PlexAlbum> = try {
        collectionBooks(get(collectionBooksPath(collectionId)), libraryId)
    } catch (missing: PlexHttpException) {
        if (missing.status == 404) cache?.invalidate(connection, collectionBooksPath(collectionId))
        throw missing
    }
    override suspend fun cachedCollectionBooks(libraryId: String, collectionId: String): CachedAlbumList? =
        cachedContainer(collectionBooksPath(collectionId))?.let { CachedAlbumList(collectionBooks(it.data, libraryId), it.fresh) }
    private fun collections(data: Container) = data.metadata.mapNotNull { item ->
        item.ratingKey?.let {
            PlexCollection(it, item.title.orEmpty(), item.childCount?.asString?.toIntOrNull() ?: 0, item.thumb,
                item.smart?.asString in listOf("true", "1"), item.subtype)
        }
    }
    /** A mixed-media collection keeps only albums; an item that names another library section is dropped too. */
    private fun collectionBooks(data: Container, libraryId: String) = albums(data.copy(metadata = data.metadata.filter { item ->
        item.type == "album" && (item.librarySectionID?.asString?.let { it == libraryId } ?: true)
    }))

    override suspend fun setAlbumFavourite(libraryId: String, albumId: String, favourite: Boolean) {
        request("/:/rate?key=${identifier(albumId)}&identifier=com.plexapp.plugins.library&rating=${if (favourite) 10 else -1}", "PUT")
        cache?.invalidate(connection, albumsPath(libraryId))
    }

    /** Locks the edited display fields in Plex; it never modifies source files or artwork. */
    override suspend fun editAlbumMetadata(libraryId: String, albumId: String, title: String, artist: String, year: Int?) {
        requireText(title.trim().isNotEmpty() && artist.trim().isNotEmpty()) { uiText(R.string.metadata_title_artist_required) }
        requireText(year == null || year in 1000..9999) { uiText(R.string.metadata_year_invalid) }
        val fields = mutableListOf(
            "title.value=${query(title.trim())}", "title.locked=1",
            "parentTitle.value=${query(artist.trim())}", "parentTitle.locked=1",
        )
        year?.let { fields += "year.value=$it"; fields += "year.locked=1" }
        request("/library/metadata/${identifier(albumId)}?${fields.joinToString("&")}", "PUT")
        cache?.invalidate(connection, albumsPath(libraryId))
        cache?.invalidate(connection, tracksPath(albumId))
    }

    /** Album children carry embedded chapter markers (single-file books); playlist items keep Plex's default shape. */
    private fun tracksPath(id: String) = if (id.startsWith("playlist:")) "/playlists/${identifier(id.removePrefix("playlist:"))}/items" else "/library/metadata/${identifier(id)}/children?includeChapters=1"
    override suspend fun albumTracks(albumId: String): List<PlexTrack> = tracks(get(tracksPath(albumId)))
    override suspend fun cachedTrackList(albumId: String): CachedTrackList? = cachedContainer(tracksPath(albumId))?.let { CachedTrackList(tracks(it.data), it.fresh) }

    /** Progress must never come from the browsing cache (including when an album is downloaded). */
    override suspend fun audiobookProgress(albumId: String): List<PlexChapterProgress> {
        requireText(!albumId.startsWith("playlist:")) { uiText(R.string.progress_not_playlist) }
        return request(tracksPath(albumId), "GET").metadata.mapNotNull { item ->
            item.ratingKey?.let { PlexChapterProgress(it, item.title.orEmpty(), item.duration ?: 0,
                item.viewOffset ?: 0, item.viewCount ?: 0, item.lastViewedAt ?: 0) }
        }
    }

    override suspend fun sendAudiobookCheckpoint(checkpoint: PhoneCheckpoint) {
        require(checkpoint.durationMs > 0 && checkpoint.positionMs in 0 until checkpoint.durationMs)
        val id = identifier(checkpoint.trackId)
        request("/:/timeline?ratingKey=$id&key=${query("/library/metadata/$id")}&state=stopped&time=${checkpoint.positionMs}&duration=${checkpoint.durationMs}&offline=1&updated=${checkpoint.updatedAt / 1000}", "POST")
    }

    /** Live playback uses Plex's normal timeline contract; offline=1 is reserved for deferred offline events. */
    suspend fun reportAudiobookTimeline(event: TimelineEvent) {
        require(event.trackId.matches(Regex("[0-9]+")) && event.albumId.matches(Regex("[0-9]+")))
        require(event.positionMs in 0 until event.durationMs)
        val values = mutableListOf(
            "ratingKey=${identifier(event.trackId)}",
            "key=${query("/library/metadata/${identifier(event.trackId)}")}",
            "state=${event.state.name.lowercase()}",
            "time=${event.positionMs}",
            "duration=${event.durationMs}",
        )
        if (event.offline) {
            values += "offline=1"
            values += "updated=${event.capturedAt / 1000}"
        }
        request("/:/timeline?${values.joinToString("&")}", "POST", event.playbackSessionId)
    }
    private fun tracks(data: Container) = data.metadata.mapNotNull { item ->
            val media = item.media?.firstOrNull()
            val path = media?.parts?.firstOrNull()?.key ?: return@mapNotNull null
            val duration = item.duration ?: 0
            val chapters = normaliseChapters(item.chapters.orEmpty().mapNotNull { chapter ->
                val start = chapter.startTimeOffset ?: return@mapNotNull null
                PlexChapter(chapter.index ?: 0, chapter.tag.orEmpty(), start, chapter.endTimeOffset ?: duration)
            }, duration)
            PlexTrack(
                id = item.ratingKey ?: return@mapNotNull null,
                title = item.title.orEmpty(),
                artist = item.originalTitle ?: item.grandparentTitle.orEmpty(),
                album = item.parentTitle.orEmpty(),
                durationMs = duration,
                streamPath = path,
                container = media.container,
                playlistItemId = item.playlistItemID,
                chapterMarkers = chapters.takeIf { it.isNotEmpty() },
            )
        }

    override suspend fun playlists(): List<PlexPlaylist> = playlists(get(PLAYLISTS))
    override suspend fun cachedPlaylists(): List<PlexPlaylist>? = cached(PLAYLISTS)?.let(::playlists)
    private fun playlists(data: Container) = data.metadata.filter { it.playlistType == "audio" }.mapNotNull { item ->
        item.ratingKey?.let { PlexPlaylist(it, item.title.orEmpty(), item.leafCount ?: 0, item.composite ?: item.thumb, item.smart?.asString in listOf("true", "1")) }
    }

    override suspend fun createPlaylist(title: String, tracks: List<PlexTrack>): PlexPlaylist {
        requireText(title.trim().isNotEmpty()) { uiText(R.string.playlist_name_required) }
        val uri = playlistUri(tracks)
        val result = request("/playlists?type=audio&smart=0&title=${query(title.trim())}&uri=${query(uri)}", "POST")
        invalidatePlaylists()
        return playlists(result).firstOrNull() ?: throw InvalidStateException(uiText(R.string.playlist_maybe_saved))
    }

    override suspend fun addToPlaylist(id: String, tracks: List<PlexTrack>) {
        val uri = playlistUri(tracks)
        request("/playlists/${identifier(id)}/items?uri=${query(uri)}", "PUT")
        invalidatePlaylists(id)
    }

    override suspend fun renamePlaylist(id: String, title: String) {
        requireText(title.trim().isNotEmpty()) { uiText(R.string.playlist_name_required) }
        request("/playlists/${identifier(id)}?title=${query(title.trim())}", "PUT")
        invalidatePlaylists(id)
    }

    override suspend fun deletePlaylist(id: String) {
        request("/playlists/${identifier(id)}", "DELETE")
        invalidatePlaylists(id)
    }

    override suspend fun removePlaylistItem(id: String, itemId: String) {
        request("/playlists/${identifier(id)}/items/${identifier(itemId)}", "DELETE")
        invalidatePlaylists(id)
    }

    override suspend fun movePlaylistItem(id: String, itemId: String, after: String?) {
        val suffix = after?.let { "?after=${identifier(it)}" }.orEmpty()
        request("/playlists/${identifier(id)}/items/${identifier(itemId)}/move$suffix", "PUT")
        invalidatePlaylists(id)
    }

    private suspend fun playlistUri(tracks: List<PlexTrack>): String {
        requireText(tracks.isNotEmpty()) { uiText(R.string.playlist_no_tracks) }
        val machine = get("/identity").machineIdentifier ?: throw InvalidStateException(uiText(R.string.server_identity_unavailable))
        return "server://$machine/com.plexapp.plugins.library/library/metadata/${tracks.joinToString(",") { identifier(it.id) }}"
    }

    private fun invalidatePlaylists(id: String? = null) {
        cache?.invalidate(connection, PLAYLISTS)
        id?.let { cache?.invalidate(connection, tracksPath("playlist:$it")) }
    }

    private suspend fun cached(path: String): Container? = cachedContainer(path)?.data
    private suspend fun cachedContainer(path: String): CachedContainer? = withContext(Dispatchers.IO) {
        cache?.entry(connection, path)?.let { entry ->
            runCatching { CachedContainer(gson.fromJson(entry.value, Envelope::class.java).mediaContainer, entry.fresh) }.getOrNull()
        }
    }

    private suspend fun get(path: String): Container = withContext(Dispatchers.IO) {
        if (!forceRefresh) cache?.read(connection, path, freshOnly = true)?.let { json ->
            runCatching { gson.fromJson(json, Envelope::class.java).mediaContainer }.getOrNull()?.let { return@withContext it }
        }
        request(path, "GET")
    }

    private suspend fun request(path: String, method: String, sessionIdentifier: String? = null): Container = withContext(Dispatchers.IO) {
        val url = URL("${connection.serverUrl.trimEnd('/')}$path")
        val request = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Plex-Token", connection.token)
            setRequestProperty("X-Plex-Product", BuildConfig.PRODUCT_NAME)
            setRequestProperty("X-Plex-Client-Identifier", clientIdentifier)
            sessionIdentifier?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{8,128}")) }?.let { setRequestProperty("X-Plex-Session-Identifier", it) }
        }
        try {
            if (request.responseCode !in 200..299) {
                throw PlexHttpException(request.responseCode)
            }
            val json = request.inputStream.bufferedReader().use { it.readText() }
            val result = if (json.isBlank()) Container() else gson.fromJson(json, Envelope::class.java).mediaContainer
            if (method == "GET") cache?.write(connection, path, json)
            result
        } finally {
            request.disconnect()
        }
    }

    private data class Envelope(@SerializedName("MediaContainer") val mediaContainer: Container)
    private data class CachedContainer(val data: Container, val fresh: Boolean)
    private data class Container(
        val machineIdentifier: String? = null,
        @SerializedName("Directory") val directories: List<Directory> = emptyList(),
        @SerializedName("Metadata") val metadata: List<Metadata> = emptyList(),
    )
    private data class Directory(val key: String?, val title: String, val type: String?)
    private data class Metadata(
        @SerializedName("ratingKey") val ratingKey: String?,
        val title: String?,
        @SerializedName("parentTitle") val parentTitle: String?,
        @SerializedName("grandparentTitle") val grandparentTitle: String?,
        @SerializedName("originalTitle") val originalTitle: String?,
        val year: Int?,
        val thumb: String?,
        val addedAt: Long?,
        val userRating: Float?,
        val viewOffset: Long?,
        val viewCount: Int?,
        val lastViewedAt: Long?,
        val composite: String?,
        val playlistType: String?,
        val smart: com.google.gson.JsonPrimitive?,
        val playlistItemID: String?,
        @SerializedName("leafCount") val leafCount: Int?,
        val duration: Long?,
        val type: String? = null,
        val subtype: String? = null,
        val librarySectionID: com.google.gson.JsonPrimitive? = null,
        val childCount: com.google.gson.JsonPrimitive? = null,
        @SerializedName("Media") val media: List<Media>? = null,
        @SerializedName("Chapter") val chapters: List<Chapter>? = null,
    )
    private data class Chapter(val index: Int?, val tag: String?, val startTimeOffset: Long?, val endTimeOffset: Long?)
    private data class Media(
        val container: String?,
        @SerializedName("Part") val parts: List<Part>? = null,
    )
    private data class Part(val key: String?)

    companion object {
        private const val PLAYLISTS = "/playlists?playlistType=audio"
        private fun query(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        private fun identifier(value: String): String { requireText(value.matches(Regex("[0-9]+"))) { uiText(R.string.invalid_item_identifier) }; return value }
    }
}
