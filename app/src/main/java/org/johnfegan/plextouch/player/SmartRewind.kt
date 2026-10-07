package org.johnfegan.plextouch.player

import androidx.media3.common.Player
import com.google.gson.Gson
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.data.chapterAt
import kotlin.math.abs

/** Why an audiobook stopped. Only the first three can earn a smart rewind (ticket 134). */
enum class PauseReason {
    /** Pause from the app, notification, lock screen, headset button or the sleep timer. */
    USER,
    /** Another app took audio focus: a call, navigation prompt or another player (permanent or transient). */
    FOCUS_LOSS,
    /** The headphones or Bluetooth route went away and Android asked us to stop ("becoming noisy"). */
    NOISY,
    /** End of the queue, a remote device or anything else; never rewinds. */
    OTHER,
}

/**
 * The last qualifying pause, persisted so that a recreated service (or a resume after the process died) still knows when
 * and why the book stopped. [elapsedRealtimeMs] is exact within one boot; across a reboot ([bootCount] differs, or the
 * elapsed clock went backwards) the wall clock is used instead. [replayUntilMs] is where the listener had got to before an
 * earlier smart rewind they have not yet heard past again; until they do, no new rewind is added on top.
 */
data class PausedAt(
    val scope: String,
    val server: String,
    val albumId: String,
    val trackId: String,
    val positionMs: Long,
    val reason: PauseReason,
    val elapsedRealtimeMs: Long,
    val wallClockMs: Long,
    val bootCount: Int,
    val replayUntilMs: Long? = null,
)

/** Where the last smart rewind started from, so an immediate second pause does not stack another rewind on top. */
data class RewindMark(val trackId: String, val fromMs: Long)

/** How Media3's play-when-ready change reason maps onto a pause that may earn a rewind. */
fun pauseReason(playWhenReadyChangeReason: Int): PauseReason = when (playWhenReadyChangeReason) {
    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> PauseReason.USER
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> PauseReason.FOCUS_LOSS
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> PauseReason.NOISY
    else -> PauseReason.OTHER
}

/** The pure smart-rewind rules (ticket 134). */
object RewindPolicy {
    /** The Settings choices in seconds; 0 is Off, the default. */
    val CHOICES_SECONDS: List<Int> = listOf(0, 5, 10, 15, 30, 60)
    const val MAX_SECONDS = 60
    /** Shorter pauses (a quick tap, a Bluetooth blip, a transient prompt) resume exactly where they stopped. */
    const val MIN_PAUSE_MS = 30_000L
    /** A resume within this distance of the paused position is "where it stopped"; anything else was a seek or chapter jump. */
    const val SAME_POSITION_MS = 1_500L

    /**
     * The position to resume from, or null to resume in place. It rewinds by the [settingSeconds] amount (at most 60 s)
     * after a qualifying pause of at least [MIN_PAUSE_MS], never before [chapterStartMs] (the current file's start, or the
     * embedded chapter's start in a single-file book), and not while the listener is still replaying an earlier rewind.
     */
    fun rewindTo(reason: PauseReason, pausedForMs: Long, settingSeconds: Int, positionMs: Long, chapterStartMs: Long, replayUntilMs: Long? = null): Long? {
        if (reason == PauseReason.OTHER) return null
        val amount = settingSeconds.coerceIn(0, MAX_SECONDS) * 1_000L
        if (amount == 0L || pausedForMs < MIN_PAUSE_MS || positionMs <= 0) return null
        if (replayUntilMs != null && positionMs < replayUntilMs) return null
        val target = (positionMs - amount).coerceAtLeast(chapterStartMs.coerceIn(0, positionMs))
        return target.takeIf { it < positionMs }
    }

    fun pausedFor(pause: PausedAt, nowElapsedMs: Long, nowWallMs: Long, bootCount: Int): Long =
        if (bootCount >= 0 && bootCount == pause.bootCount && nowElapsedMs >= pause.elapsedRealtimeMs) nowElapsedMs - pause.elapsedRealtimeMs
        else (nowWallMs - pause.wallClockMs).coerceAtLeast(0)

    /**
     * The resume decision for the item now loaded: only the same account, server, book and file, resumed where it was
     * paused (so a seek, skip or chapter selection made while paused never rewinds) and only for audiobooks.
     */
    fun resumeTarget(
        pause: PausedAt?, audiobook: Boolean, scope: String, server: String, albumId: String, trackId: String, positionMs: Long,
        chapterStartMs: Long, settingSeconds: Int, nowElapsedMs: Long, nowWallMs: Long, bootCount: Int,
    ): Long? {
        if (pause == null || !audiobook) return null
        if (pause.scope != scope || pause.server != server || pause.albumId != albumId || pause.trackId != trackId) return null
        if (abs(positionMs - pause.positionMs) > SAME_POSITION_MS) return null
        return rewindTo(pause.reason, pausedFor(pause, nowElapsedMs, nowWallMs, bootCount), settingSeconds, positionMs, chapterStartMs, pause.replayUntilMs)
    }

    /** The replay limit a new pause inherits from the last rewind: only in the same file and before its starting point. */
    fun replayUntil(mark: RewindMark?, trackId: String, positionMs: Long): Long? =
        mark?.takeIf { it.trackId == trackId && positionMs < it.fromMs }?.fromMs

    /** The start a rewind may not cross: the embedded chapter's start in a single-file book, otherwise the file's start. */
    fun chapterStart(track: PlexTrack?, positionMs: Long): Long = track?.let { chapterAt(it, positionMs)?.startMs } ?: 0L

    /** Rewind seconds to announce once: a rewind the session reported since [previous], applied within the last few seconds. */
    fun notice(previous: PlaybackState, current: PlaybackState, nowElapsedMs: Long): Int? {
        if (current.rewindAt == 0L || current.rewindAt == previous.rewindAt || current.rewoundMs <= 0) return null
        if (nowElapsedMs - current.rewindAt !in 0..NOTICE_WINDOW_MS) return null
        return ((current.rewoundMs + 500) / 1_000).toInt().coerceAtLeast(1)
    }

    /** A controller that connects later (a recreated screen) does not announce an old rewind again. */
    const val NOTICE_WINDOW_MS = 5_000L
}

/** The persisted pause behind the store's lock: one small JSON value, read on service start and written on the writer. */
class PauseRecordStore(private val load: () -> String?, private val write: (String?) -> Unit, private val lock: Any = Any()) {
    private val gson = Gson()

    fun read(): PausedAt? = synchronized(lock) {
        runCatching { gson.fromJson(load() ?: return@synchronized null, PausedAt::class.java) }.getOrNull()
            ?.takeIf { runCatching { it.scope.isNotEmpty() && it.trackId.isNotEmpty() && it.reason.name.isNotEmpty() }.getOrDefault(false) }
    }

    fun save(pause: PausedAt?) = synchronized(lock) { write(pause?.let(gson::toJson)) }
}
