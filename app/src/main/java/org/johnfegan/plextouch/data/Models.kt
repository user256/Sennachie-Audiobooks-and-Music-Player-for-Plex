package org.johnfegan.plextouch.data

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

data class PlexSection(val id: String, val title: String)

@Immutable
data class PlexAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val trackCount: Int,
    val thumb: String? = null,
    val addedAt: Long = 0,
    val localThumb: String? = null,
    val userRating: Float? = null,
) {
    val isFavourite: Boolean get() = userRating == 10f
}

/** A parsed library cache entry, retaining whether Plex data can be reused without refreshing. */
data class CachedAlbumList(val albums: List<PlexAlbum>, val fresh: Boolean)

/** A parsed track (or playlist item) cache entry; `fresh` means it may be shown without asking Plex again. */
data class CachedTrackList(val tracks: List<PlexTrack>, val fresh: Boolean)

@Immutable
data class PlexTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val streamPath: String,
    val container: String?,
    val localUri: String? = null,
    val playlistItemId: String? = null,
    /** Gson allocates without running constructors, so catalogue JSON written before chapters existed yields null here. */
    @SerializedName("chapters") private val chapterMarkers: List<PlexChapter>? = null,
) {
    /** Embedded chapter markers (sorted, non-overlapping, 1-based `index`); empty when Plex exposed none. */
    val chapters: List<PlexChapter> get() = chapterMarkers.orEmpty()
}

/** One chapter marker inside a single media file; offsets are milliseconds from the start of that file. */
@Immutable
data class PlexChapter(val index: Int, val title: String, val startMs: Long, val endMs: Long)

/**
 * Orders raw Plex markers by start, clamps them to the file, trims overlaps to the next start, drops
 * anything left empty, then renumbers from 1. An untitled marker keeps a blank title; screens show it as "Chapter N"
 * from string resources (see `chapterName` in the UI layer).
 */
fun normaliseChapters(raw: List<PlexChapter>, durationMs: Long): List<PlexChapter> {
    val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
    val sorted = raw.map { it.copy(startMs = it.startMs.coerceIn(0, limit), endMs = it.endMs.coerceAtMost(limit)) }
        .filter { it.startMs < limit && it.endMs > it.startMs }.sortedBy { it.startMs }
    val trimmed = sorted.mapIndexed { position, chapter ->
        val next = sorted.getOrNull(position + 1)
        if (next != null && next.startMs < chapter.endMs) chapter.copy(endMs = next.startMs) else chapter
    }.filter { it.endMs > it.startMs }
    return trimmed.mapIndexed { position, chapter ->
        chapter.copy(index = position + 1, title = chapter.title.trim())
    }
}

/** The last chapter starting at or before `positionMs`; null before the first marker or when the track has none. */
fun chapterAt(track: PlexTrack, positionMs: Long): PlexChapter? {
    val chapters = track.chapters
    var low = 0
    var high = chapters.size - 1
    var found: PlexChapter? = null
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (chapters[middle].startMs <= positionMs) { found = chapters[middle]; low = middle + 1 } else high = middle - 1
    }
    return found
}

@Immutable
data class PlexPlaylist(val id: String, val title: String, val trackCount: Int, val thumb: String? = null, val smart: Boolean = false) {
    /** The artist is left blank; screens label a playlist's "artist" from string resources (see `albumArtist`). */
    fun album() = PlexAlbum("playlist:$id", title, "", null, trackCount, thumb)
}

data class PlexConnection(val serverUrl: String, val token: String) {
    /**
     * A stream URL carrying the token as a query parameter, for the Sonos handoff only.
     *
     * The speaker fetches the audio itself and cannot be given request headers, so the token has to
     * travel in the URL it is handed. Phone playback never uses this: `PlexPlaybackService` sends the
     * token as an `X-Plex-Token` header and its media URIs stay token-free (see `playbackUri`), so player
     * errors and logs cannot leak the credential.
     */
    fun streamUrl(path: String): String {
        val separator = if (path.contains("?")) "&" else "?"
        return "${serverUrl.trimEnd('/')}$path${separator}X-Plex-Token=${token.urlEncode()}"
    }
}

/** Display names live in string resources (see `LibraryMode.label` in the UI layer). */
enum class LibraryMode { AUDIOBOOK, MUSIC }

@Immutable
data class ListeningProgress(
    val album: PlexAlbum,
    val mode: LibraryMode,
    val server: String,
    val trackIndex: Int,
    val positionMs: Long,
    val elapsedMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val finished: Boolean = false,
    val hasListened: Boolean = false,
    val trackId: String? = null,
    val accountScope: String? = null,
    /** When this book was first heard to the end, kept through a replay so history can still say it was completed (ticket 138). */
    val completedAt: Long? = null,
    /** When the listener reset this book's progress on the phone; cleared again by the next playback save (ticket 138). */
    val resetAt: Long? = null,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
