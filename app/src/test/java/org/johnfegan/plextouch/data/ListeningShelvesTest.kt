package org.johnfegan.plextouch.data

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ListeningShelvesTest {
    private val album = PlexAlbum("1", "Blue Train", "John Coltrane", 1957, 5)
    private val book = ListeningProgress(album, LibraryMode.AUDIOBOOK, "home", 0, 10, 10, 100, 1)

    @Test fun favouritesRequireFivePersonalPlexStarsAndSurviveMetadataPersistence() {
        assertTrue(album.copy(userRating = 10f).isFavourite)
        listOf(null, 0f, 5f, 9f, 9.9f).forEach { assertFalse(album.copy(userRating = it).isFavourite) }
        val favourite = album.copy(userRating = 10f)
        assertEquals(favourite, Gson().fromJson(Gson().toJson(favourite), PlexAlbum::class.java))
        val legacy = Gson().toJsonTree(album).asJsonObject.apply { remove("userRating") }
        assertFalse(Gson().fromJson(legacy, PlexAlbum::class.java).isFavourite)
    }

    @Test fun legacyProgressIsRecognisedAndNewFieldsRoundTrip() {
        val legacy = Gson().toJsonTree(book).asJsonObject.apply { remove("hasListened") }
        val restored = Gson().fromJson(legacy, ListeningProgress::class.java)
        assertEquals(listOf(book), listenAgain(listOf(restored), "home"))
        val updated = mergeListeningProgress(listOf(restored), book.copy(positionMs = 0, elapsedMs = 0, updatedAt = 2))
        assertTrue(updated.single().hasListened)
        assertEquals(updated, Gson().fromJson(Gson().toJson(updated), Array<ListeningProgress>::class.java).toList())
        assertEquals(1, listenAgain(updated, "home").size)
    }

    @Test fun musicCannotEvictBooksAndBooksAreNotLimitedToForty() {
        var history = emptyList<ListeningProgress>()
        repeat(70) { history = mergeListeningProgress(history, book.copy(album = album.copy(id = "book$it"), updatedAt = it.toLong())) }
        repeat(100) { history = mergeListeningProgress(history, book.copy(album = album.copy(id = "music$it"), mode = LibraryMode.MUSIC, updatedAt = (100 + it).toLong())) }
        assertEquals(70, listenAgain(history, "home").size)
        assertEquals(40, history.count { it.mode == LibraryMode.MUSIC })
        assertEquals("book69", listenAgain(history, "home").first().album.id)
    }

    @Test fun listenAgainIncludesFinishedAndPartialButNotUnplayedOtherServersOrMusic() {
        val completed = book.copy(album = album.copy(id = "2"), finished = true, updatedAt = 3)
        val history = listOf(book, completed, book.copy(updatedAt = 0), book.copy(album = album.copy(id = "3"), positionMs = 0, elapsedMs = 0),
            book.copy(server = "away"), book.copy(mode = LibraryMode.MUSIC))
        assertEquals(listOf(completed, book), listenAgain(history, "home"))
        assertTrue(listenAgain(history, null).isEmpty())
    }

    @Test fun replayUpdatesSameBookAndKeepsItOnTheShelfEvenAtTheBeginning() {
        val history = mergeListeningProgress(listOf(book.copy(finished = true)), book.copy(positionMs = 0, elapsedMs = 0, updatedAt = 5))
        assertEquals(1, history.size)
        assertFalse(history.single().finished)
        assertTrue(history.single().hasListened)
        assertEquals(1, listenAgain(history, "home").size)
    }

    @Test fun offlineShelfOnlyShowsAvailableAlbumsAndUsesLocalArtwork() {
        val local = album.copy(localThumb = "/local/cover.jpg")
        val missing = album.copy(id = "2")
        assertEquals(listOf(local), shelfAlbums(listOf(album, missing), listOf(local), true))
        assertEquals(listOf(local, missing), shelfAlbums(listOf(album, missing), listOf(local), false))
    }
}
