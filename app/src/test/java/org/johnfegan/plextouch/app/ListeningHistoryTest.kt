package org.johnfegan.plextouch.app

import org.johnfegan.plextouch.data.BookHistoryStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningDay
import org.johnfegan.plextouch.data.ListeningSample
import org.johnfegan.plextouch.data.recordListening
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ListeningHistoryTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = LocalDateTime.parse("2026-10-07T21:00:00").atZone(zone).toInstant().toEpochMilli()
    private val server = connection.serverUrl

    private fun store(): FakeStore = FakeStore().apply {
        historyList = listOf(progress(album("1"), 0, 30_000, scope = "A"), progress(album("2"), 1, 120_000, finished = true, scope = "A"), progress(album("3"), 0, 10_000, scope = "B"))
        log = listOf("A", "B").fold(emptyList<ListeningDay>()) { acc, scope -> recordListening(acc, ListeningSample(scope, server, LibraryMode.AUDIOBOOK, "1", now - 10_000, now, zone)) }
    }

    @Test fun loadsBooksAndStatsForTheCurrentAccountFromThePhoneAlone() {
        val view = ListeningHistory(store(), { now }, { zone }).load(server, "A")
        assertEquals(listOf("1", "2"), view.books.map { it.album.id })
        assertEquals(listOf(BookHistoryStatus.IN_PROGRESS, BookHistoryStatus.COMPLETED), view.books.map { it.status })
        assertEquals(10_000, view.stats.todayMs)
    }

    @Test fun resetMarksOneBookAndNeverTouchesPlexOrTheTimeline() {
        val store = store()
        val result = ListeningHistory(store, { now }, { zone }).reset(server, "A", "2", loadedAlbumId = null) as HistoryChange.Done
        assertEquals(BookHistoryStatus.RESET, result.view.books.single { it.album.id == "2" }.status)
        assertEquals(now, result.view.books.single { it.album.id == "2" }.resetAt)
        assertTrue(store.resolved.isEmpty())
        assertTrue(store.removed.isEmpty())
        assertEquals(10_000L, store.historyList.single { it.album.id == "3" }.positionMs)
    }

    @Test fun aBookLoadedInThePlayerCannotBeReset() {
        val store = store()
        val before = store.historyList
        val result = ListeningHistory(store, { now }, { zone }).reset(server, "A", "1", loadedAlbumId = "1")
        assertEquals(UiText.Res(R.string.history_reset_playing), (result as HistoryChange.Refused).message)
        assertEquals(before, store.historyList)
    }

    @Test fun clearRemovesOnlyThisAccountsProgressAndLog() {
        val store = store()
        val result = ListeningHistory(store, { now }, { zone }).clear(server, "A") as HistoryChange.Done
        assertTrue(result.view.books.isEmpty())
        assertEquals(0, result.view.stats.todayMs)
        assertEquals(listOf("3"), store.historyList.map { it.album.id })
        assertEquals(listOf("B"), store.log.map { it.scope })
        assertEquals(10_000, ListeningHistory(store, { now }, { zone }).load(server, "B").stats.todayMs)
        assertTrue(store.resolved.isEmpty())
    }
}
