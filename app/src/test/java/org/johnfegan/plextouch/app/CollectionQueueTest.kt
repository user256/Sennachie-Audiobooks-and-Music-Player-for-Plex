package org.johnfegan.plextouch.app

import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 131: which books of a collection play, in what order, and when the run moves to the next one. */
class CollectionQueueTest {
    private val scope = "account"
    private val server = connection.serverUrl
    private val books = listOf(album("1", "Book one"), album("2", "Book two"), album("3", "Book three"), album("4", "Book four"))

    private fun saved(id: String, finished: Boolean, owner: String? = scope, at: String = server, mode: LibraryMode = LibraryMode.AUDIOBOOK) =
        progress(album(id), 0, 1_000, finished = finished, scope = owner, mode = mode).copy(server = at)

    private fun unfinished(history: List<ListeningProgress>, list: List<PlexAlbum> = books, offline: Boolean = false, downloads: List<org.johnfegan.plextouch.data.DownloadStatus> = emptyList()) =
        unfinishedBooks(list, history, downloads, server, scope, offline).map { it.id }

    @Test fun finishedBooksAreSkippedAndCollectionOrderIsKept() {
        val history = listOf(saved("1", finished = true), saved("3", finished = false), saved("4", finished = true))
        assertEquals("in-progress and unstarted books, in the collection's order", listOf("2", "3"), unfinished(history))
    }

    @Test fun orderIsDeterministicAndRepeatsAndPlaylistsAreDropped() {
        val list = listOf(album("2"), album("1"), album("2"), PlexAlbum("playlist:9", "A playlist", "", null, 1))
        assertEquals(listOf("2", "1"), unfinished(emptyList(), list))
        assertEquals(unfinished(emptyList(), list), unfinished(emptyList(), list))
    }

    @Test fun onlyThisAccountServerAndAudiobookProgressCounts() {
        val history = listOf(
            saved("1", finished = true, owner = "someone-else"),
            saved("2", finished = true, at = "https://other:32400"),
            saved("3", finished = true, mode = LibraryMode.MUSIC),
        )
        assertEquals("another account's, server's or mode's finish never hides a book", listOf("1", "2", "3", "4"), unfinished(history))
    }

    @Test fun theNewestSavedEntryForABookWins() {
        // History is newest first: the book was restarted after it was finished.
        val history = listOf(saved("1", finished = false), saved("1", finished = true))
        assertEquals(listOf("1", "2", "3", "4"), unfinished(history))
    }

    @Test fun offlineKeepsOnlyFullyDownloadedBooks() {
        val ready = download(album("3"), listOf(track("31", local = true)))
        val partial = download(album("2"), listOf(track("21", local = true)), ready = false)
        assertEquals(listOf("3"), unfinished(emptyList(), offline = true, downloads = listOf(ready, partial)))
        assertEquals("online, every unfinished book is queued", listOf("1", "2", "3", "4"), unfinished(emptyList(), downloads = listOf(ready)))
    }

    @Test fun anEmptyOrFinishedCollectionStartsNothing() {
        assertNull(startCollectionRun("50", emptyList(), server, scope))
        val allDone = books.map { saved(it.id, finished = true) }
        assertNull(startCollectionRun("50", unfinishedBooks(books, allDone, emptyList(), server, scope, false), server, scope))
    }

    private fun run(vararg ids: String, current: String = ids.first()) = CollectionRun(server, scope, "50", ids.map { id -> books.first { it.id == id } }, current)
    private fun playing(id: String, playing: Boolean) = PlaybackState(ready = true, album = books.first { it.id == id }, mode = LibraryMode.AUDIOBOOK, playing = playing)

    @Test fun aRunStartsAtTheFirstUnfinishedBook() {
        val started = startCollectionRun("50", unfinishedBooks(books, listOf(saved("1", finished = true)), emptyList(), server, scope, false), server, scope)!!
        assertEquals("2", started.currentId)
        assertEquals(listOf("2", "3", "4"), started.books.map { it.id })
    }

