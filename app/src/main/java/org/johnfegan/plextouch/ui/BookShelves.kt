package org.johnfegan.plextouch.ui

import androidx.compose.runtime.Immutable
import org.johnfegan.plextouch.app.CollectionRun
import org.johnfegan.plextouch.app.ShelfLoad
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexCollection
import org.johnfegan.plextouch.data.ReadingListEntry

/*
 * Ticket 131: audiobook collections, series and the reading list. Everything here is pure presentation over
 * [BookShelvesState], so it is unit-tested on the JVM.
 */

/** Whose shelves these are: loaded data is shown only while the server, account and library still match. */
@Immutable
data class ShelfOwner(val server: String, val scope: String?, val library: String?)

enum class BookGroupKind { COLLECTION, SERIES }

/**
 * A browsable group of books. A collection's `key` is its Plex rating key; a series is keyed by its author. `cover` is a
 * stand-in album for the artwork composable (a collection's own thumbnail, or a series' first book).
 */
@Immutable
data class BookGroup(val key: String, val title: String, val kind: BookGroupKind, val count: Int, val cover: PlexAlbum, val smart: Boolean = false)

/** The collections, series and reading list slice of the UI state; [owner] guards against a stale account or server. */
@Immutable
data class BookShelvesState(
    val owner: ShelfOwner? = null,
    val collections: List<PlexCollection> = emptyList(),
    val collectionsLoad: ShelfLoad? = null,
    val selected: BookGroup? = null,
    val books: List<PlexAlbum> = emptyList(),
    val booksLoad: ShelfLoad? = null,
    val refreshing: Boolean = false,
    val readingList: List<ReadingListEntry> = emptyList(),
    val run: CollectionRun? = null,
)

/** One row of a collection, series or reading list. `available` is false offline for a title that is not downloaded. */
@Immutable
data class ShelfBook(val album: PlexAlbum, val progress: ListeningProgress?, val available: Boolean) {
    val finished: Boolean get() = progress?.finished == true
}

val PlexTouchUiState.shelfOwner: ShelfOwner? get() = connection?.let { ShelfOwner(it.serverUrl, accountScope, selectedLibraryId) }

/** The shelves for the account, server and library now on screen; anything loaded for another is never shown. */
val PlexTouchUiState.currentShelves: BookShelvesState get() = shelves.takeIf { it.owner != null && it.owner == shelfOwner } ?: BookShelvesState(shelfOwner)

fun collectionGroup(collection: PlexCollection): BookGroup = BookGroup(
    collection.id, collection.title, BookGroupKind.COLLECTION, collection.childCount,
    PlexAlbum("collection:${collection.id}", collection.title, "", null, collection.childCount, collection.thumb), collection.smart,
)

/**
 * Series, as the household's Plex audiobook library exposes them: books grouped by album artist (the author), listing
 * only authors with at least two books. Books read in publication order (year, then title, then id), which is
 * deterministic; a book without a year comes after the dated ones. Built from the catalogue already in memory, so it is
 * as offline-safe as the library view.
 */
fun seriesGroups(albums: List<PlexAlbum>, unknownAuthor: String): List<BookGroup> =
    groupArtists(albums, unknownAuthor).filter { it.albums.size >= 2 }.map { author ->
        val books = seriesOrder(author.albums)
        BookGroup("author:${author.key}", author.name, BookGroupKind.SERIES, books.size, books.first())
    }

fun seriesBooks(albums: List<PlexAlbum>, group: BookGroup, unknownAuthor: String): List<PlexAlbum> =
    groupArtists(albums, unknownAuthor).firstOrNull { "author:${it.key}" == group.key }?.albums?.let(::seriesOrder).orEmpty()

fun seriesOrder(books: List<PlexAlbum>): List<PlexAlbum> =
    books.sortedWith(compareBy<PlexAlbum> { it.year == null }.thenBy { it.year ?: 0 }.thenBy { it.title.lowercase() }.thenBy { it.id })

private fun downloadedIds(downloads: List<DownloadStatus>, server: String?): Map<String, DownloadStatus> =
    downloads.filter { it.ready && it.record.server == server }.associateBy { it.record.album.id }

/** A collection's or series' books with their progress and offline availability, in the group's order. */
fun presentShelfBooks(
    books: List<PlexAlbum>, history: List<ListeningProgress>, downloads: List<DownloadStatus>,
    server: String?, scope: String?, offlineOnly: Boolean,
): List<ShelfBook> {
    val progress = progressByAlbum(history, server, LibraryMode.AUDIOBOOK, scope)
    val local = downloadedIds(downloads, server)
    return books.distinctBy { it.id }.map { album ->
        val download = local[album.id]
        ShelfBook(download?.let { album.copy(localThumb = it.coverUri) } ?: album, progress[album.id], !offlineOnly || download != null)
    }
}

/**
 * The reading list as shown: each entry takes the catalogue's current title and artwork when the book is in it (the saved
 * snapshot otherwise), with progress, and is marked unavailable offline unless it is fully downloaded.
 */
fun presentReadingList(
    entries: List<ReadingListEntry>, catalogue: List<PlexAlbum>, history: List<ListeningProgress>, downloads: List<DownloadStatus>,
    server: String?, scope: String?, offlineOnly: Boolean,
): List<ShelfBook> {
    val known = catalogue.associateBy { it.id }
    return presentShelfBooks(entries.map { known[it.album.id] ?: it.album }, history, downloads, server, scope, offlineOnly)
}

fun inReadingList(entries: List<ReadingListEntry>, albumId: String): Boolean = entries.any { it.album.id == albumId }
