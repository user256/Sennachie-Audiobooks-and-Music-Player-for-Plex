package org.johnfegan.plextouch.data

import androidx.compose.runtime.Immutable

/** Ticket 138: how a book stands in this phone's history. */
enum class BookHistoryStatus { IN_PROGRESS, COMPLETED, RESET }

/** One row of the history screen. `completedAt` may be set on an in-progress book: it was finished once and is being replayed. */
@Immutable
data class BookHistoryEntry(
    val album: PlexAlbum,
    val status: BookHistoryStatus,
    val elapsedMs: Long,
    val durationMs: Long,
    val fraction: Float,
    val updatedAt: Long,
    val completedAt: Long?,
    val resetAt: Long?,
)

/** Audiobooks this account has listened to on this server, most recently played first, with their status and dates. */
fun bookHistory(history: List<ListeningProgress>, server: String, scope: String?): List<BookHistoryEntry> =
    history.asSequence()
        .filter { it.server == server && it.mode == LibraryMode.AUDIOBOOK && it.belongsToScope(scope) && (it.wasListened() || it.resetAt != null) }
        .distinctBy { it.album.id }
        .sortedByDescending { maxOf(it.updatedAt, it.resetAt ?: 0) }
        .map { progress ->
            val status = when {
                progress.resetAt != null -> BookHistoryStatus.RESET
                progress.finished -> BookHistoryStatus.COMPLETED
                else -> BookHistoryStatus.IN_PROGRESS
            }
            // History saved before ticket 138 has no completion date; its last save is the closest honest answer.
            val completedAt = progress.completedAt ?: progress.updatedAt.takeIf { progress.finished }
            BookHistoryEntry(progress.album, status, progress.elapsedMs, progress.durationMs, if (progress.finished) 1f else progress.fraction, progress.updatedAt, completedAt, progress.resetAt)
        }.toList()

/**
 * Starts one book over on this phone: its saved position and finished flag go, a reset date is recorded, and everything
 * else (other books, other accounts, the Plex timeline outbox) is untouched. Nothing is sent to Plex.
 */
fun resetBookProgress(history: List<ListeningProgress>, server: String, scope: String?, albumId: String, at: Long): List<ListeningProgress> =
    history.map { progress ->
        if (progress.server == server && progress.mode == LibraryMode.AUDIOBOOK && progress.belongsToScope(scope) && progress.album.id == albumId) {
            progress.copy(trackIndex = 0, positionMs = 0, elapsedMs = 0, finished = false, trackId = null, hasListened = true, resetAt = at)
        } else progress
    }

/** Clear-history for one account on one server: its saved progress in every mode goes; other accounts and servers keep theirs. */
fun clearListeningProgress(history: List<ListeningProgress>, server: String, scope: String?): List<ListeningProgress> =
    history.filterNot { it.server == server && it.belongsToScope(scope) }
