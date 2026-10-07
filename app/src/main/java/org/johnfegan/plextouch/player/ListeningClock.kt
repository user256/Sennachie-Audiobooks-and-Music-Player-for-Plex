package org.johnfegan.plextouch.player

import org.johnfegan.plextouch.data.ListeningLog

/**
 * Measures playing wall-time for the listening log (ticket 138) from a monotonic clock (`SystemClock.elapsedRealtime` in
 * the service), never from the playback position, so seeks, skips and speed changes neither add nor remove time. Each
 * [take] returns the time since the previous one while playing, capped at `maxDeltaMs` so a stalled heartbeat or a
 * suspended process cannot add hours at once.
 */
class ListeningClock(private val maxDeltaMs: Long = ListeningLog.MAX_SAMPLE_MS) {
    private var since: Long? = null

    /** The playing time since the last take (zero when the clock was not running), then runs on while `playing` or stops. */
    fun take(now: Long, playing: Boolean): Long {
        val from = since
        since = if (playing) now else null
        return if (from == null) 0 else (now - from).coerceIn(0, maxDeltaMs)
    }
}
