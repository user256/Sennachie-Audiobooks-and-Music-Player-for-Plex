package org.johnfegan.plextouch.wear

import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexTimelineState
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.data.ReadingListEntry
import org.johnfegan.plextouch.data.RemoteChapterBaseline
import org.johnfegan.plextouch.data.TimelineEvent
import org.johnfegan.plextouch.data.belongsToScope
import org.johnfegan.plextouch.player.BookTimeline
import org.johnfegan.plextouch.player.PlaybackController
import org.johnfegan.plextouch.player.PlaybackState
import org.johnfegan.plextouch.ui.chapterCount
import org.johnfegan.plextouch.ui.chapterNumber
import org.johnfegan.plextouch.ui.displayedChapter
import org.johnfegan.plextouch.ui.embeddedChapters
import org.johnfegan.plextouch.wear.shared.ChapterSkip
import org.johnfegan.plextouch.wear.shared.Checkpoint
import org.johnfegan.plextouch.wear.shared.Command
import org.johnfegan.plextouch.wear.shared.CommandOutcome
import org.johnfegan.plextouch.wear.shared.HeldBookPosition
import org.johnfegan.plextouch.wear.shared.ListEntry
import org.johnfegan.plextouch.wear.shared.NowPlaying
import org.johnfegan.plextouch.wear.shared.PhoneState
import org.johnfegan.plextouch.wear.shared.PhoneStatus
import org.johnfegan.plextouch.wear.shared.ReceiptOutcome
import org.johnfegan.plextouch.wear.shared.TransferFile
import org.johnfegan.plextouch.wear.shared.TransferRefusal
import org.johnfegan.plextouch.wear.shared.WatchCheckpoints
import org.johnfegan.plextouch.wear.shared.WatchTransferBounds
import org.johnfegan.plextouch.wear.shared.WearAction
import org.johnfegan.plextouch.widget.LivePlayback
import org.johnfegan.plextouch.widget.WidgetPrivacy
import org.johnfegan.plextouch.widget.WidgetState
import org.johnfegan.plextouch.widget.widgetModel
import org.johnfegan.plextouch.widget.DownloadedTitle

/**
 * Ticket 141, phone side: everything the watch companion is told or asks for, as pure functions of saved state (the
 * pattern of ticket 139's widget). The Android glue is [PhoneWearListenerService] and [WearUpdates].
 */

/** What the playback service holds right now, read from its player on the main thread. */
data class LiveBook(
    val album: PlexAlbum,
    val mode: LibraryMode,
    val server: String,
    val scope: String?,
    val playing: Boolean,
    val track: PlexTrack?,
    val trackIndex: Int,
    val trackCount: Int,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val bookElapsedMs: Long,
    val bookTotalMs: Long,
)

/**
 * The phone state the watch shows. The title shown is the widget's (ticket 139) choice, so the same account, server and
 * offline-only rules apply: signed out shows nothing at all, another account's books never appear, offline-only mode
 * shows only complete downloads. The reading list is this account's, bounded to [WatchTransferBounds.MAX_LIST_ENTRIES].
 */
