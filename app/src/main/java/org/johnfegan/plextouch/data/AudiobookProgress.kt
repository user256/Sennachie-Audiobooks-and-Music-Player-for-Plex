package org.johnfegan.plextouch.data

import java.security.MessageDigest
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.checkText
import org.johnfegan.plextouch.ui.requireText
import org.johnfegan.plextouch.ui.uiText

/** A token fingerprint, never a token. Old unscoped history remains readable but cannot be exported. */
fun PlexConnection.progressScope(accountIdentity: String = token): String = MessageDigest.getInstance("SHA-256")
    .digest("${serverUrl.trimEnd('/')}\u0000$accountIdentity".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

data class PlexChapterProgress(
    val id: String,
    val title: String,
    val durationMs: Long,
    val positionMs: Long,
    val playCount: Int,
    val lastViewedAt: Long,
) {
    val resumable: Boolean get() = durationMs > 0 && positionMs in 1 until durationMs
}

data class PhoneCheckpoint(val trackId: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long)

fun phoneCheckpoint(progress: ListeningProgress?, accountScope: String, chapters: List<PlexChapterProgress>): PhoneCheckpoint {
    requireText(progress != null && progress.mode == LibraryMode.AUDIOBOOK) { uiText(R.string.checkpoint_listen_first) }
    requireText(progress.accountScope == accountScope) { uiText(R.string.checkpoint_old_scope) }
    requireText(!progress.finished) { uiText(R.string.checkpoint_finished) }
    val chapter = chapters.singleOrNull { it.id == progress.trackId }
    requireText(chapter != null) { uiText(R.string.checkpoint_chapter_missing) }
    requireText(chapter.durationMs > 0 && progress.positionMs in 0 until chapter.durationMs) { uiText(R.string.checkpoint_invalid_position) }
    return PhoneCheckpoint(chapter.id, progress.positionMs, chapter.durationMs, progress.updatedAt)
}

fun phoneCheckpoint(progress: ListeningProgress?, connection: PlexConnection, chapters: List<PlexChapterProgress>): PhoneCheckpoint =
    phoneCheckpoint(progress, connection, connection.progressScope(), chapters)

fun phoneCheckpoint(progress: ListeningProgress?, connection: PlexConnection, accountScope: String, chapters: List<PlexChapterProgress>): PhoneCheckpoint {
    requireText(progress?.server == connection.serverUrl) { uiText(R.string.checkpoint_other_server) }
    return phoneCheckpoint(progress, accountScope, chapters)
}

/** Explicit exchange only: no background writes, speculative completion or max-offset conflict policy. */
class AudiobookProgressExchange(
    private val read: suspend () -> List<PlexChapterProgress>,
    private val send: suspend (PhoneCheckpoint) -> Unit,
) {
    suspend fun export(expected: List<PlexChapterProgress>, phone: PhoneCheckpoint, stillCurrent: () -> Boolean): PlexChapterProgress {
        checkText(read() == expected) { uiText(R.string.progress_changed_compare) }
        checkText(stillCurrent()) { uiText(R.string.progress_phone_changed) }
        send(phone)
        val stored = read().singleOrNull { it.id == phone.trackId }
        checkText(stored?.positionMs == phone.positionMs) { uiText(R.string.progress_not_confirmed) }
        return stored
    }
}

enum class PlexTimelineState { PLAYING, PAUSED, BUFFERING, STOPPED }
enum class TimelineBlock { CONFLICT, AUTHORITY_CHANGED, AUTHENTICATION, MISSING_CHAPTER }

/** No token, stream URL, title or account identifier is persisted in a timeline event. */
data class TimelineEvent(
    val id: String,
    val scope: String,
    val server: String,
    val albumId: String,
    val trackId: String,
    val originPositionMs: Long,
    val positionMs: Long,
    val durationMs: Long,
    val state: PlexTimelineState,
    val capturedAt: Long,
    val playbackSessionId: String,
    val offline: Boolean,
    val baseline: RemoteChapterBaseline? = null,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val blocked: TimelineBlock? = null,
)

data class RemoteChapterBaseline(val trackId: String, val positionMs: Long, val playCount: Int, val lastViewedAt: Long) {
    companion object {
        fun from(progress: PlexChapterProgress) = RemoteChapterBaseline(progress.id, progress.positionMs, progress.playCount, progress.lastViewedAt)
    }
}

data class ProgressSyncConflict(
    val scope: String,
    val server: String,
    val albumId: String,
    val trackId: String,
    val phonePositionMs: Long,
    val plexPositionMs: Long?,
    val reason: TimelineBlock,
    val createdAt: Long,
)

data class TimelineSyncState(
    val events: List<TimelineEvent> = emptyList(),
    val baselines: List<RemoteChapterBaselineEntry> = emptyList(),
    val conflicts: List<ProgressSyncConflict> = emptyList(),
)

data class RemoteChapterBaselineEntry(
    val scope: String,
    val server: String,
    val albumId: String,
    val baseline: RemoteChapterBaseline,
)

/** Pure persistent queue policy. Events are coalesced per chapter, never by largest offset. */
object TimelineOutbox {
    private fun same(event: TimelineEvent, other: TimelineEvent) =
        event.scope == other.scope && event.server == other.server && event.albumId == other.albumId && event.trackId == other.trackId
    private fun same(entry: RemoteChapterBaselineEntry, event: TimelineEvent) =
        entry.scope == event.scope && entry.server == event.server && entry.albumId == event.albumId && entry.baseline.trackId == event.trackId

    fun enqueue(state: TimelineSyncState, event: TimelineEvent): TimelineSyncState {
        require(event.scope.length == 64 && event.trackId.matches(Regex("[0-9]+")))
        require(event.positionMs in 0 until event.durationMs)
        val existing = state.events.firstOrNull { same(it, event) }
        if (existing?.blocked != null) return state
        val baseline = existing?.baseline ?: state.baselines.firstOrNull { same(it, event) }?.baseline ?: event.baseline
        return state.copy(events = (state.events.filterNot { same(it, event) } + event.copy(baseline = baseline))
            .sortedBy { it.capturedAt }.takeLast(MAX_EVENTS))
    }

    fun next(state: TimelineSyncState, now: Long): TimelineEvent? =
        state.events.firstOrNull { it.blocked == null && it.nextAttemptAt <= now }

    fun baseline(state: TimelineSyncState, scope: String, server: String, albumId: String, trackId: String): RemoteChapterBaseline? =
        state.baselines.firstOrNull { it.scope == scope && it.server == server && it.albumId == albumId && it.baseline.trackId == trackId }?.baseline

    fun acknowledged(state: TimelineSyncState, event: TimelineEvent, remote: PlexChapterProgress): TimelineSyncState {
        val baseline = RemoteChapterBaseline.from(remote)
        val entry = RemoteChapterBaselineEntry(event.scope, event.server, event.albumId, baseline)
        return state.copy(
            events = state.events.filterNot { it.id == event.id }.map { pending ->
                if (same(pending, event)) pending.copy(baseline = baseline) else pending
            },
            baselines = (state.baselines.filterNot { same(it, event) } + entry).takeLast(MAX_BASELINES),
            conflicts = state.conflicts.filterNot { it.scope == event.scope && it.server == event.server && it.albumId == event.albumId && it.trackId == event.trackId },
        )
    }

    fun retry(state: TimelineSyncState, event: TimelineEvent, now: Long): TimelineSyncState {
        val attempts = (event.attempts + 1).coerceAtMost(MAX_ATTEMPTS)
        val delay = retryDelay(event)
        return state.copy(events = state.events.map { if (it.id == event.id) it.copy(attempts = attempts, nextAttemptAt = now + delay) else it })
    }

    fun retryDelay(event: TimelineEvent): Long =
        (5_000L shl event.attempts.coerceAtMost(6)).coerceAtMost(300_000L)

    fun block(state: TimelineSyncState, event: TimelineEvent, reason: TimelineBlock, remote: PlexChapterProgress?, now: Long): TimelineSyncState {
        val conflict = ProgressSyncConflict(event.scope, event.server, event.albumId, event.trackId, event.positionMs, remote?.positionMs, reason, now)
        return state.copy(
            events = state.events.map { if (it.id == event.id) it.copy(blocked = reason) else it },
            conflicts = (state.conflicts.filterNot { it.scope == event.scope && it.server == event.server && it.albumId == event.albumId && it.trackId == event.trackId } + conflict).takeLast(MAX_CONFLICTS),
        )
    }

    fun resolve(state: TimelineSyncState, scope: String, server: String, albumId: String, remote: PlexChapterProgress): TimelineSyncState {
        val event = TimelineEvent("resolved", scope, server, albumId, remote.id, remote.positionMs, remote.positionMs,
            remote.durationMs.coerceAtLeast(1), PlexTimelineState.PAUSED, 0, "resolved", false)
        return acknowledged(state.copy(events = state.events.filterNot { it.scope == scope && it.server == server && it.albumId == albumId && it.trackId == remote.id }), event, remote)
    }

    fun accountChanged(state: TimelineSyncState, activeScope: String, now: Long): TimelineSyncState = state.copy(
        events = state.events.map { if (it.scope != activeScope && it.blocked == null) it.copy(blocked = TimelineBlock.AUTHORITY_CHANGED) else it },
        conflicts = (state.conflicts + state.events.filter { it.scope != activeScope && it.blocked == null }.map {
            ProgressSyncConflict(it.scope, it.server, it.albumId, it.trackId, it.positionMs, null, TimelineBlock.AUTHORITY_CHANGED, now)
        }).distinctBy { "${it.scope}:${it.server}:${it.albumId}:${it.trackId}" }.takeLast(MAX_CONFLICTS),
    )

    const val MAX_EVENTS = 40
    const val MAX_BASELINES = 100
    const val MAX_CONFLICTS = 20
    const val MAX_ATTEMPTS = 8
}

sealed interface TimelineDelivery {
    data class Delivered(val remote: PlexChapterProgress) : TimelineDelivery
    data class Conflict(val remote: PlexChapterProgress?, val reason: TimelineBlock) : TimelineDelivery
    data object Retry : TimelineDelivery
    data object AuthenticationFailure : TimelineDelivery
}

/** Reads immediately before posting and read-backs after it; Plex offers no compare-and-set. */
class AudiobookTimelineSync(
    private val read: suspend (String) -> List<PlexChapterProgress>,
    private val send: suspend (TimelineEvent) -> Unit,
) {
    suspend fun deliver(event: TimelineEvent): TimelineDelivery {
        return try {
        val current = read(event.albumId).singleOrNull { it.id == event.trackId }
            ?: return TimelineDelivery.Conflict(null, TimelineBlock.MISSING_CHAPTER)
        if (current.positionMs == event.positionMs) return TimelineDelivery.Delivered(current)
        val expected = event.baseline
        val safe = if (expected != null) RemoteChapterBaseline.from(current) == expected
        else current.positionMs == event.originPositionMs && current.playCount == 0
        if (!safe) return TimelineDelivery.Conflict(current, TimelineBlock.CONFLICT)
        send(event)
        val confirmed = read(event.albumId).singleOrNull { it.id == event.trackId }
            ?: return TimelineDelivery.Conflict(null, TimelineBlock.MISSING_CHAPTER)
        if (confirmed.positionMs == event.positionMs) TimelineDelivery.Delivered(confirmed) else TimelineDelivery.Retry
    } catch (error: PlexHttpException) {
        if (error.authentication) TimelineDelivery.AuthenticationFailure else TimelineDelivery.Retry
    } catch (_: Exception) { TimelineDelivery.Retry }
    }
}
