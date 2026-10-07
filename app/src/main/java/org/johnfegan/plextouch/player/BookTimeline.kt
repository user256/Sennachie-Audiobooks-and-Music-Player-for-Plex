package org.johnfegan.plextouch.player

import androidx.compose.runtime.Immutable
import androidx.media3.common.Player

/** A spot in a multi-file book: queue entry `trackIndex`, `offsetMs` into that file. */
@Immutable
data class BookPoint(val trackIndex: Int, val offsetMs: Long)

/**
 * A book's files laid end to end, so a position can be read as one book-level offset and back.
 *
 * A file whose duration is unknown or zero (`<= 0`, which also covers `C.TIME_UNSET`) takes no room on the book line:
 * a book offset never maps inside it, but it stays in the queue and plays as normal. Skips that cross into such a file
 * land at its start, because there is no length to carry the remainder into.
 */
@Immutable
class BookTimeline(durationsMs: List<Long>) {
    private val durations: List<Long> = durationsMs.map { if (it > 0) it else 0 }
    private val starts: List<Long> = durations.runningFold(0L) { start, duration -> start + duration }.dropLast(1)

    val size: Int get() = durations.size
    val totalMs: Long = durations.sum()

    fun known(index: Int): Boolean = durations.getOrElse(index) { 0 } > 0
    fun durationOf(index: Int): Long = durations.getOrElse(index) { 0 }
    fun startOf(index: Int): Long = starts.getOrElse(index.coerceAtMost(size - 1).coerceAtLeast(0)) { 0 }

    /** The book offset of a file position; an offset past a known file end is held at that end. */
    fun bookOffset(index: Int, offsetMs: Long): Long {
        if (index !in durations.indices) return 0
        val offset = offsetMs.coerceAtLeast(0)
        return starts[index] + if (known(index)) offset.coerceAtMost(durations[index]) else 0
    }

    /** The file and offset under a book offset, skipping files without a known length; null when no file has one. */
    fun locate(bookMs: Long): BookPoint? {
        val last = durations.indices.lastOrNull(::known) ?: return null
        val target = bookMs.coerceIn(0, totalMs)
        if (target >= totalMs) return BookPoint(last, durations[last])
        val index = durations.indices.first { known(it) && target < starts[it] + durations[it] }
        return BookPoint(index, target - starts[index])
    }

    /**
     * Where a relative skip from (`index`, `offsetMs`) lands. Inside the file it is a plain offset; past either end it carries
     * the remainder into the neighbouring file. Backwards it stops at the start of the book; forwards it stops at the end of
     * the last file, or with `repeat` wraps round to the first. `currentDurationMs` is the player's live length for the
     * current file, preferred to the catalogue figure when known. With no known length for the current file the skip stays
     * inside it, exactly as [skipTarget] does.
     */
    fun skip(index: Int, offsetMs: Long, deltaMs: Long, currentDurationMs: Long, repeat: Boolean): BookPoint {
        if (index !in durations.indices) return BookPoint(index.coerceAtLeast(0), skipTarget(offsetMs, deltaMs, currentDurationMs))
        val current = if (currentDurationMs > 0) currentDurationMs else durations[index]
        val target = offsetMs.coerceAtLeast(0) + deltaMs
        if (current <= 0) return BookPoint(index, target.coerceAtLeast(0))
        if (target in 0 until current) return BookPoint(index, target)
        return if (target < 0) back(index, -target) else forward(index, target - current, current, repeat)
    }

    private fun back(from: Int, remainingMs: Long): BookPoint {
        var remaining = remainingMs
        var index = from - 1
        while (index >= 0) {
            val duration = durations[index]
            if (duration <= 0) return BookPoint(index, 0)
            if (remaining <= duration) return BookPoint(index, duration - remaining)
            remaining -= duration
            index--
        }
        return BookPoint(0, 0)
    }

    private fun forward(from: Int, overflowMs: Long, currentMs: Long, repeat: Boolean): BookPoint {
        var remaining = overflowMs
        var index = from + 1
        // At most one lap of the queue, so a book of unknown lengths cannot spin.
        repeat(size) {
            if (index > durations.lastIndex) {
                if (!repeat) return end(from, currentMs)
                index = 0
            }
            val duration = durations[index]
            if (duration <= 0 || remaining < duration) return BookPoint(index, if (duration <= 0) 0 else remaining)
            remaining -= duration
            index++
        }
        return end(from, currentMs)
    }

    /** Without repeat, a forward skip past the last file sits at its end, which lets the player finish the book. */
    private fun end(from: Int, currentMs: Long): BookPoint {
        val last = durations.lastIndex
        return if (last == from) BookPoint(from, currentMs) else BookPoint(last, durations[last])
    }

    companion object {
        fun of(tracks: List<org.johnfegan.plextouch.data.PlexTrack>) = BookTimeline(tracks.map { it.durationMs })
    }
}

/**
 * Where saved progress should resume in `tracks`. The saved `trackId` wins over the saved index when the list has been
 * reordered or grown (a track id missing from the list falls back to the saved index); an offset saved at (or past) the end of a known-length file moves to the start of the next one, so
 * a checkpoint taken on a file boundary resumes in the next file rather than replaying the tail of the last.
 */
fun resumePoint(tracks: List<org.johnfegan.plextouch.data.PlexTrack>, trackIndex: Int, positionMs: Long, trackId: String?): BookPoint {
    if (tracks.isEmpty()) return BookPoint(0, 0)
    val saved = trackIndex.coerceIn(0, tracks.lastIndex)
    // A track listed twice (a playlist) keeps its saved slot; only a moved track is looked up by id.
    val byId = trackId?.takeIf { tracks[saved].id != it }?.let { id -> tracks.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    val index = byId ?: saved
    val offset = positionMs.coerceAtLeast(0)
    val duration = tracks[index].durationMs
    if (duration > 0 && offset >= duration && index < tracks.lastIndex) return BookPoint(index + 1, 0)
    return BookPoint(index, offset)
}

/** The book-level offset the service stores as `elapsedMs`: the earlier files plus this one, never past its known end. */
fun bookElapsedMs(beforeMs: Long, positionMs: Long, trackDurationMs: Long): Long {
    val position = positionMs.coerceAtLeast(0)
    return beforeMs.coerceAtLeast(0) + if (trackDurationMs > 0) position.coerceAtMost(trackDurationMs) else position
}

/**
 * Saved progress is marked finished only when the player has ended on the last file with repeat off. Moving from one
 * file to the next (an auto transition, a skip or a chapter tap) never reports completion, and repeat-all never ends.
 */
fun finishedBook(playbackState: Int, index: Int, itemCount: Int, repeatMode: Int): Boolean =
    playbackState == Player.STATE_ENDED && repeatMode == Player.REPEAT_MODE_OFF && itemCount > 0 && index == itemCount - 1
