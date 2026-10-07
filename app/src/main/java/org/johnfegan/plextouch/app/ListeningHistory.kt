package org.johnfegan.plextouch.app

import androidx.compose.runtime.Immutable
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.BookHistoryEntry
import org.johnfegan.plextouch.data.ListeningHistoryStore
import org.johnfegan.plextouch.data.ListeningStats
import org.johnfegan.plextouch.data.bookHistory
import org.johnfegan.plextouch.data.listeningStats
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText
import java.time.Instant
import java.time.ZoneId

/** Ticket 138: what the history screen shows for one account on one server, read from the phone alone. */
@Immutable
data class ListeningHistoryView(val server: String, val scope: String?, val books: List<BookHistoryEntry>, val stats: ListeningStats)

sealed interface HistoryChange {
    data class Done(val view: ListeningHistoryView) : HistoryChange
    data class Refused(val message: UiText) : HistoryChange
}

/**
 * Listening history and stats, built from local data only, so it works offline and never contacts Plex. Reset and clear
 * change this phone's saved progress and log for the given account and server; neither enqueues a Plex timeline write.
 */
class ListeningHistory(
    private val store: ListeningHistoryStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    fun load(server: String, scope: String?): ListeningHistoryView {
        val today = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
        return ListeningHistoryView(server, scope, bookHistory(store.history(), server, scope), listeningStats(store.listeningLog(), server, scope, today))
    }

    /** A book loaded in the player would be saved straight back by the playback service, so it cannot be reset. */
    fun reset(server: String, scope: String?, albumId: String, loadedAlbumId: String?): HistoryChange {
        if (albumId == loadedAlbumId) return HistoryChange.Refused(uiText(R.string.history_reset_playing))
        store.resetBookProgress(server, scope, albumId, now())
        return HistoryChange.Done(load(server, scope))
    }

    fun clear(server: String, scope: String?): HistoryChange {
        store.clearListeningHistory(server, scope)
        return HistoryChange.Done(load(server, scope))
    }
}