fun watchPhoneState(
    server: String?,
    scope: String?,
    history: List<ListeningProgress>,
    offlineOnly: Boolean,
    downloads: List<DownloadStatus>,
    readingList: List<ReadingListEntry>,
    live: LiveBook?,
    heldAlbumId: String?,
    now: Long,
): PhoneState {
    if (server.isNullOrBlank() || scope.isNullOrBlank()) return PhoneState(PhoneStatus.SIGNED_OUT, capturedAt = now)
    val ready = downloads.filter { it.ready && it.record.server == server }
    val downloaded = ready.map { DownloadedTitle(it.record.server, it.record.album.id, it.coverUri) }
    val livePlayback = live?.let { LivePlayback(it.album, it.mode, it.server, it.scope, it.playing) }
    val item = widgetModel(history, livePlayback, scope, server, offlineOnly, downloaded, WidgetPrivacy()) as? WidgetState.Item
    val loaded = live?.takeIf { item != null && item.live && it.album.id == item.albumId }
    val nowPlaying = when {
        loaded != null -> {
            val chapter = displayedChapter(loaded.track, loaded.trackCount, loaded.positionMs)
            val embedded = embeddedChapters(loaded.track, loaded.trackCount).isNotEmpty()
            NowPlaying(
                albumId = loaded.album.id, title = loaded.album.title, author = loaded.album.artist,
                audiobook = loaded.mode == LibraryMode.AUDIOBOOK, playing = loaded.playing, live = true,
                chapterTitle = if (embedded) chapter?.title?.takeIf { it.isNotBlank() } else loaded.track?.title,
                chapterNumber = chapterNumber(loaded.track, loaded.trackCount, loaded.trackIndex, loaded.positionMs),
                chapterCount = chapterCount(loaded.track, loaded.trackCount),
                positionMs = loaded.positionMs.coerceAtLeast(0), durationMs = loaded.durationMs.coerceAtLeast(0), speed = loaded.speed,
                bookPermille = if (loaded.bookTotalMs > 0) (loaded.bookElapsedMs * 1000 / loaded.bookTotalMs).toInt().coerceIn(0, 1000) else item!!.progressPermille,
            )
        }
        item != null -> {
            val saved = history.firstOrNull { it.server == server && it.album.id == item.albumId && it.mode == item.mode && it.belongsToScope(scope) }
            NowPlaying(
                albumId = item.albumId, title = item.title.orEmpty(), author = item.creator.orEmpty(),
                audiobook = item.mode == LibraryMode.AUDIOBOOK, playing = false, live = false, chapterTitle = null,
                chapterNumber = (saved?.trackIndex ?: 0) + 1, chapterCount = 0,
                positionMs = saved?.positionMs ?: 0, durationMs = 0, speed = 1f, bookPermille = item.progressPermille,
            )
        }
        else -> null
    }
    val entries = readingList.take(WatchTransferBounds.MAX_LIST_ENTRIES).map { entry ->
        val local = ready.firstOrNull { it.record.album.id == entry.album.id && it.record.mode == LibraryMode.AUDIOBOOK }
        ListEntry(entry.album.id, entry.album.title, entry.album.artist, local?.bytes?.takeIf { it > 0 })
    }
    val held = heldAlbumId?.let { id ->
        history.firstOrNull { it.server == server && it.album.id == id && it.mode == LibraryMode.AUDIOBOOK && it.belongsToScope(scope) }
            ?.let { HeldBookPosition(id, it.trackId, it.positionMs, it.updatedAt, it.finished) }
    }
    return PhoneState(if (loaded != null) PhoneStatus.LOADED else PhoneStatus.IDLE, nowPlaying, entries, held, now)
}

/**
 * Which phone queue the watch's button acted on. Play/pause with nothing loaded is not handled here: the listener hands it
 * to the widget's resume action (ticket 139), so the watch resumes exactly what the widget would.
 */
fun runWatchCommand(command: Command, state: PlaybackState, positionMs: Long, player: PlaybackController, chooseSpeed: (Float) -> Unit): CommandOutcome {
    if (!state.ready || state.trackCount <= 0 || state.album == null) return CommandOutcome.NOTHING_LOADED
    when (command.action) {
        WearAction.TOGGLE -> player.toggle()
        WearAction.PLAY -> if (!state.playing) player.toggle()
        WearAction.PAUSE -> if (state.playing) player.pause()
        WearAction.BACK_30 -> player.skip(-SKIP_MS)
        WearAction.FORWARD_30 -> player.skip(SKIP_MS)
        WearAction.PREVIOUS_CHAPTER, WearAction.NEXT_CHAPTER -> {
            val markers = embeddedChapters(state.track, state.trackCount).map { it.startMs }
            val target = ChapterSkip.target(state.trackCount, state.trackIndex, positionMs, markers, command.action == WearAction.NEXT_CHAPTER)
                ?: return CommandOutcome.DONE
            if (target.index == state.trackIndex) player.seek(target.positionMs) else player.chapter(target.index, target.positionMs)
        }
        WearAction.SPEED -> {
            if (state.mode != LibraryMode.AUDIOBOOK) return CommandOutcome.NOT_AUDIOBOOK
            chooseSpeed(command.speed ?: return CommandOutcome.FAILED)
        }
    }
    return CommandOutcome.DONE
}

