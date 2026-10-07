package org.johnfegan.plextouch.ui

import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPreloadTest {
    private val server = "https://plex.example:32400/"
    private val connection = PlexConnection(server, "secret-token")
    private fun album(id: String, addedAt: Long = 0, thumb: String? = "/library/metadata/$id/thumb/1", localThumb: String? = null) =
        PlexAlbum(id, "Album $id", "Artist $id", null, 1, thumb = thumb, addedAt = addedAt, localThumb = localThumb)
    private fun url(id: String) = "https://plex.example:32400/library/metadata/$id/thumb/1"
    private fun played(album: PlexAlbum, at: Long, mode: LibraryMode = LibraryMode.MUSIC, finished: Boolean = false, scope: String? = "me") =
        ListeningProgress(album, mode, server, 0, 0, 0, 100, at, finished = finished, accountScope = scope)

    @Test fun artworkUrlNeverCarriesTheTokenAndRejectsForeignPaths() {
        assertEquals(url("7"), plexArtworkUrl("/library/metadata/7/thumb/1", connection))
        assertFalse(plexArtworkUrl("/library/metadata/7/thumb/1", connection)!!.contains("secret-token"))
        assertNull(plexArtworkUrl("//evil.example/x.jpg", connection))
        assertNull(plexArtworkUrl("https://evil.example/x.jpg", connection))
        assertNull(plexArtworkUrl(null, connection))
        assertNull(plexArtworkUrl("/library/metadata/7/thumb/1", null))
    }

    @Test fun takesOnlyTheFirstCoversOfEachShelfInShelfOrder() {
        val shelves = listOf((1..6).map { album("a$it") }, (1..6).map { album("b$it") })
        assertEquals(listOf("a1", "a2", "b1", "b2").map(::url), artworkPreloadUrls(shelves, connection, perShelf = 2, limit = 10))
    }

    @Test fun stopsAtTheOverallLimit() {
        val shelves = listOf((1..6).map { album("a$it") }, (1..6).map { album("b$it") })
        assertEquals(listOf("a1", "a2", "a3", "a4", "b1").map(::url), artworkPreloadUrls(shelves, connection, perShelf = 4, limit = 5))
        assertTrue(artworkPreloadUrls(shelves, connection, perShelf = 0).isEmpty())
        assertTrue(artworkPreloadUrls(shelves, connection, limit = 0).isEmpty())
    }

    @Test fun anAlbumOnSeveralShelvesIsRequestedOnce() {
        val shared = album("1")
        val sameCover = album("2", thumb = shared.thumb)
        val urls = artworkPreloadUrls(listOf(listOf(shared), listOf(shared, sameCover, album("3"))), connection)
        assertEquals(listOf(url("1"), url("3")), urls)
    }

    @Test fun downloadedCoversAndMissingThumbsAreNeverFetchedFromPlex() {
        val downloaded = album("1", localThumb = "file:///data/covers/1.jpg")
        val urls = artworkPreloadUrls(listOf(listOf(downloaded, album("2", thumb = null), album("3", thumb = "//cdn/x"), album("4"))), connection)
        assertEquals(listOf(url("4")), urls)
    }

    @Test fun offlineOnlyHasNoArtworkConnectionSoNothingIsSelected() {
        assertTrue(artworkPreloadUrls(listOf(listOf(album("1"), album("2"))), connection = null).isEmpty())
    }

    @Test fun homeShelvesFollowTheScreenFeaturedSavedPlayedThenRecent() {
        val albums = (1..20).map { album("$it", addedAt = it.toLong()) }
        val history = listOf(played(albums[4], at = 9), played(albums[2], at = 5), played(album("gone"), at = 8),
            played(albums[0], at = 7, mode = LibraryMode.AUDIOBOOK), played(albums[1], at = 6, scope = "someone-else"))
        val saved = (1..15).map { albums[20 - it] }
        val shelves = homeShelves(albums, history.sortedByDescending { it.updatedAt }, saved, server, LibraryMode.MUSIC, "me")
        assertEquals(listOf("5"), shelves[0].map { it.id })
        assertEquals(HOME_SAVED_SHELF, shelves[1].size)
        assertEquals(listOf("5", "3"), shelves[2].map { it.id })
        assertEquals((20 downTo 9).map { "$it" }, shelves[3].map { it.id })
    }

    @Test fun booksFeatureTheUnfinishedBookAndHaveNoRecentlyPlayedShelf() {
        val albums = (1..3).map { album("$it", addedAt = it.toLong()) }
        val history = listOf(played(albums[0], 9, LibraryMode.AUDIOBOOK, finished = true), played(albums[1], 8, LibraryMode.AUDIOBOOK))
        val shelves = homeShelves(albums, history, emptyList(), server, LibraryMode.AUDIOBOOK, "me")
        assertEquals(listOf("2"), shelves[0].map { it.id })
        assertTrue(shelves[2].isEmpty())
        val first = artworkPreloadUrls(shelves, connection)
        assertEquals(listOf(url("2"), url("3"), url("1")), first)
    }

    @Test fun anEmptyCatalogueHasNothingToWarm() {
        val shelves = homeShelves(emptyList(), emptyList(), emptyList(), server, LibraryMode.MUSIC, null)
        assertTrue(artworkPreloadUrls(shelves, connection).isEmpty())
    }

    @Test fun defaultBudgetCoversEveryShelfOfAFullHome() {
        val albums = (1..40).map { album("$it", addedAt = it.toLong()) }
        val history = (1..10).map { played(albums[it], at = 100L - it) }
        val shelves = homeShelves(albums, history, albums.take(15), server, LibraryMode.MUSIC, "me")
        val urls = artworkPreloadUrls(shelves, connection)
        assertTrue(urls.size <= PRELOAD_LIMIT)
        assertEquals(urls.distinct(), urls)
        // The first cover of every rail is in the pass.
        shelves.filter { it.isNotEmpty() }.forEach { shelf -> assertTrue(url(shelf.first().id) in urls) }
    }
}