    @Test fun theRunWaitsWhilePlayingOrPausedMidBook() {
        val current = run("2", "3")
        assertEquals(RunStep.Keep(current), collectionRunStep(current, playing("2", playing = true), emptyList(), server, scope))
        assertEquals(RunStep.Keep(current), collectionRunStep(current, playing("2", playing = false), listOf(saved("2", finished = false)), server, scope))
        assertEquals("nothing loaded yet", RunStep.Keep(current), collectionRunStep(current, PlaybackState(), emptyList(), server, scope))
    }

    @Test fun aFinishedBookAdvancesToTheNextUnfinishedOne() {
        val current = run("2", "3", "4")
        // Book three was finished elsewhere meanwhile, so it is skipped.
        val history = listOf(saved("2", finished = true), saved("3", finished = true))
        val step = collectionRunStep(current, playing("2", playing = false), history, server, scope)
        assertEquals(RunStep.Next(current.copy(currentId = "4"), books[3]), step)
    }

    @Test fun theLastFinishedBookEndsTheRun() {
        assertEquals(RunStep.End, collectionRunStep(run("2", "3", current = "3"), playing("3", playing = false), listOf(saved("3", finished = true)), server, scope))
    }

    @Test fun theFinishedPreviousBookStillReportedByThePlayerNeverAdvancesTwice() {
        val moved = run("2", "3", "4", current = "3")
        assertEquals(RunStep.Keep(moved), collectionRunStep(moved, playing("2", playing = false), listOf(saved("2", finished = true)), server, scope))
    }

    @Test fun startingAnotherBookOfTheRunMovesItThere() {
        val current = run("2", "3", "4")
        assertEquals(RunStep.Keep(current.copy(currentId = "4")), collectionRunStep(current, playing("4", playing = true), emptyList(), server, scope))
    }

    @Test fun playingSomethingElseOrChangingAccountOrServerEndsTheRun() {
        val current = run("2", "3")
        assertEquals(RunStep.End, collectionRunStep(current, playing("1", playing = true), emptyList(), server, scope))
        assertEquals(RunStep.End, collectionRunStep(current, playing("2", playing = true).copy(mode = LibraryMode.MUSIC), emptyList(), server, scope))
        assertEquals(RunStep.End, collectionRunStep(current, playing("2", playing = true), emptyList(), server, "someone-else"))
        assertEquals(RunStep.End, collectionRunStep(current, playing("2", playing = true), emptyList(), "https://other:32400", scope))
        assertEquals(RunStep.End, collectionRunStep(current, playing("2", playing = true), emptyList(), null, scope))
    }

    @Test fun eachBookIsPlayedOnItsOwnSoItsProgressAndTimelineStaySeparate() {
        val player = FakePlayer()
        val store = FakeStore()
        val playback = AlbumPlayback(FakeGateways(), player, store)
        val started = startCollectionRun("50", books.take(2), server, scope)!!
        val history = listOf(saved("1", finished = false).copy(trackIndex = 1, positionMs = 5_000, trackId = "12"))
        playback.play(connection, started.books.first(), listOf(track("11"), track("12")), LibraryMode.AUDIOBOOK, PlaybackState(ready = true), history, scope, offlineOnly = false)
        val next = collectionRunStep(started, playing("1", playing = false), listOf(saved("1", finished = true)), server, scope) as RunStep.Next
        playback.play(connection, next.album, listOf(track("21")), LibraryMode.AUDIOBOOK, playing("1", playing = false), listOf(saved("1", finished = true)), scope, offlineOnly = false)
        assertEquals("one player queue per book", listOf(listOf("11", "12"), listOf("21")), player.plays.map { play -> play.tracks.map { it.id } })
        assertEquals(listOf("1", "2"), player.plays.map { it.album.id })
        assertEquals("the first book resumed at its own saved spot", 1 to 5_000L, player.plays.first().index to player.plays.first().positionMs)
        assertEquals("the next book starts at its beginning", 0 to 0L, player.plays.last().index to player.plays.last().positionMs)
        assertTrue(player.commands.isEmpty())
    }
}