private const val SKIP_MS = 30_000L

/**
 * The phone's record of a book it sent to the watch. It is the only link from the watch's opaque transfer id back to an
 * account scope (a token fingerprint, never a token), a server and an album; it never leaves the phone.
 */
data class WatchTransferRecord(
    val transferId: String,
    val scope: String,
    val server: String,
    val album: PlexAlbum,
    val files: List<TransferFile>,
    val createdAt: Long,
) {
    val albumId: String get() = album.id
}

/** A file the phone is about to send: its manifest entry (hash still empty) and where the download lives. */
data class PlannedFile(val entry: TransferFile, val localUri: String)

sealed interface TransferPlan {
    data class Refused(val reason: TransferRefusal) : TransferPlan
    data class Ready(val album: PlexAlbum, val files: List<PlannedFile>, val resumeIndex: Int, val resumePositionMs: Long) : TransferPlan
}

/**
 * The deliberate offline scope (ticket 141): a book may go to the watch only when it is this account's reading-list title
 * or its most recent audiobook, the phone holds a complete, verified download of it from the signed-in server, and it fits
 * [WatchTransferBounds]. The watch resumes from the phone's saved position (unfinished, same account), else the start.
 */
fun planWatchTransfer(
    albumId: String,
    server: String?,
    scope: String?,
    readingList: List<ReadingListEntry>,
    history: List<ListeningProgress>,
    downloads: List<DownloadStatus>,
): TransferPlan {
    if (server.isNullOrBlank() || scope.isNullOrBlank()) return TransferPlan.Refused(TransferRefusal.SIGNED_OUT)
    val ours = history.filter { it.server == server && it.mode == LibraryMode.AUDIOBOOK && it.belongsToScope(scope) }
    val current = ours.maxByOrNull { it.updatedAt }?.album?.id
    if (readingList.none { it.album.id == albumId } && current != albumId) return TransferPlan.Refused(TransferRefusal.NOT_ON_LIST)
    val download = downloads.firstOrNull { it.ready && it.record.server == server && it.record.album.id == albumId && it.record.mode == LibraryMode.AUDIOBOOK }
        ?: return TransferPlan.Refused(TransferRefusal.NOT_DOWNLOADED)
    val tracks = download.record.tracks
    WatchTransferBounds.phoneCheck(tracks.map { it.file.verifiedBytes.takeIf { bytes -> bytes > 0 } })?.let { return TransferPlan.Refused(it) }
    val uris = download.tracks.map { it.localUri }
    if (uris.size != tracks.size || uris.any { it == null || !it.startsWith("file:") }) return TransferPlan.Refused(TransferRefusal.NOT_DOWNLOADED)
    val single = embeddedChapters(tracks.first().track, tracks.size)
    val files = tracks.mapIndexed { index, item ->
        val extension = item.track.container?.takeIf { it.matches(TransferFile.EXTENSION) } ?: "audio"
        PlannedFile(TransferFile(index, item.track.id, item.track.title, item.track.durationMs.coerceAtLeast(0), item.file.verifiedBytes, "", extension,
            if (index == 0) single.map { it.startMs } else emptyList()), uris[index]!!)
    }
    val saved = ours.firstOrNull { it.album.id == albumId }?.takeUnless { it.finished }
    val resumeIndex = saved?.trackId?.let { id -> files.indexOfFirst { it.entry.trackId == id } }?.takeIf { it >= 0 } ?: 0
    val duration = files[resumeIndex].entry.durationMs
    val resumePosition = if (saved == null || saved.trackId != files[resumeIndex].entry.trackId) 0 else saved.positionMs.coerceIn(0, (duration - 1).coerceAtLeast(0))
    return TransferPlan.Ready(download.record.album.copy(localThumb = null), files, resumeIndex, resumePosition)
}

