package org.johnfegan.plextouch.ui

import org.johnfegan.plextouch.data.DownloadAlbum
import org.johnfegan.plextouch.data.DownloadFile
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.DownloadTrack
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexCollection
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.data.ReadingListEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 131: series grouping, shelf rows, offline marking and account/server isolation of the shelves. */
class BookShelvesTest {
    private val server = "https://plex.lan:32400"
    private val scope = "account"
    private fun book(id: String, author: String, year: Int?, title: String = "Book $id") = PlexAlbum(id, title, author, year, 3)
    private fun ready(album: PlexAlbum, at: String = server) = DownloadStatus(
        DownloadAlbum(at, "1", LibraryMode.AUDIOBOOK, album, listOf(DownloadTrack(PlexTrack("t${album.id}", "T", "", "", 1, "/p", "mp3", localUri = "file:///x"), DownloadFile("x")))),
        listOf(PlexTrack("t${album.id}", "T", "", "", 1, "/p", "mp3", localUri = "file:///x")), "file:///cover/${album.id}.jpg", 1, 0, 0, 0, 0,
    )
    private fun progress(album: PlexAlbum, finished: Boolean, owner: String? = scope) =
        ListeningProgress(album, LibraryMode.AUDIOBOOK, server, 0, 1_000, 1_000, 10_000, 1, finished = finished, accountScope = owner)

    @Test fun seriesGroupAuthorsWithTwoOrMoreBooksInPublicationOrder() {
        val albums = listOf(
            book("1", "Terry Pratchett", 1989, "Guards! Guards!"), book("2", "terry  pratchett", 1987, "Mort"),
            book("3", "Terry Pratchett", null, "Undated"), book("4", "Solo Author", 2000), book("5", "", 2001), book("6", " ", 2002),
        )
        val groups = seriesGroups(albums, "Unknown author")
        assertEquals("an author with one book is not a series", listOf("Terry Pratchett", "Unknown author"), groups.map { it.title })
        val pratchett = groups.first()
        assertEquals(BookGroupKind.SERIES, pratchett.kind)
        assertEquals(3, pratchett.count)
        assertEquals("year order, undated last", listOf("2", "1", "3"), seriesBooks(albums, pratchett, "Unknown author").map { it.id })
        assertEquals("the cover is the first book", "2", pratchett.cover.id)
    }

    @Test fun seriesOrderIsDeterministicForTies() {
        val tied = listOf(book("9", "A", 2000, "Same"), book("8", "A", 2000, "Same"), book("7", "A", 2000, "Earlier"))
        assertEquals(listOf("7", "8", "9"), seriesOrder(tied).map { it.id })
        assertEquals(seriesOrder(tied), seriesOrder(tied.reversed()))
    }

    @Test fun collectionsBecomeGroupsWithTheirOwnArtwork() {
        val group = collectionGroup(PlexCollection("50", "Discworld", 41, "/library/collections/50/thumb", smart = true))
        assertEquals("50", group.key)
        assertEquals(BookGroupKind.COLLECTION, group.kind)
        assertEquals("/library/collections/50/thumb", group.cover.thumb)
        assertTrue(group.smart)
    }

    @Test fun shelfRowsCarryProgressAndAreMarkedUnavailableOfflineUnlessDownloaded() {
        val one = book("1", "A", 2000)
        val two = book("2", "A", 2001)
        val history = listOf(progress(one, finished = true), progress(two, finished = false, owner = "someone-else"))
        val online = presentShelfBooks(listOf(one, two), history, emptyList(), server, scope, offlineOnly = false)
        assertTrue(online.all { it.available })
        assertTrue(online[0].finished)
        assertEquals("another account's progress is not shown", null, online[1].progress)
        val offline = presentShelfBooks(listOf(one, two), history, listOf(ready(one), ready(two, at = "https://other")), server, scope, offlineOnly = true)
        assertEquals(listOf(true, false), offline.map { it.available })
        assertEquals("a download's cover is used", "file:///cover/1.jpg", offline[0].album.localThumb)
    }

    @Test fun theReadingListPrefersTheCatalogueCopyAndKeepsItsOwnOrder() {
        val entries = listOf(ReadingListEntry(book("2", "A", 2001, "Old title"), 1), ReadingListEntry(book("1", "A", 2000), 2), ReadingListEntry(book("9", "Gone", 1999), 3))
        val catalogue = listOf(book("1", "A", 2000), book("2", "A", 2001, "Renamed"))
        val rows = presentReadingList(entries, catalogue, emptyList(), emptyList(), server, scope, offlineOnly = false)
        assertEquals(listOf("2", "1", "9"), rows.map { it.album.id })
        assertEquals("Renamed", rows[0].album.title)
        assertEquals("a title missing from the catalogue keeps its snapshot", "Book 9", rows[2].album.title)
        val offline = presentReadingList(entries, catalogue, emptyList(), listOf(ready(catalogue[0])), server, scope, offlineOnly = true)
        assertEquals(listOf(false, true, false), offline.map { it.available })
        assertTrue(inReadingList(entries, "9"))
        assertFalse(inReadingList(entries, "5"))
    }

    @Test fun shelvesLoadedForAnotherAccountServerOrLibraryAreNeverShown() {
        val connection = PlexConnection(server, "token")
        val loaded = BookShelvesState(owner = ShelfOwner(server, scope, "1"), collections = listOf(PlexCollection("50", "Discworld", 3)),
            readingList = listOf(ReadingListEntry(book("1", "A", 2000), 1)))
        val state = PlexTouchUiState(connection = connection, accountScope = scope, selectedLibraryId = "1", shelves = loaded)
        assertEquals(loaded, state.currentShelves)
        listOf(
            state.copy(accountScope = "someone-else"),
            state.copy(connection = PlexConnection("https://other:32400", "token")),
            state.copy(selectedLibraryId = "2"),
            state.copy(connection = null),
        ).forEach { changed ->
            assertTrue(changed.currentShelves.collections.isEmpty())
            assertTrue(changed.currentShelves.readingList.isEmpty())
            assertEquals(changed.shelfOwner, changed.currentShelves.owner)
        }
    }

    @Test fun backClosesAnOpenCollectionBeforeLeavingTheLibrary() {
        val group = collectionGroup(PlexCollection("50", "Discworld", 3))
        val state = PlexTouchUiState(connection = PlexConnection(server, "token"), accountScope = scope, selectedLibraryId = "1", tab = LibraryTab.LIBRARY,
            personalShelf = PersonalShelf.COLLECTIONS, shelves = BookShelvesState(owner = ShelfOwner(server, scope, "1"), selected = group))
        assertEquals(BackAction.CLOSE_BOOK_GROUP, backAction(state))
        assertEquals(BackAction.HOME_TAB, backAction(state.copy(shelves = state.shelves.copy(selected = null))))
    }
}
