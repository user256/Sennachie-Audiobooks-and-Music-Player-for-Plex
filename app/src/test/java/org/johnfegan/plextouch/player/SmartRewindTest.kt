package org.johnfegan.plextouch.player

import androidx.media3.common.Player
import org.johnfegan.plextouch.data.PlexChapter
import org.johnfegan.plextouch.data.PlexTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartRewindTest {
    private val scope = "a".repeat(64)
    private val server = "https://plex.lan:32400"
    private val minute = 60_000L

    private fun pause(positionMs: Long, reason: PauseReason = PauseReason.USER, trackId: String = "11", elapsed: Long = 1_000_000, wall: Long = 1_700_000_000_000, boot: Int = 7, replayUntil: Long? = null) =
        PausedAt(scope, server, "10", trackId, positionMs, reason, elapsed, wall, boot, replayUntil)

    /** Resuming the same book, file and offset [pausedForMs] later within the same boot. */
    private fun resume(pause: PausedAt?, positionMs: Long = pause?.positionMs ?: 0, pausedForMs: Long = minute, setting: Int = 10,
                       chapterStartMs: Long = 0, trackId: String = "11", audiobook: Boolean = true) =
        RewindPolicy.resumeTarget(pause, audiobook, scope, server, "10", trackId, positionMs, chapterStartMs, setting,
            (pause?.elapsedRealtimeMs ?: 0) + pausedForMs, (pause?.wallClockMs ?: 0) + pausedForMs, pause?.bootCount ?: 7)

    @Test fun onlyAPauseOfAtLeastThirtySecondsRewinds() {
        assertNull(resume(pause(5 * minute), pausedForMs = 29_999))
        assertEquals(5 * minute - 10_000, resume(pause(5 * minute), pausedForMs = 30_000))
    }

    @Test fun offAndOutOfRangeSettingsAreBounded() {
        assertNull(resume(pause(5 * minute), setting = 0))
        assertEquals("never more than 60 s", 5 * minute - 60_000, resume(pause(5 * minute), setting = 600))
        assertEquals(5 * minute - 5_000, resume(pause(5 * minute), setting = 5))
        assertEquals(listOf(0, 5, 10, 15, 30, 60), RewindPolicy.CHOICES_SECONDS)
    }

    @Test fun userFocusLossAndNoisyPausesQualifyAndOtherStopsDoNot() {
        listOf(PauseReason.USER, PauseReason.FOCUS_LOSS, PauseReason.NOISY).forEach { assertEquals(it.name, 50_000L, resume(pause(60_000, it))) }
        assertNull(resume(pause(60_000, PauseReason.OTHER)))
        assertEquals(PauseReason.USER, pauseReason(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
        assertEquals(PauseReason.FOCUS_LOSS, pauseReason(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
        assertEquals(PauseReason.NOISY, pauseReason(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY))
        assertEquals(PauseReason.OTHER, pauseReason(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
        assertEquals(PauseReason.OTHER, pauseReason(Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE))
    }

    @Test fun aRewindNeverCrossesTheStartOfTheFile() {
        assertEquals(0L, resume(pause(4_000)))
        assertNull("already at the start: nothing to rewind", resume(pause(0)))
    }

    @Test fun aSingleFileBookClampsToTheEmbeddedChapterStart() {
        val book = PlexTrack("11", "Book", "Author", "Book", 3_600_000, "/parts/11", "m4b",
            chapterMarkers = listOf(PlexChapter(1, "One", 0, 100_000), PlexChapter(2, "Two", 100_000, 3_600_000)))
        val position = 105_000L
        val start = RewindPolicy.chapterStart(book, position)
        assertEquals(100_000L, start)
        assertEquals(100_000L, resume(pause(position), chapterStartMs = start))
        assertEquals("further into the chapter the full amount applies", 140_000L, resume(pause(150_000), chapterStartMs = RewindPolicy.chapterStart(book, 150_000)))
        assertEquals("a file without markers clamps to its own start", 0L, RewindPolicy.chapterStart(book.copy(chapterMarkers = null), position))
        assertEquals(0L, RewindPolicy.chapterStart(null, position))
    }

    @Test fun seekingSkippingOrChoosingAChapterWhilePausedCancelsTheRewind() {
        val paused = pause(5 * minute)
        assertNull("a seek or 30 s skip moved the offset", resume(paused, positionMs = 5 * minute - 30_000))
        assertNull("a chapter selection loaded another file", resume(paused, trackId = "12", positionMs = 0))
        assertEquals("resuming where it stopped still rewinds", 5 * minute + 200 - 10_000, resume(paused, positionMs = 5 * minute + 200))
        assertNull("music never rewinds", resume(paused, audiobook = false))
        assertNull("nothing paused", resume(null))
    }

    @Test fun repeatedPausesWithoutListeningInBetweenDoNotStack() {
        // First pause: rewound from 5:00 to 4:50.
        val first = resume(pause(5 * minute))!!
        val mark = RewindMark("11", 5 * minute)
        // Paused again 2 s later, long enough to qualify, but the listener has not heard past 5:00 yet.
        val again = pause(first + 2_000, replayUntil = RewindPolicy.replayUntil(mark, "11", first + 2_000))
        assertEquals(5 * minute, again.replayUntilMs)
        assertNull(resume(again))
        // Once playback passes the earlier pause point, the next long pause rewinds again.
        val later = pause(5 * minute + 20_000, replayUntil = RewindPolicy.replayUntil(mark, "11", 5 * minute + 20_000))
        assertNull(later.replayUntilMs)
        assertEquals(5 * minute + 10_000, resume(later))
        assertNull("another file does not inherit the limit", RewindPolicy.replayUntil(mark, "12", 1_000))
    }

    @Test fun offlineResumptionFromALocalFileRewindsTheSameWay() {
        // The pause was captured while streaming; the resume plays the downloaded copy of the same file (same ids and server).
        val streamed = pause(2 * minute, PauseReason.NOISY)
        assertEquals(2 * minute - 15_000, resume(streamed, setting = 15))
    }

    @Test fun aPersistedPauseSurvivesTheServiceBeingRecreated() {
        val prefs = mutableMapOf<String, String>()
        val first = PauseRecordStore({ prefs["paused"] }, { json -> if (json == null) prefs.remove("paused") else prefs["paused"] = json })
        val paused = pause(5 * minute, PauseReason.FOCUS_LOSS, replayUntil = 6 * minute)
        first.save(paused)
        val recreated = PauseRecordStore({ prefs["paused"] }, { json -> if (json == null) prefs.remove("paused") else prefs["paused"] = json })
        assertEquals(paused, recreated.read())
        recreated.save(null)
        assertNull(PauseRecordStore({ prefs["paused"] }, {}).read())
        assertNull("an unreadable value is no pause", PauseRecordStore({ "{broken" }, {}).read())
    }

    @Test fun acrossARebootTheWallClockMeasuresThePause() {
        val paused = pause(5 * minute, elapsed = 9_000_000, wall = 1_700_000_000_000, boot = 7)
        assertEquals("same boot: elapsed realtime", 40_000L, RewindPolicy.pausedFor(paused, 9_040_000, 1_700_000_999_999, 7))
        assertEquals("rebooted: the wall clock", 2 * minute, RewindPolicy.pausedFor(paused, 5_000, 1_700_000_000_000 + 2 * minute, 8))
        assertEquals("elapsed went backwards without a boot count", 3 * minute, RewindPolicy.pausedFor(paused, 1_000, 1_700_000_000_000 + 3 * minute, -1))
        assertEquals("a clock set backwards is no pause", 0L, RewindPolicy.pausedFor(paused, 1_000, 1_600_000_000_000, 8))
        assertEquals(5 * minute - 10_000, RewindPolicy.resumeTarget(paused, true, scope, server, "10", "11", 5 * minute, 0, 10, 5_000, 1_700_000_000_000 + 2 * minute, 8))
    }

    @Test fun theRewindIsAnnouncedOnce() {
        val before = PlaybackState(ready = true)
        val after = before.copy(rewoundMs = 9_800, rewindAt = 50_000)
        assertEquals(10, RewindPolicy.notice(before, after, 50_400))
        assertNull("the same rewind is not announced again", RewindPolicy.notice(after, after.copy(playing = true), 50_600))
        assertNull("a screen that reconnects later stays quiet", RewindPolicy.notice(before, after, 90_000))
        assertNull(RewindPolicy.notice(before, before.copy(playing = true), 50_400))
    }
}
