package org.johnfegan.plextouch.ui

import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.DownloadAlbum
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.widget.widgetPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ticket 140: what TalkBack is given to say, and where its adjust gestures land. */
class AccessibilityTest {
    private fun hours(n: Int) = uiPlural(R.plurals.a11y_hours, n)
    private fun minutes(n: Int) = uiPlural(R.plurals.a11y_minutes, n)
    private fun seconds(n: Int) = uiPlural(R.plurals.a11y_seconds, n)
    private fun join(a: UiText, b: UiText) = uiText(R.string.a11y_duration_join, a, b)

    @Test fun durationsAreSpokenInWholeWordsWithEveryNonZeroUnit() {
        assertEquals(join(minutes(12), seconds(3)), spokenDuration(723_000))
        assertEquals(join(join(hours(1), minutes(5)), seconds(3)), spokenDuration(3_903_000))
        assertEquals(minutes(45), spokenDuration(2_700_000))
        assertEquals(join(hours(20), seconds(1)), spokenDuration(72_001_999))
        assertEquals(seconds(1), spokenDuration(1_999))
    }

    @Test fun zeroAndNegativeDurationsReadAsZeroSeconds() {
        assertEquals(seconds(0), spokenDuration(0))
        assertEquals(seconds(0), spokenDuration(-5_000))
        assertEquals(seconds(0), spokenDuration(999))
    }

    @Test fun theSeekSliderStateIsThePositionOfTheLength() {
        assertEquals(uiText(R.string.a11y_position_of, join(minutes(12), seconds(3)), minutes(45)), spokenPosition(723_000, 2_700_000))
        // A position past the end (a stale tick) is clamped; an unknown length reads the position alone.
        assertEquals(uiText(R.string.a11y_position_of, minutes(45), minutes(45)), spokenPosition(2_800_000, 2_700_000))
        assertEquals(join(minutes(12), seconds(3)), spokenPosition(723_000, 0))
    }

    @Test fun theWholeBookBarReadsElapsedLengthAndTimeLeft() {
        val spoken = spokenBookPosition(BookProgress(elapsedMs = 10_920_000, totalMs = 36_000_000))
        assertEquals(uiText(R.string.a11y_book_position, join(hours(3), minutes(2)), hours(10), join(hours(6), minutes(58))), spoken)
    }

    @Test fun timeLeftIsRoundedDownToMinutes() {
        assertEquals(join(hours(7), minutes(3)), spokenMinutes(25_399_000))
        assertEquals(minutes(0), spokenMinutes(30_000))
        assertEquals(minutes(0), spokenMinutes(-1))
    }

    @Test fun speedsAreSpokenWithoutTheMultiplicationSign() {
        assertEquals(uiText(R.string.a11y_speed, "1.25"), spokenSpeed(1.25f))
        assertEquals(uiText(R.string.a11y_speed, "1"), spokenSpeed(1f))
        assertEquals(uiText(R.string.a11y_speed, "0.75"), spokenSpeed(.75f))
        assertEquals(uiText(R.string.a11y_speed, "2"), spokenSpeed(2f))
    }

    @Test fun togglesHaveSpokenStates() {
        assertEquals(uiText(R.string.a11y_rated_five_stars), favouriteState(true))
        assertEquals(uiText(R.string.a11y_not_rated), favouriteState(false))
        assertEquals(uiText(R.string.a11y_on), onOffState(true))
        assertEquals(uiText(R.string.a11y_off), onOffState(false))
        assertEquals(uiText(R.string.a11y_off), sleepState(null))
        assertEquals(uiPlural(R.plurals.a11y_minutes_left, 12), sleepState(12))
        assertEquals(uiPlural(R.plurals.a11y_percent, 46), spokenPercent(.456f))
        assertEquals(uiPlural(R.plurals.a11y_percent, 100), spokenPercent(1.4f))
    }

    @Test fun theDownloadControlSaysWhetherTheTitleIsSaved() {
        val tracks = listOf(PlexTrack("1", "One", "A", "B", 1, "/1", "mp3"), PlexTrack("2", "Two", "A", "B", 1, "/2", "mp3"), PlexTrack("3", "Three", "A", "B", 1, "/3", "mp3"))
        val record = DownloadAlbum("server", "lib", LibraryMode.AUDIOBOOK, PlexAlbum("9", "B", "A", null, 3), emptyList())
        fun status(completed: Int, failed: Int = 0) = DownloadStatus(record, tracks, null, completed, failed, 0, 0, 0)
        assertEquals(uiText(R.string.a11y_not_saved_offline), downloadState(null))
        assertEquals(uiText(R.string.a11y_saved_offline), downloadState(status(3)))
        assertEquals(uiPlural(R.plurals.a11y_saving_offline, 3, 1, 3), downloadState(status(1)))
        assertEquals(uiPlural(R.plurals.a11y_download_failed, 2, 1, 3, 2), downloadState(status(1, failed = 2)))
    }

    @Test fun aTalkBackAdjustOnTheSeekSliderMovesThirtySeconds() {
        val hour = 3_600_000L
        // Compose's default adjust is a twentieth of the range: three minutes here.
        assertEquals(1_030_000L, accessibleSeekTarget(1_000_000, 1_180_000f, hour))
        assertEquals(970_000L, accessibleSeekTarget(1_000_000, 820_000f, hour))
        // Clamped at both ends of the file.
        assertEquals(0L, accessibleSeekTarget(10_000, -170_000f, hour))
        assertEquals(hour, accessibleSeekTarget(hour - 5_000, hour + 175_000f, hour))
    }

    @Test fun aValueSetDirectlyOnTheSeekSliderIsHonoured() {
        val hour = 3_600_000L
        assertEquals(2_000_000L, accessibleSeekTarget(1_000_000, 2_000_000f, hour))
        assertEquals(1_000_000L, accessibleSeekTarget(1_000_000, 1_000_000f, hour))
        assertEquals(hour, accessibleSeekTarget(0, 9_000_000f, hour))
        assertEquals(0L, accessibleSeekTarget(5_000, 10_000f, 0))
    }

    @Test fun theLetterRailIsOneAdjustableControlThatSkipsEmptyLetters() {
        val rail = LetterRail.of(mapOf("A" to 0, "C" to 4, "M" to 9))
        assertEquals(27, rail.letters.size)
        assertEquals(1, rail.indexOf(null))
        assertEquals(13, rail.indexOf("M"))
        assertEquals("C", rail.step(1, 2f))
        assertEquals("M", rail.step(3, 4f))
        assertEquals("A", rail.step(3, 2f))
        // Past the last letter with artists there is nowhere to go; a request for the current letter is not a move.
        assertNull(rail.step(13, 14f))
        assertNull(rail.step(1, 1f))
        assertNull(rail.step(1, 0f))
        // A long jump into empty letters lands on the nearest letter with artists on the way back.
        assertEquals("A", rail.step(13, 0f))
        assertEquals("M", rail.step(1, 26f))
    }

    @Test fun theWidgetBarReadsAWholePercent() {
        assertEquals(0, widgetPercent(0))
        assertEquals(46, widgetPercent(456))
        assertEquals(99, widgetPercent(994))
        assertEquals(100, widgetPercent(995))
        assertEquals(100, widgetPercent(1_200))
        assertEquals(0, widgetPercent(-5))
    }
}
