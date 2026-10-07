package org.johnfegan.plextouch.ui

import androidx.compose.runtime.Immutable
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexChapter
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.belongsToScope
import org.johnfegan.plextouch.data.chapterAt
import java.util.Locale
import java.text.Normalizer
import androidx.annotation.StringRes
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.PlexServer
import org.johnfegan.plextouch.player.BookTimeline

enum class LibraryTab { HOME, SEARCH, LIBRARY, DOWNLOADS }
enum class AlbumSort(@StringRes val label: Int) { RECENT(R.string.sort_recent), TITLE(R.string.sort_title), CREATOR(R.string.sort_creator) }

/** The mode's display name ("Audiobooks", "Music"). */
@get:StringRes
val LibraryMode.label: Int get() = when (this) { LibraryMode.AUDIOBOOK -> R.string.mode_audiobooks; LibraryMode.MUSIC -> R.string.mode_music }

/** A server Plex listed without a name reads as "Plex server". */
fun serverName(server: PlexServer): UiText = server.name.takeIf { it.isNotBlank() }?.let(UiText::Raw) ?: uiText(R.string.plex_server_default)
enum class MusicCollectionTab { ALBUMS, ARTISTS, PLAYLISTS }

@Immutable
data class MusicArtist(val key: String, val name: String, val albums: List<PlexAlbum>)

private fun artistSortName(name: String) = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").uppercase(Locale.ROOT)
private fun favouriteKey(album: PlexAlbum) = listOf(album.title, album.artist).joinToString("\u0000") { value ->
    Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}
fun artistLetter(name: String): String = artistSortName(name).firstOrNull()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
fun artistLetterPositions(artists: List<MusicArtist>): Map<String, Int> = buildMap {
    artists.forEachIndexed { index, artist -> putIfAbsent(artistLetter(artist.name), index) }
}

/**
 * Album artists come from the current catalogue, so cached/offline browsing stays consistent. Albums without an artist are
 * grouped under `unknownArtist` (the screen passes its localised "Unknown artist"), which also places them in the A–Z order.
 */
fun presentArtists(albums: List<PlexAlbum>, query: String, unknownArtist: String): List<MusicArtist> =
    filterArtists(groupArtists(albums, unknownArtist), query)

/** Every artist of the catalogue in A–Z order (`#` first), each with its albums by title. This is the work [ArtistIndexCache] keeps. */
fun groupArtists(albums: List<PlexAlbum>, unknownArtist: String): List<MusicArtist> {
    val spaces = Regex("\\s+")
    fun name(album: PlexAlbum) = album.artist.trim().replace(spaces, " ").ifBlank { unknownArtist }
    return albums.filterNot { it.id.startsWith("playlist:") }.distinctBy { it.id }
        .groupBy { name(it).lowercase(Locale.ROOT) }
        .map { (key, titles) -> MusicArtist(key, name(titles.first()), presentAlbums(titles, "", AlbumSort.TITLE)) }
        .sortedWith(compareBy<MusicArtist> { artistLetter(it.name) }.thenBy { artistSortName(it.name) })
}

/** The artists whose names contain every word of `query`, in their existing order; a blank query returns the list itself. */
fun filterArtists(artists: List<MusicArtist>, query: String): List<MusicArtist> {
    val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return if (words.isEmpty()) artists else artists.filter { artist -> words.all { artist.name.contains(it, ignoreCase = true) } }
}

/** Plex can expose duplicate metadata IDs for one displayed album; favourites should only show it once. */
fun favouriteAlbums(albums: List<PlexAlbum>): List<PlexAlbum> =
    albums.filter { it.isFavourite && !it.id.startsWith("playlist:") }.distinctBy(::favouriteKey)

fun lastPlayed(history: List<ListeningProgress>, server: String?, mode: LibraryMode, scope: String? = null): ListeningProgress? =
    history.filter { it.server == server && it.mode == mode && it.belongsToScope(scope) }.maxByOrNull { it.updatedAt }

/**
 * Saved progress keyed by album id for one server, mode and account scope, so a screen of tiles makes one pass over
 * history instead of a scan per album. The first entry in history order wins, exactly as the old `firstOrNull` did.
 */
fun progressByAlbum(history: List<ListeningProgress>, server: String?, mode: LibraryMode, scope: String?): Map<String, ListeningProgress> {
    val byAlbum = LinkedHashMap<String, ListeningProgress>()
    for (progress in history) {
        if (progress.server == server && progress.mode == mode && progress.belongsToScope(scope)) byAlbum.putIfAbsent(progress.album.id, progress)
    }
    return byAlbum
}

