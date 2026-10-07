package org.johnfegan.plextouch.wear.shared

import java.io.InputStream
import java.security.MessageDigest

/** A queue entry and an offset inside it. */
data class SkipTarget(val index: Int, val positionMs: Long)

/**
 * Chapter skip for the watch buttons, on the phone's queue and on the watch's own copy alike. A book stored as one file
 * with embedded markers skips between [markers] (start offsets inside the current file); any other book skips between
 * files. "Previous" first returns to the start of the current chapter, as a CD player does, unless that start is less
 * than [RESTART_THRESHOLD_MS] behind.
 */
object ChapterSkip {
    const val RESTART_THRESHOLD_MS = 3_000L

    /** Null when there is nowhere to go (forward from the last chapter). */
    fun target(itemCount: Int, index: Int, positionMs: Long, markers: List<Long>, forward: Boolean): SkipTarget? {
        if (itemCount <= 0 || index !in 0 until itemCount) return null
        val position = positionMs.coerceAtLeast(0)
        val starts = markers.filter { it >= 0 }.distinct().sorted()
        val current = starts.indexOfLast { it <= position }
        if (forward) {
            starts.getOrNull(current + 1)?.let { return SkipTarget(index, it) }
            return if (index + 1 < itemCount) SkipTarget(index + 1, 0) else null
        }
        if (starts.isNotEmpty()) {
            if (current >= 0 && position - starts[current] > RESTART_THRESHOLD_MS) return SkipTarget(index, starts[current])
            if (current >= 1) return SkipTarget(index, starts[current - 1])
        } else if (position > RESTART_THRESHOLD_MS) return SkipTarget(index, 0)
        return if (index > 0) SkipTarget(index - 1, 0) else SkipTarget(index, 0)
    }
}

/**
 * The deliberate offline scope: one book on the watch at a time, of bounded size, never the whole library. The phone
 * checks what it may send; the watch checks what it may hold before it asks and again when the manifest arrives.
 */
object WatchTransferBounds {
    /** One book of at most 1 GiB: roughly 30 hours at 64 kbit/s, enough for nearly every audiobook. */
    const val MAX_BOOK_BYTES = 1L shl 30
    const val MAX_FILES = 300
    const val MAX_MARKERS = 500
    /** Free space the watch keeps for the system and other apps after the book is stored. */
    const val RESERVE_BYTES = 512L shl 20
    /** Reading-list titles sent to the watch: a glanceable list, not a library. */
    const val MAX_LIST_ENTRIES = 10

    /** Phone side: every file must be a verified download (`null` or 0 is an incomplete one). */
    fun phoneCheck(fileBytes: List<Long?>): TransferRefusal? = when {
        fileBytes.isEmpty() || fileBytes.any { it == null || it <= 0 } -> TransferRefusal.NOT_DOWNLOADED
        fileBytes.size > MAX_FILES -> TransferRefusal.TOO_MANY_FILES
        fileBytes.sumOf { it ?: 0 } > MAX_BOOK_BYTES -> TransferRefusal.TOO_LARGE
        else -> null
    }

    /** Watch side: [heldAlbumId] is the book already on the watch (or being received), which must be removed first. */
    fun watchCheck(totalBytes: Long, freeBytes: Long, heldAlbumId: String?, albumId: String): TransferRefusal? = when {
        heldAlbumId != null && heldAlbumId != albumId -> TransferRefusal.ALREADY_HOLDING
        heldAlbumId != null -> TransferRefusal.BUSY
        totalBytes <= 0 -> TransferRefusal.NOT_DOWNLOADED
        totalBytes > MAX_BOOK_BYTES -> TransferRefusal.TOO_LARGE
        freeBytes - totalBytes < RESERVE_BYTES -> TransferRefusal.INSUFFICIENT_SPACE
        else -> null
    }
}

/** The watch's latest local position in its book, and when it was taken. */
data class LocalPosition(val trackId: String, val positionMs: Long, val at: Long)

enum class ResumeChoice { WATCH, ASK }

/**
 * The watch's queue of checkpoints still to reach the phone. One entry per file is kept, the most recently captured
 * (never the largest offset: a deliberate rewind on the watch is a real position). The queue is bounded; the oldest
 * entries go first, and each one is dropped once the phone sends a receipt for it.
 */
object WatchCheckpoints {
    const val MAX_PENDING = 20
    /** The phone ignores positions this early in a file anyway (as its own service does); the watch does not queue them. */
    const val MIN_POSITION_MS = 30_000L
    /** Positions closer than this are treated as the same place when comparing phone and watch. */
    const val SAME_PLACE_MS = 5_000L

    /** True when a position is worth sending: inside the file, past the start, and never at its end (no completion). */
    fun worthSending(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= MIN_POSITION_MS && positionMs < durationMs

    fun record(pending: List<Checkpoint>, checkpoint: Checkpoint): List<Checkpoint> {
        if (!worthSending(checkpoint.positionMs, checkpoint.durationMs)) return pending
        val previous = pending.firstOrNull { it.transferId == checkpoint.transferId && it.trackId == checkpoint.trackId }
        if (previous != null && previous.capturedAt > checkpoint.capturedAt) return pending
        return (pending.filterNot { it.transferId == checkpoint.transferId && it.trackId == checkpoint.trackId } + checkpoint)
            .sortedBy { it.capturedAt }.takeLast(MAX_PENDING)
    }

    fun receipt(pending: List<Checkpoint>, id: String): List<Checkpoint> = pending.filterNot { it.id == id }

    /** Removing the book drops everything still queued for it. */
    fun forget(pending: List<Checkpoint>, transferId: String): List<Checkpoint> = pending.filterNot { it.transferId == transferId }

    /**
     * Whether the watch may resume from its own position or must ask. It never silently jumps to the phone's position,
     * and never silently ignores a phone that has listened since: when the phone's save is newer and somewhere else,
     * the listener chooses.
     */
    fun resumeChoice(watch: LocalPosition, phone: HeldBookPosition?): ResumeChoice {
        if (phone == null || phone.finished || phone.updatedAt <= watch.at) return ResumeChoice.WATCH
        val samePlace = phone.trackId == watch.trackId && kotlin.math.abs(phone.positionMs - watch.positionMs) < SAME_PLACE_MS
        return if (samePlace) ResumeChoice.WATCH else ResumeChoice.ASK
    }
}

/** Independent file verification: both sides hash the bytes themselves; the watch keeps a file only on an exact match. */
object FileDigest {
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