/** What a watch checkpoint does on the phone: the receipt for the watch, and what (if anything) to save. */
data class WatchSync(val outcome: ReceiptOutcome, val event: TimelineEvent? = null, val progress: ListeningProgress? = null)

/**
 * Maps a watch checkpoint onto the phone's own rules (tickets 106/113/114). The watch never writes Plex progress: an
 * accepted position becomes an ordinary [TimelineEvent] for the same conflict-safe outbox the playback service feeds
 * (same scope/server/album/track identity, the stored baseline, the conflict blocks), plus a local history save.
 *
 * - Unknown transfers, another account or server: refused.
 * - Positions inside the first [WatchCheckpoints.MIN_POSITION_MS] or at the file's end: ignored; nothing is ever completed.
 * - The phone listened to this book after the watch's capture, or has it loaded in its player now (resuming that queue
 *   would save the player's older offset over the watch's): the phone keeps its position, and the watch is told so,
 *   rather than either side overwriting the other.
 * - A rewind on the watch is a real position and is accepted.
 * - The file's length is the phone's own record, not the watch's claim.
 */
fun watchCheckpointSync(
    checkpoint: Checkpoint,
    record: WatchTransferRecord?,
    activeScope: String?,
    activeServer: String?,
    history: List<ListeningProgress>,
    offlineOnly: Boolean,
    baseline: RemoteChapterBaseline?,
    phoneLoadedAlbumId: String?,
): WatchSync {
    if (record == null || record.transferId != checkpoint.transferId) return WatchSync(ReceiptOutcome.UNKNOWN_TRANSFER)
    if (activeScope != record.scope || activeServer != record.server || record.scope.length != 64) return WatchSync(ReceiptOutcome.SCOPE_CHANGED)
    val index = record.files.indexOfFirst { it.trackId == checkpoint.trackId }
    if (index < 0) return WatchSync(ReceiptOutcome.IGNORED)
    val duration = record.files[index].durationMs
    if (!WatchCheckpoints.worthSending(checkpoint.positionMs, duration)) return WatchSync(ReceiptOutcome.IGNORED)
    if (phoneLoadedAlbumId == record.albumId) return WatchSync(ReceiptOutcome.STALE)
    val saved = history.firstOrNull { it.server == record.server && it.album.id == record.albumId && it.mode == LibraryMode.AUDIOBOOK && it.belongsToScope(record.scope) }
    if (saved != null && saved.updatedAt > checkpoint.capturedAt) return WatchSync(ReceiptOutcome.STALE)
    val event = TimelineEvent(
        id = "watch-${checkpoint.id}", scope = record.scope, server = record.server, albumId = record.albumId, trackId = checkpoint.trackId,
        originPositionMs = checkpoint.originPositionMs.coerceIn(0, duration - 1), positionMs = checkpoint.positionMs, durationMs = duration,
        state = if (checkpoint.playing) PlexTimelineState.PLAYING else PlexTimelineState.PAUSED, capturedAt = checkpoint.capturedAt,
        playbackSessionId = "watch-${checkpoint.sessionId}", offline = offlineOnly, baseline = baseline,
    )
    val timeline = BookTimeline(record.files.map { it.durationMs })
    val progress = ListeningProgress(
        album = record.album, mode = LibraryMode.AUDIOBOOK, server = record.server, trackIndex = index, positionMs = checkpoint.positionMs,
        elapsedMs = timeline.bookOffset(index, checkpoint.positionMs), durationMs = timeline.totalMs, updatedAt = checkpoint.capturedAt,
        finished = false, trackId = checkpoint.trackId, accountScope = record.scope,
    )
    return WatchSync(ReceiptOutcome.QUEUED, event, progress)
}