fun presentAlbums(albums: List<PlexAlbum>, query: String, sort: AlbumSort): List<PlexAlbum> {
    val words = searchWords(query)
    val filtered = if (words.isEmpty()) albums else albums.filter { matchesSearch(it, words) }
    return when (sort) {
        AlbumSort.RECENT -> filtered.sortedByDescending { it.addedAt }
        AlbumSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
        AlbumSort.CREATOR -> filtered.sortedWith(compareBy<PlexAlbum> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
    }
}

/** One row of a book's chapter list; tapping it plays queue entry `trackIndex` from `offsetMs`. */
data class ChapterEntry(val label: String, val title: UiText, val subtitle: UiText, val trackIndex: Int, val offsetMs: Long, val durationMs: Long, val active: Boolean)

/** A book stored as one file with at least two markers uses those markers as its chapters; any other shape lists its tracks. */
fun embeddedChapters(track: PlexTrack?, trackCount: Int): List<PlexChapter> =
    if (trackCount == 1 && track != null && track.chapters.size >= 2) track.chapters else emptyList()
fun embeddedChapters(tracks: List<PlexTrack>): List<PlexChapter> = embeddedChapters(tracks.firstOrNull(), tracks.size)

/** The chapter under a file offset, falling back to the first marker so a lead-in before it still reads as chapter 1. */
fun displayedChapter(track: PlexTrack?, trackCount: Int, positionMs: Long): PlexChapter? =
    embeddedChapters(track, trackCount).takeIf { it.isNotEmpty() }?.let { chapterAt(track!!, positionMs) ?: it.first() }

/** 1-based number for "Chapter N" labels: the marker at the offset in a chaptered single file, otherwise the track number. */
fun chapterNumber(track: PlexTrack?, trackCount: Int, trackIndex: Int, positionMs: Long): Int =
    displayedChapter(track, trackCount, positionMs)?.index ?: (trackIndex + 1)
fun chapterNumber(tracks: List<PlexTrack>, trackIndex: Int, positionMs: Long): Int =
    chapterNumber(tracks.getOrNull(trackIndex), tracks.size, trackIndex, positionMs)

fun chapterCount(track: PlexTrack?, trackCount: Int): Int = embeddedChapters(track, trackCount).size.takeIf { it > 0 } ?: trackCount
fun chapterCount(tracks: List<PlexTrack>): Int = chapterCount(tracks.firstOrNull(), tracks.size)

/** A marker's name, or "Chapter N" for a marker Plex left untitled. */
fun chapterName(chapter: PlexChapter): UiText =
    chapter.title.takeIf { it.isNotBlank() }?.let(UiText::Raw) ?: uiText(R.string.chapter_number_title, chapter.index)

/** The player subtitle: the marker's name inside a chaptered single file, otherwise the track title. */
fun chapterTitle(track: PlexTrack?, trackCount: Int, positionMs: Long): UiText? =
    displayedChapter(track, trackCount, positionMs)?.let(::chapterName) ?: track?.title?.let(UiText::Raw)

/** Builds the chapter list for album and player screens; the active row follows the playing offset, not just the track. */
fun bookChapters(tracks: List<PlexTrack>, currentTrackIndex: Int, currentPositionMs: Long): List<ChapterEntry> {
    val embedded = embeddedChapters(tracks)
    if (embedded.isNotEmpty()) {
        val current = if (currentTrackIndex == 0) displayedChapter(tracks.first(), tracks.size, currentPositionMs) else null
        return embedded.map { chapter ->
            ChapterEntry("${chapter.index}", chapterName(chapter), uiText(R.string.chapter_starts_at, playbackTime(chapter.startMs)), 0, chapter.startMs, chapter.endMs - chapter.startMs, chapter == current)
        }
    }
    return tracks.mapIndexed { index, track -> ChapterEntry("${index + 1}", UiText.Raw(track.title), UiText.Raw(track.artist), index, 0, track.durationMs, index == currentTrackIndex) }
}

/** Where the listener is in the whole book, for the player's book bar under the per-file slider. */
@Immutable
data class BookProgress(val elapsedMs: Long, val totalMs: Long) {
    val remainingMs: Long get() = (totalMs - elapsedMs).coerceAtLeast(0)
    val fraction: Float get() = if (totalMs <= 0) 0f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
}

/**
 * Book-level progress for a book played as several files; null for a single file (its slider already is the book) and
 * whenever `queue` is not the queue now playing, so a replaced queue never lends its lengths to the next book.
 * Files of unknown length count as zero (see [BookTimeline]).
 */
fun bookProgress(queue: List<PlexTrack>, trackIndex: Int, trackId: String?, positionMs: Long): BookProgress? {
    if (queue.size < 2 || queue.getOrNull(trackIndex)?.id != trackId) return null
    val timeline = BookTimeline.of(queue)
    if (timeline.totalMs <= 0) return null
    return BookProgress(timeline.bookOffset(trackIndex, positionMs), timeline.totalMs)
}

fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

/** The speeds the player menu and Settings offer. */
val SPEED_CHOICES: List<Float> = listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Speed controls read as 1×, 1.25× or 1.5×, never 1.0×. */
fun speedLabel(speed: Float): String = "%.2f".format(Locale.ROOT, speed).trimEnd('0').trimEnd('.') + "×"

/** "1h 5m" or "42m". */
fun listeningTime(milliseconds: Long): UiText {
    val minutes = (milliseconds.coerceAtLeast(0) / 60_000)
    return if (minutes >= 60) uiText(R.string.duration_hours_minutes, minutes / 60, minutes % 60) else uiText(R.string.duration_minutes, minutes)
}
