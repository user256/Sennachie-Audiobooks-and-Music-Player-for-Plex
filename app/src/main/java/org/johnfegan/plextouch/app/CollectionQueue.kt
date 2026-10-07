package org.johnfegan.plextouch.app

import androidx.compose.runtime.Immutable
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.belongsToScope
import org.johnfegan.plextouch.player.PlaybackState

/**
 * Ticket 131: "play the unfinished books in a collection".
 *
 * Design: the player gets one book at a time. Each book is started on its own (through [AlbumPlayback], so it resumes at
 * its saved chapter and offset with its own progress identity and its own per-book timeline from ticket 132), and when
 * that book finishes the next unfinished book of the run is started. One Media3 queue spanning several books is
 * deliberately not built: the service's book bar, 30-second skips across files, "finished" detection and saved
 * progress all treat the queue as one book, so a multi-book queue would merge them. Editing the order of the run is
 * left to ticket 108 (editable queue); the order here is the collection's own, which is deterministic.
 */
@Immutable
data class CollectionRun(
    val server: String,
    val scope: String?,
    val groupKey: String,
    /** The run's books in collection order, unfinished when the run started. */
    val books: List<PlexAlbum>,
    val currentId: String,
)

/** Album ids this account has finished on this server (first saved entry per album wins, as in `progressByAlbum`). */
fun finishedBookIds(history: List<ListeningProgress>, server: String, scope: String?): Set<String> {
    val seen = HashSet<String>()
    val finished = HashSet<String>()
    for (progress in history) {
        if (progress.server != server || progress.mode != LibraryMode.AUDIOBOOK || !progress.belongsToScope(scope)) continue
        if (seen.add(progress.album.id) && progress.finished) finished += progress.album.id
    }
    return finished
}

/**
 * The books of a collection still to be listened to, in the collection's order: playlists and repeats are dropped, a book
 * this account finished on this server is skipped, and offline-only mode keeps only books fully downloaded from this
 * server (a streamed book would be refused mid-run).
 */
fun unfinishedBooks(
    books: List<PlexAlbum>, history: List<ListeningProgress>, downloads: List<DownloadStatus>,
    server: String, scope: String?, offlineOnly: Boolean,
): List<PlexAlbum> {
    val finished = finishedBookIds(history, server, scope)
    val local = if (offlineOnly) downloads.filter { it.ready && it.record.server == server }.mapTo(HashSet()) { it.record.album.id } else emptySet()
    return books.asSequence().filterNot { it.id.startsWith("playlist:") }.distinctBy { it.id }
        .filterNot { it.id in finished }
        .filter { !offlineOnly || it.id in local }
        .toList()
}

/** Starts a run at the first unfinished book; null when every book is finished (or, offline, none is downloaded). */
fun startCollectionRun(groupKey: String, unfinished: List<PlexAlbum>, server: String, scope: String?): CollectionRun? =
    unfinished.firstOrNull()?.let { CollectionRun(server, scope, groupKey, unfinished, it.id) }

sealed interface RunStep {
    /** Nothing to do yet (still playing, paused mid-book, or nothing loaded). */
    data class Keep(val run: CollectionRun) : RunStep
    /** The current book finished: start `album` next. */
    data class Next(val run: CollectionRun, val album: PlexAlbum) : RunStep
    /** The run is over: its last book finished, another title was played, or the account or server changed. */
    data object End : RunStep
}

/**
 * What a run does after a player or history change. The current book counts as finished only once the playback service
 * has saved it finished and the player has stopped; the next book is the first later one in the run that is still not
 * finished (a book finished elsewhere meanwhile is skipped). Starting another book of the same run moves the run there;
 * playing anything outside it, or a different server or account, ends it.
 */
fun collectionRunStep(run: CollectionRun, playback: PlaybackState, history: List<ListeningProgress>, server: String?, scope: String?): RunStep {
    if (server != run.server || scope != run.scope) return RunStep.End
    val playing = playback.album ?: return RunStep.Keep(run)
    if (playback.mode != LibraryMode.AUDIOBOOK || run.books.none { it.id == playing.id }) return RunStep.End
    val finished = finishedBookIds(history, run.server, run.scope)
    // The player can still report the previous (finished) book for a moment after the run moved on; that never advances twice.
    if (playing.id != run.currentId) return RunStep.Keep(if (playback.playing || playing.id !in finished) run.copy(currentId = playing.id) else run)
    if (playback.playing || playing.id !in finished) return RunStep.Keep(run)
    val next = run.books.dropWhile { it.id != run.currentId }.drop(1).firstOrNull { it.id !in finished } ?: return RunStep.End
    return RunStep.Next(run.copy(currentId = next.id), next)
}
