package org.johnfegan.plextouch.app

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PhoneCheckpoint
import org.johnfegan.plextouch.data.PlexChapterProgress
import org.johnfegan.plextouch.data.PlexConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.uiText
import org.johnfegan.plextouch.ui.UiText

class ProgressExchangeTest {
    private val gateway = FakeGateway()
    private val gateways = FakeGateways(gateway)
    private val store = FakeStore()
    private val player = FakePlayer()
    private val book = album("10", "Dune")
    private val scope = store.progressScope(connection)
    private var context = ProgressContext(connection, LibraryMode.AUDIOBOOK, book.id, scope, offlineOnly = false, playing = false, buffering = false, playerReady = true)
    private val exchange = ProgressExchange(gateways, store, player) { context }
    private val chapters = listOf(PlexChapterProgress("11", "One", 60_000, 1_000, 0, 0), PlexChapterProgress("12", "Two", 60_000, 30_000, 0, 0))
    private val refusal = CompareResult.Refused(uiText(R.string.progress_compare_refused))

    private fun assertRefused(reason: String, change: ProgressContext.() -> ProgressContext) = runBlocking {
        context = context.change()
        assertEquals(reason, refusal, exchange.compare(connection, book))
        assertTrue("$reason: Plex must not be read", gateways.opened.isEmpty())
    }

    @Test fun compareRefusesWhilePlaying() = assertRefused("playing") { copy(playing = true) }
    @Test fun compareRefusesWhileBuffering() = assertRefused("buffering") { copy(buffering = true) }
    @Test fun compareRefusesOffline() = assertRefused("offline") { copy(offlineOnly = true) }
    @Test fun compareRefusesForAnotherAlbum() = assertRefused("another album is open") { copy(selectedAlbumId = "99") }
    @Test fun compareRefusesOnAnotherConnection() = assertRefused("another connection") { copy(connection = PlexConnection("https://other:32400", "t")) }
    @Test fun compareRefusesInMusicMode() = assertRefused("music mode") { copy(mode = LibraryMode.MUSIC) }

    @Test fun compareReadsPlexBesideThePhoneCheckpoint() = runBlocking {
        store.historyList = listOf(progress(book, trackIndex = 1, positionMs = 5_000, trackId = "12", scope = scope))
        gateway.progress = { chapters }
        val result = exchange.compare(connection, book) as CompareResult.Ready
        assertEquals(chapters, result.comparison.chapters)
        assertEquals(PhoneCheckpoint("12", 5_000, 60_000, 1_000), result.comparison.phone)
        assertNull(result.comparison.phoneNotice)
        assertEquals("progress is never served from the browsing cache", listOf(connection to true), gateways.opened)
    }

    @Test fun compareExplainsAMissingPhoneCheckpointInsteadOfFailing() = runBlocking {
        gateway.progress = { chapters }
        val result = exchange.compare(connection, book) as CompareResult.Ready
        assertNull(result.comparison.phone)
        // No saved position at all: the server guard in `phoneCheckpoint` speaks first, as it always has.
        assertEquals(uiText(R.string.checkpoint_other_server), result.comparison.phoneNotice)
        store.historyList = listOf(progress(book, 0, 0, finished = true, trackId = "11", scope = scope))
        assertEquals(uiText(R.string.checkpoint_finished), (exchange.compare(connection, book) as CompareResult.Ready).comparison.phoneNotice)
    }

    @Test fun aContextChangeDuringTheReadDropsTheAnswer() = runBlocking {
        gateway.progress = { context = context.copy(playing = true); chapters }
        assertEquals(CompareResult.Stale, exchange.compare(connection, book))
    }

    @Test fun aNetworkFailureLeavesThePhonePositionAlone() = runBlocking {
        gateway.progress = { throw IOException("reset") }
        assertEquals(CompareResult.Failed(uiText(R.string.progress_read_failed)), exchange.compare(connection, book))
    }

    @Test fun sendRefusesOnceTheContextNoLongerMatches() = runBlocking {
        val comparison = ProgressComparison(book, connection, chapters, PhoneCheckpoint("12", 5_000, 60_000, 1_000), null)
        context = context.copy(offlineOnly = true)
        assertEquals(ExchangeResult.Failed(uiText(R.string.progress_pause_and_reconnect)), exchange.send(comparison, comparison.phone!!))
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun sendWritesThePhoneCheckpointAndSettlesTheTimeline() = runBlocking {
        store.historyList = listOf(progress(book, trackIndex = 1, positionMs = 5_000, trackId = "12", scope = scope))
        val phone = PhoneCheckpoint("12", 5_000, 60_000, 1_000)
        // Plex reports the phone's offset on the read-back once the checkpoint has landed.
        gateway.progress = { if (gateway.sent.isEmpty()) chapters else chapters.map { if (it.id == "12") it.copy(positionMs = 5_000) else it } }
        val comparison = ProgressComparison(book, connection, chapters, phone, null)
        assertEquals(ExchangeResult.Done, exchange.send(comparison, phone))
        assertEquals(listOf(phone), gateway.sent)
        assertEquals(listOf(PlexChapterProgress("12", "Two", 60_000, 5_000, 0, 0)), store.resolved)
    }

    @Test fun sendRefusesWhenPlexMovedBeforeTheWrite() = runBlocking {
        store.historyList = listOf(progress(book, trackIndex = 1, positionMs = 5_000, trackId = "12", scope = scope))
        val phone = PhoneCheckpoint("12", 5_000, 60_000, 1_000)
        gateway.progress = { chapters.map { it.copy(positionMs = 0) } }
        val result = exchange.send(ProgressComparison(book, connection, chapters, phone, null), phone)
        assertEquals(ExchangeResult.Failed(uiText(R.string.progress_changed_compare)), result)
        assertTrue(gateway.sent.isEmpty())
        assertTrue(store.resolved.isEmpty())
    }

    @Test fun resumeStartsTheChosenChapterFromThePlexOffset() = runBlocking {
        gateway.progress = { chapters }
        gateway.trackList = { listOf(track("11"), track("12")) }
        val comparison = ProgressComparison(book, connection, chapters, null, UiText.Raw("no phone position"))
        assertEquals(ExchangeResult.Done, exchange.resume(comparison, chapters[1], null))
        val started = player.plays.single()
        assertEquals(1, started.index)
        assertEquals(30_000L, started.positionMs)
        assertEquals(LibraryMode.AUDIOBOOK, started.mode)
        assertEquals(listOf(chapters[1]), store.resolved)
    }

    @Test fun resumeRefusesWhenPlexProgressMovedSinceTheComparison() = runBlocking {
        gateway.progress = { chapters.map { it.copy(positionMs = 0) } }
        val comparison = ProgressComparison(book, connection, chapters, null, null)
        assertEquals(ExchangeResult.Failed(uiText(R.string.progress_changed_close)), exchange.resume(comparison, chapters[1], null))
        assertTrue(player.plays.isEmpty())
    }
}
