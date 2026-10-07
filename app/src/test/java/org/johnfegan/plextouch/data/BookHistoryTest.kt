package org.johnfegan.plextouch.data

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class BookHistoryTest {
    private val book = PlexAlbum("1", "Middlemarch", "George Eliot", 1871, 3)
    private val other = PlexAlbum("2", "Persuasion", "Jane Austen", 1817, 2)
    private fun save(album: PlexAlbum = book, elapsed: Long = 30_000, finished: Boolean = false, at: Long, scope: String? = "A", server: String = "home") =
        ListeningProgress(album, LibraryMode.AUDIOBOOK, server, 1, elapsed, elapsed, 100_000, at, finished = finished, trackId = "t", accountScope = scope)

    @Test fun completedThenReplayedBooksKeepTheirFirstCompletionDate() {
        var history = mergeListeningProgress(emptyList(), save(at = 10))
        assertEquals(BookHistoryStatus.IN_PROGRESS, bookHistory(history, "home", "A").single().status)
        history = mergeListeningProgress(history, save(elapsed = 100_000, finished = true, at = 100))
        history = mergeListeningProgress(history, save(elapsed = 100_000, finished = true, at = 110)) // a second end-of-book save
        bookHistory(history, "home", "A").single().let {
            assertEquals(BookHistoryStatus.COMPLETED, it.status)
            assertEquals(100L, it.completedAt)
            assertEquals(1f, it.fraction)
        }
        history = mergeListeningProgress(history, save(elapsed = 5_000, at = 200))
        bookHistory(history, "home", "A").single().let {
            assertEquals(BookHistoryStatus.IN_PROGRESS, it.status)
            assertEquals(100L, it.completedAt)
            assertEquals(0.05f, it.fraction, 0.001f)
        }
        history = mergeListeningProgress(history, save(elapsed = 100_000, finished = true, at = 300))
        assertEquals(300L, bookHistory(history, "home", "A").single().completedAt)
    }

    @Test fun historySavedBeforeCompletionDatesFallsBackToTheLastSave() {
        val legacy = Gson().toJsonTree(save(finished = true, at = 42)).asJsonObject.apply { remove("completedAt"); remove("resetAt") }
        val restored = Gson().fromJson(legacy, ListeningProgress::class.java)
        assertNull(restored.completedAt)
        assertEquals(42L, bookHistory(listOf(restored), "home", "A").single().completedAt)
    }

    @Test fun resetStartsOneBookOverAndTheNextPlaybackSaveEndsTheReset() {
        val history = listOf(save(at = 20), save(album = other, at = 10), save(at = 30, scope = "B"))
        val reset = resetBookProgress(history, "home", "A", book.id, at = 50)
        val entry = bookHistory(reset, "home", "A").first()
        assertEquals(BookHistoryStatus.RESET, entry.status)
        assertEquals(50L, entry.resetAt)
        assertEquals(0L, entry.elapsedMs)
        reset.first().let { assertEquals(0, it.trackIndex); assertEquals(0L, it.positionMs); assertNull(it.trackId); assertFalse(it.finished); assertEquals(20L, it.updatedAt) }
        // The other book and the other account's copy of this book are untouched.
        assertEquals(history.drop(1), reset.drop(1))
        // Resetting does not drop the book from Listen Again.
        assertEquals(listOf(book.id, other.id), listenAgain(reset, "home", "A").map { it.album.id })
        val replayed = mergeListeningProgress(reset, save(elapsed = 2_000, at = 60))
        assertEquals(BookHistoryStatus.IN_PROGRESS, bookHistory(replayed, "home", "A").first().status)
        assertNull(replayed.first().resetAt)
    }

    @Test fun aCompletedBookCanBeResetAndKeepsItsCompletionDate() {
        val history = mergeListeningProgress(emptyList(), save(elapsed = 100_000, finished = true, at = 100))
        val entry = bookHistory(resetBookProgress(history, "home", "A", book.id, at = 120), "home", "A").single()
        assertEquals(BookHistoryStatus.RESET, entry.status)
        assertEquals(100L, entry.completedAt)
    }

    @Test fun historyIsScopedToAccountAndServerAndClearingTouchesOnlyThatScope() {
        val music = save(album = PlexAlbum("m", "Kind of Blue", "Miles Davis", 1959, 5), at = 5).copy(mode = LibraryMode.MUSIC)
        val history = listOf(save(at = 20), music, save(album = other, at = 30, scope = "B"), save(album = other, at = 40, server = "cabin"))
        assertEquals(listOf(book.id), bookHistory(history, "home", "A").map { it.album.id })
        assertEquals(listOf(other.id), bookHistory(history, "home", "B").map { it.album.id })
        assertTrue(bookHistory(history, "home", null).isEmpty())
        val cleared = clearListeningProgress(history, "home", "A")
        assertEquals(history.drop(2), cleared)
    }

    @Test fun unplayedEntriesAreNotHistoryAndRowsAreNewestFirst() {
        val opened = save(album = PlexAlbum("3", "Emma", "Jane Austen", 1815, 1), elapsed = 0, at = 99).copy(positionMs = 0)
        val rows = bookHistory(listOf(save(album = other, at = 10), opened, save(at = 20)), "home", "A")
        assertEquals(listOf(book.id, other.id), rows.map { it.album.id })
    }
}
