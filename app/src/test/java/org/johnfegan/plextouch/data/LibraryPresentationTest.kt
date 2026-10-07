package org.johnfegan.plextouch.data

import org.johnfegan.plextouch.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.uiText

class LibraryPresentationTest {
    private val albums = listOf(
        PlexAlbum("1", "Blue Train", "John Coltrane", 1957, 0, addedAt = 10),
        PlexAlbum("2", "The Left Hand of Darkness", "Ursula K. Le Guin", 1969, 0, addedAt = 30),
        PlexAlbum("3", "Kind of Blue", "Miles Davis", 1959, 0, addedAt = 20),
    )

    @Test fun searchMatchesTitleAndCreatorWithoutCaseOrWhitespaceSensitivity() {
        assertEquals(listOf("1"), presentAlbums(albums, "  COLTRANE blue ", AlbumSort.TITLE).map { it.id })
        assertEquals(listOf("2"), presentAlbums(albums, "le guin", AlbumSort.TITLE).map { it.id })
        assertTrue(presentAlbums(albums, "no match", AlbumSort.TITLE).isEmpty())
    }

    @Test fun sortUsesPlexAddedDateAndNotFakeRecommendations() {
        assertEquals(listOf("2", "3", "1"), presentAlbums(albums, "", AlbumSort.RECENT).map { it.id })
        assertEquals(listOf("1", "3", "2"), presentAlbums(albums, "", AlbumSort.TITLE).map { it.id })
        assertEquals(3, albums.size)
    }

    @Test fun favouritesCollapseDuplicatePlexMetadataButKeepDistinctAlbums() {
        val rated = albums[0].copy(userRating = 10f)
        val duplicate = rated.copy(id = "duplicate-id", title = "  BLUE TRAIN ", artist = "Jöhn Coltrane")
        val different = rated.copy(id = "2", title = "Blue Train", artist = "John Coltrane Quartet")
        assertEquals(listOf("1", "2"), favouriteAlbums(listOf(rated, duplicate, different, albums[1])).map { it.id })
    }

    @Test fun progressAndTimesHandleUnknownOrOutOfRangeDurations() {
        val progress = ListeningProgress(albums[0], LibraryMode.AUDIOBOOK, "server", 0, 0, 200, 100, 0)
        assertEquals(1f, progress.fraction)
        assertEquals(0f, progress.copy(durationMs = 0).fraction)
        assertEquals(0f, progress.copy(elapsedMs = -100).fraction)
        assertEquals("0:00", playbackTime(-100))
        assertEquals("1:01:01", playbackTime(3_661_000))
        assertEquals(uiText(R.string.duration_hours_minutes, 1L, 1L), listeningTime(3_661_000))
        assertEquals(uiText(R.string.duration_minutes, 59L), listeningTime(3_599_999))
        assertEquals(uiText(R.string.duration_minutes, 0L), listeningTime(-1))
    }

    @Test fun speedLabelsDropTrailingZeros() {
        assertEquals("1×", speedLabel(1f))
        assertEquals("2×", speedLabel(2f))
        assertEquals("0.75×", speedLabel(.75f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("1.5×", speedLabel(1.5f))
    }

    @Test fun lastPlayedIsIsolatedByModeAndServerAndIncludesFinishedMusic() {
        val music = ListeningProgress(albums[0], LibraryMode.MUSIC, "home", 2, 900, 900, 1000, 20, true)
        val book = music.copy(album = albums[1], mode = LibraryMode.AUDIOBOOK, updatedAt = 30, finished = false)
        val history = listOf(music.copy(updatedAt = 10), book, music, music.copy(server = "other", updatedAt = 100))
        assertEquals(music, lastPlayed(history, "home", LibraryMode.MUSIC))
        assertEquals(book, lastPlayed(history, "home", LibraryMode.AUDIOBOOK))
        assertNull(lastPlayed(history, null, LibraryMode.MUSIC))
        assertNull(lastPlayed(emptyList(), "home", LibraryMode.AUDIOBOOK))
    }

    @Test fun progressByAlbumKeepsTheFirstEntryPerAlbumForOneServerModeAndScope() {
        val book = ListeningProgress(albums[0], LibraryMode.AUDIOBOOK, "home", 0, 10, 10, 100, 5, accountScope = "scope")
        val history = listOf(
            book,
            book.copy(positionMs = 99, updatedAt = 1),
            book.copy(album = albums[1], finished = true),
            book.copy(album = albums[2], server = "away"),
            book.copy(album = albums[2], mode = LibraryMode.MUSIC),
            book.copy(album = albums[2], accountScope = null),
        )
        val progress = progressByAlbum(history, "home", LibraryMode.AUDIOBOOK, "scope")
        assertEquals(listOf("1", "2"), progress.keys.toList())
        assertEquals(10L, progress.getValue("1").positionMs)
        assertTrue(progress.getValue("2").finished)
        history.filter { it.album.id in progress }.forEach { assertSame(history.first { p -> p.album.id == it.album.id }, progress[it.album.id]) }
        assertEquals(mapOf("3" to history[4]), progressByAlbum(history, "home", LibraryMode.MUSIC, "scope"))
        assertEquals(mapOf("3" to history[5]), progressByAlbum(history, "home", LibraryMode.AUDIOBOOK, null))
        assertTrue(progressByAlbum(history, null, LibraryMode.AUDIOBOOK, "scope").isEmpty())
        assertTrue(progressByAlbum(emptyList(), "home", LibraryMode.AUDIOBOOK, "scope").isEmpty())
    }
}
