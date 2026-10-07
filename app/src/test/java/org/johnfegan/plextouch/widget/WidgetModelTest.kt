package org.johnfegan.plextouch.widget

import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {
    private val server = "https://plex.example:32400"
    private val scope = "a".repeat(64)
    private val otherScope = "b".repeat(64)
    private val book = PlexAlbum("book", "The Long Road", "A. Narrator", 2020, 12)
    private val album = PlexAlbum("album", "Night Songs", "The Band", 2019, 10)

    private fun progress(item: PlexAlbum, mode: LibraryMode, updatedAt: Long, accountScope: String? = scope, on: String = server, elapsed: Long = 250, total: Long = 1_000) =
        ListeningProgress(item, mode, on, 0, elapsed, elapsed, total, updatedAt, accountScope = accountScope)

    private fun model(
        history: List<ListeningProgress> = emptyList(), live: LivePlayback? = null, accountScope: String? = scope, on: String? = server,
        offlineOnly: Boolean = false, downloads: List<DownloadedTitle> = emptyList(), privacy: WidgetPrivacy = WidgetPrivacy(),
    ) = widgetModel(history, live, accountScope, on, offlineOnly, downloads, privacy)

    @Test fun signedOutIsEmptyEvenWithHistoryOnThePhone() {
        val history = listOf(progress(book, LibraryMode.AUDIOBOOK, 10))
        assertEquals(WidgetState.Empty(EmptyReason.SIGNED_OUT), model(history, on = null, accountScope = null))
        assertEquals(WidgetState.Empty(EmptyReason.SIGNED_OUT), model(history, accountScope = null))
    }

    @Test fun nothingPlayedIsEmpty() {
        assertEquals(WidgetState.Empty(EmptyReason.NOTHING_PLAYED), model())
    }

    @Test fun showsTheMostRecentTitleAcrossModesWithProgress() {
        val state = model(listOf(progress(book, LibraryMode.AUDIOBOOK, 10), progress(album, LibraryMode.MUSIC, 20, elapsed = 500))) as WidgetState.Item
        assertEquals("album", state.albumId)
        assertEquals(LibraryMode.MUSIC, state.mode)
        assertEquals("Night Songs", state.title)
        assertEquals("The Band", state.creator)
        assertEquals(500, state.progressPermille)
        assertFalse(state.playing)
        assertFalse(state.live)
        assertEquals(WidgetArtwork.Fetched(artworkKey(server, "album")), state.artwork)
    }

    @Test fun livePlaybackWinsAndReportsPlaying() {
        val history = listOf(progress(book, LibraryMode.AUDIOBOOK, 10, elapsed = 100), progress(album, LibraryMode.MUSIC, 20))
        val state = model(history, LivePlayback(book, LibraryMode.AUDIOBOOK, server, scope, playing = true)) as WidgetState.Item
        assertEquals("book", state.albumId)
        assertTrue(state.playing)
        assertTrue(state.live)
        assertEquals(100, state.progressPermille)
    }

    @Test fun accountSwitchHidesTheOtherAccountsTitles() {
        val history = listOf(progress(book, LibraryMode.AUDIOBOOK, 10, accountScope = otherScope), progress(album, LibraryMode.MUSIC, 5, on = "https://other:32400"))
        val live = LivePlayback(book, LibraryMode.AUDIOBOOK, server, otherScope, playing = true)
        assertEquals(WidgetState.Empty(EmptyReason.NOTHING_PLAYED), model(history, live))
        // The same history read under the account that made it shows the book again.
        assertEquals("book", (model(history, accountScope = otherScope) as WidgetState.Item).albumId)
    }

    @Test fun legacyUnscopedHistoryIsNotShown() {
        assertEquals(WidgetState.Empty(EmptyReason.NOTHING_PLAYED), model(listOf(progress(book, LibraryMode.AUDIOBOOK, 10, accountScope = null))))
    }

    @Test fun offlineOnlyShowsOnlyCompleteDownloadsWithTheirSavedCover() {
        val history = listOf(progress(book, LibraryMode.AUDIOBOOK, 10), progress(album, LibraryMode.MUSIC, 20))
        val downloads = listOf(DownloadedTitle(server, "book", "file:///data/cover.jpg"), DownloadedTitle("https://other:32400", "album", null))
        val state = model(history, offlineOnly = true, downloads = downloads) as WidgetState.Item
        assertEquals("book", state.albumId)
        assertEquals(WidgetArtwork.Downloaded("file:///data/cover.jpg"), state.artwork)
        assertEquals(WidgetState.Empty(EmptyReason.NOTHING_PLAYED), model(history, offlineOnly = true))
        // A streamed title still loaded in the service is not shown offline either.
        val streaming = LivePlayback(album, LibraryMode.MUSIC, server, scope, playing = false)
        assertEquals("book", (model(history, streaming, offlineOnly = true, downloads = downloads) as WidgetState.Item).albumId)
    }

    @Test fun onlineADownloadedTitleStillPrefersItsSavedCover() {
        val state = model(listOf(progress(book, LibraryMode.AUDIOBOOK, 10)), downloads = listOf(DownloadedTitle(server, "book", "file:///c.jpg"))) as WidgetState.Item
        assertEquals(WidgetArtwork.Downloaded("file:///c.jpg"), state.artwork)
    }

    @Test fun lockScreenPrivacyWithholdsTitleAndArtworkButKeepsTransport() {
        val history = listOf(progress(book, LibraryMode.AUDIOBOOK, 10))
        val live = LivePlayback(book, LibraryMode.AUDIOBOOK, server, scope, playing = true)
        for (privacy in listOf(WidgetPrivacy(keyguardHost = true, showPrivateOnLockScreen = false), WidgetPrivacy(keyguardHost = true, showPrivateOnLockScreen = null))) {
            val state = model(history, live, privacy = privacy) as WidgetState.Item
            assertTrue(state.redacted)
            assertNull(state.title)
            assertNull(state.creator)
            assertNull(state.artwork)
            assertTrue(state.playing)
            assertEquals(250, state.progressPermille)
        }
        val allowed = model(history, live, privacy = WidgetPrivacy(keyguardHost = true, showPrivateOnLockScreen = true)) as WidgetState.Item
        assertFalse(allowed.redacted)
        assertEquals("The Long Road", allowed.title)
        // The home screen is never redacted, whatever the lock-screen setting.
        assertFalse((model(history, privacy = WidgetPrivacy(keyguardHost = false, showPrivateOnLockScreen = false)) as WidgetState.Item).redacted)
    }

    @Test fun progressIsClampedAndBlankCreatorFallsBack() {
        val anonymous = book.copy(artist = " ")
        val state = model(listOf(progress(anonymous, LibraryMode.AUDIOBOOK, 10, elapsed = 5_000, total = 1_000))) as WidgetState.Item
        assertEquals(1000, state.progressPermille)
        assertNull(state.creator)
    }

    @Test fun throttleRendersTheFirstRequestAtOnceAndCoalescesTheRest() {
        val throttle = WidgetThrottle(minGapMs = 1_000, checkpointGapMs = 15_000)
        assertEquals(0L, throttle.request(now = 0))
        assertNull(throttle.request(now = 10))
        throttle.rendered(now = 20)
        // A state change 300 ms later waits out the remainder of the second.
        assertEquals(720L, throttle.request(now = 300))
        assertNull(throttle.request(now = 400))
        throttle.rendered(now = 1_020)
        assertEquals(0L, throttle.request(now = 5_000))
    }

    @Test fun throttleDropsCheckpointsUntilTheirLongerGap() {
        val throttle = WidgetThrottle(minGapMs = 1_000, checkpointGapMs = 15_000)
        assertEquals(0L, throttle.request(now = 0, checkpoint = true))
        throttle.rendered(now = 0)
        assertNull(throttle.request(now = 5_000, checkpoint = true))
        assertNull(throttle.request(now = 14_999, checkpoint = true))
        assertEquals(0L, throttle.request(now = 15_000, checkpoint = true))
        throttle.rendered(now = 15_000)
        // A real state change is never held back by the checkpoint gap.
        assertEquals(0L, throttle.request(now = 16_000))
    }
}
