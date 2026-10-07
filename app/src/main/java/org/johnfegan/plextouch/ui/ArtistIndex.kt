package org.johnfegan.plextouch.ui

import androidx.compose.runtime.Immutable
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum

/** Which catalogue an artist index belongs to: server, account, library, music mode and offline-only browsing. */
@Immutable
data class ArtistIndexScope(
    val server: String?,
    val accountScope: String?,
    val libraryId: String?,
    val mode: LibraryMode,
    val offlineOnly: Boolean,
) {
    companion object {
        fun of(state: PlexTouchUiState) = ArtistIndexScope(state.connection?.serverUrl, state.accountScope, state.selectedLibraryId, state.mode, state.offlineOnly)
    }
}

/**
 * The Artists screen's presentation model: every artist of one catalogue revision in A–Z order, with the rail's first
 * position for each letter. `revision` identifies the album list it was grouped from; `unknownArtist` is the localised
 * name albums without an artist were grouped under.
 */
@Immutable
data class ArtistIndex(
    val scope: ArtistIndexScope,
    val revision: Int,
    val unknownArtist: String,
    val artists: List<MusicArtist>,
    val positions: Map<String, Int>,
) {
    /** True when this index describes the catalogue `state` is browsing; a newer revision replaces it moments later. */
    fun isFor(state: PlexTouchUiState): Boolean = scope == ArtistIndexScope.of(state)
}

/** A content fingerprint of the album list: ids, titles, artists, ratings and covers all contribute. */
fun catalogueRevision(albums: List<PlexAlbum>): Int = albums.hashCode()

/**
 * Keeps the grouped artists and A–Z positions per [ArtistIndexScope], so returning to Artists or switching back to
 * a music library reuses them rather than regrouping. An entry is reused only for an equal album list (same revision,
 * then compared in full) and the same "Unknown artist" text; any other change to the albums, their artists or ratings
 * regroups. At most [maxScopes] catalogues are kept, least recently used first out. Thread-safe; meant for a background
 * dispatcher.
 */
class ArtistIndexCache(
    private val maxScopes: Int = 4,
    private val group: (List<PlexAlbum>, String) -> List<MusicArtist> = ::groupArtists,
) {
    private class Entry(val source: List<PlexAlbum>, val index: ArtistIndex)
    private val entries = object : LinkedHashMap<ArtistIndexScope, Entry>(maxScopes + 1, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ArtistIndexScope, Entry>?) = size > maxScopes
    }

    @Synchronized fun index(scope: ArtistIndexScope, albums: List<PlexAlbum>, unknownArtist: String): ArtistIndex {
        val revision = catalogueRevision(albums)
        entries[scope]?.let { entry ->
            val index = entry.index
            if (index.revision == revision && index.unknownArtist == unknownArtist && entry.source == albums) return index
        }
        val artists = group(albums, unknownArtist)
        val index = ArtistIndex(scope, revision, unknownArtist, artists, artistLetterPositions(artists))
        entries[scope] = Entry(albums, index)
        return index
    }

    @Synchronized fun clear() = entries.clear()
}
