package org.johnfegan.plextouch.player

import androidx.media3.common.C
import androidx.media3.common.Player
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.ui.BookProgress
import org.johnfegan.plextouch.ui.bookProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTimelineTest {
    private fun file(id: String, durationMs: Long) = PlexTrack(id, "Part $id", "Narrator", "Book", durationMs, "/library/parts/$id/file.mp3", "mp3")
    // Three 10-minute files.
    private val even = listOf(600_000L, 600_000L, 600_000L)
    private fun skip(durations: List<Long>, index: Int, at: Long, delta: Long, live: Long = durations[index], repeat: Boolean = false, shuffled: Boolean = false) =
        bookSkip(durations, index, at, delta, live, repeat, shuffled)

    @Test fun bookOffsetsMapBothWays() {
        val timeline = BookTimeline(even)
        assertEquals(1_800_000L, timeline.totalMs)
        assertEquals(900_000L, timeline.bookOffset(1, 300_000))
        assertEquals("an offset past a known end is held there", 1_200_000L, timeline.bookOffset(1, 900_000))
        assertEquals(BookPoint(1, 300_000), timeline.locate(900_000))
        assertEquals("a boundary belongs to the file it starts", BookPoint(1, 0), timeline.locate(600_000))
        assertEquals(BookPoint(2, 600_000), timeline.locate(5_000_000))
        assertEquals(BookPoint(0, 0), timeline.locate(-5))
    }

    @Test fun zeroLengthAndUnknownFilesTakeNoRoomButStayInTheQueue() {
        val timeline = BookTimeline(listOf(600_000L, 0L, C.TIME_UNSET, 600_000L))
        assertEquals(4, timeline.size)
        assertEquals(1_200_000L, timeline.totalMs)
        assertEquals(600_000L, timeline.startOf(3))
        assertEquals("a position inside an unknown file maps to its start", 600_000L, timeline.bookOffset(2, 42_000))
        assertEquals("never inside an unknown file", BookPoint(3, 0), timeline.locate(600_000))
        assertEquals(BookPoint(0, 599_999), timeline.locate(599_999))
        assertNull(BookTimeline(listOf(0L, C.TIME_UNSET)).locate(10))
        assertEquals(0L, BookTimeline(emptyList()).totalMs)
    }

    @Test fun forwardSkipCrossesIntoTheNextFileWithTheRemainder() {
        assertEquals(BookPoint(0, 590_000), skip(even, 0, 560_000, 30_000))
        assertEquals(BookPoint(1, 10_000), skip(even, 0, 580_000, 30_000))
        assertEquals("a boundary hit exactly starts the next file", BookPoint(1, 0), skip(even, 0, 570_000, 30_000))
        assertEquals("a long skip hops whole files", BookPoint(2, 30_000), skip(even, 0, 580_000, 650_000))
    }

    @Test fun backwardSkipCrossesIntoThePreviousFileFromItsEnd() {
        assertEquals(BookPoint(0, 580_000), skip(even, 1, 10_000, -30_000))
        assertEquals(BookPoint(1, 0), skip(even, 1, 30_000, -30_000))
        assertEquals(BookPoint(0, 590_000), skip(even, 2, 10_000, -620_000))
        assertEquals("the start of the book is a floor, even with repeat", BookPoint(0, 0), skip(even, 0, 10_000, -30_000, repeat = true))
        assertEquals(BookPoint(0, 0), skip(even, 1, 10_000, -5_000_000))
    }

    @Test fun theEndOfTheBookClampsUnlessRepeatAllWraps() {
        assertEquals(BookPoint(2, 600_000), skip(even, 2, 590_000, 30_000))
        assertEquals(BookPoint(0, 20_000), skip(even, 2, 590_000, 30_000, repeat = true))
        assertEquals("a single file keeps the old clamp", BookPoint(0, 120_000), skip(listOf(120_000L), 0, 100_000, 30_000))
        assertEquals(BookPoint(2, 600_000), skip(even, 0, 590_000, 50_000_000))
    }

    @Test fun unknownDurationsKeepTheInFileSkipAndNeverInventAnOffset() {
        val gappy = listOf(600_000L, 0L, 600_000L)
        assertEquals("the current file's length is unknown: plain relative seek", BookPoint(1, 60_000), skip(gappy, 1, 30_000, 30_000, live = C.TIME_UNSET))
        assertEquals(BookPoint(1, 0), skip(gappy, 1, 10_000, -30_000, live = C.TIME_UNSET))
        assertEquals("crossing into an unknown file lands at its start", BookPoint(1, 0), skip(gappy, 0, 590_000, 30_000))
        assertEquals("backwards into an unknown file lands at its start", BookPoint(1, 0), skip(gappy, 2, 10_000, -30_000))
        assertEquals("the player's live length wins once known", BookPoint(2, 10_000), skip(gappy, 1, 50_000, 30_000, live = 70_000))
        assertEquals("all-unknown book with repeat cannot spin", BookPoint(1, 0), skip(listOf(0L, 0L), 0, 10_000, 30_000, live = 20_000, repeat = true))
    }

    @Test fun shuffledQueuesKeepTheOldInFileSkip() {
        assertEquals(BookPoint(0, 600_000), skip(even, 0, 590_000, 30_000, shuffled = true))
        assertEquals(BookPoint(1, 0), skip(even, 1, 10_000, -30_000, shuffled = true))
    }

    @Test fun resumeMapsSavedProgressOntoTheCurrentTrackList() {
        val tracks = listOf(file("a", 600_000), file("b", 600_000), file("c", 0))
        assertEquals(BookPoint(1, 42_000), resumePoint(tracks, 1, 42_000, "b"))
        assertEquals("a checkpoint on a file end resumes at the next file", BookPoint(1, 0), resumePoint(tracks, 0, 600_000, "a"))
        assertEquals("the last file has no next one", BookPoint(2, 900_000), resumePoint(tracks, 2, 900_000, "c"))
        assertEquals("a moved track is found by id", BookPoint(2, 5_000), resumePoint(listOf(file("x", 600_000)) + tracks, 1, 5_000, "b"))
        assertEquals("an unknown id keeps the saved slot", BookPoint(0, 5_000), resumePoint(tracks, 0, 5_000, "gone"))
        assertEquals("a duplicated id keeps its saved slot", BookPoint(2, 5_000), resumePoint(listOf(file("a", 600_000), file("b", 600_000), file("a", 600_000)), 2, 5_000, "a"))
        assertEquals(BookPoint(2, 0), resumePoint(tracks, 9, -4, null))
        assertEquals(BookPoint(0, 0), resumePoint(emptyList(), 3, 7_000, "a"))
    }

    @Test fun savedElapsedIsBookLevelAndNeverPassesTheFileEnd() {
        val timeline = BookTimeline(even)
        assertEquals(1_242_000L, bookElapsedMs(timeline.startOf(2), 42_000, 600_000))
        assertEquals(1_200_000L, bookElapsedMs(timeline.startOf(1), 700_000, 600_000))
        assertEquals("unknown length: trust the player", 1_270_000L, bookElapsedMs(1_200_000, 70_000, 0))
        assertEquals(0L, bookElapsedMs(0, -10, 600_000))
    }

    @Test fun onlyTheEndOfTheLastFileWithoutRepeatIsACompletion() {
        assertFalse("a file boundary keeps playing", finishedBook(Player.STATE_READY, 1, 3, Player.REPEAT_MODE_OFF))
        assertFalse(finishedBook(Player.STATE_BUFFERING, 2, 3, Player.REPEAT_MODE_OFF))
        assertFalse("ended before the last file is not the end of the book", finishedBook(Player.STATE_ENDED, 1, 3, Player.REPEAT_MODE_OFF))
        assertFalse("repeat-all never finishes", finishedBook(Player.STATE_ENDED, 2, 3, Player.REPEAT_MODE_ALL))
        assertFalse(finishedBook(Player.STATE_ENDED, 0, 0, Player.REPEAT_MODE_OFF))
        assertTrue(finishedBook(Player.STATE_ENDED, 2, 3, Player.REPEAT_MODE_OFF))
    }

    @Test fun bookBarFollowsTheQueueNowPlayingOnly() {
        val queue = listOf(file("a", 600_000), file("b", 0), file("c", 600_000))
        assertEquals(BookProgress(642_000, 1_200_000), bookProgress(queue, 2, "c", 42_000))
        assertEquals(BookProgress(600_000, 1_200_000), bookProgress(queue, 1, "b", 42_000))
        assertEquals(.535f, bookProgress(queue, 2, "c", 42_000)!!.fraction, .001f)
        assertEquals(558_000L, bookProgress(queue, 2, "c", 42_000)!!.remainingMs)
        assertNull("a replaced queue never lends its lengths", bookProgress(queue, 2, "other-book-file", 42_000))
        assertNull(bookProgress(queue, 7, "c", 0))
        assertNull("one file: the slider is already the book", bookProgress(listOf(file("a", 600_000)), 0, "a", 0))
        assertNull(bookProgress(listOf(file("a", 0), file("b", 0)), 0, "a", 0))
    }
}
