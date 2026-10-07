package org.johnfegan.plextouch.wear.shared

/**
 * The watch's one offline book (ticket 141): the request the listener consented to, the manifest the phone answered with,
 * which files have been received and independently verified, the local position, and checkpoints still to reach the phone.
 */
data class WatchBook(
    val requestedAlbumId: String? = null,
    val requestedAt: Long = 0,
    val manifest: TransferManifest? = null,
    val verified: List<Int> = emptyList(),
    /** The file index requested from the phone and not yet received; null when idle, failed or complete. */
    val receiving: Int? = null,
    val failure: TransferRefusal? = null,
    val position: LocalPosition? = null,
    val pending: List<Checkpoint> = emptyList(),
    /** The last receipt that did not sync, so the watch can say why (cleared by the next accepted one). */
    val notice: ReceiptOutcome? = null,
) {
    val complete: Boolean get() = manifest != null && verified.size == manifest.files.size
    val nextMissing: Int? get() = manifest?.files?.indices?.firstOrNull { it !in verified }
    /** A book on (or on its way to) the watch; a request counts only until it times out or fails. */
    fun heldAlbumId(now: Long): String? = manifest?.albumId
        ?: requestedAlbumId?.takeIf { failure == null && now - requestedAt < WatchBooks.REQUEST_TIMEOUT_MS }
}

/** Pure transitions for [WatchBook]; the watch app persists the result of each. */
object WatchBooks {
    /** A request the phone has not answered in this time may be made again. */
    const val REQUEST_TIMEOUT_MS = 120_000L

    /** Gson leaves missing lists null in a stored or damaged state. */
    @Suppress("SENSELESS_COMPARISON")
    fun sanitise(book: WatchBook?): WatchBook {
        if (book == null) return WatchBook()
        val manifest = book.manifest?.takeIf { it.wellFormed() }
        return book.copy(
            manifest = manifest,
            verified = if (manifest == null || book.verified == null) emptyList() else book.verified.filter { it in manifest.files.indices }.distinct(),
            receiving = book.receiving?.takeIf { manifest != null && it in manifest.files.indices },
            pending = (book.pending ?: emptyList()).filter { it != null && it.wellFormed() },
            position = book.position?.takeIf { it.trackId != null },
        )
    }

    /** The listener confirmed the copy; refused (with the reason) when the watch already holds a book or lacks space. */
    fun request(book: WatchBook, albumId: String, totalBytes: Long, freeBytes: Long, now: Long): Pair<WatchBook, TransferRefusal?> {
        WatchTransferBounds.watchCheck(totalBytes, freeBytes, book.heldAlbumId(now), albumId)?.let { return book to it }
        return WatchBook(requestedAlbumId = albumId, requestedAt = now, pending = book.pending) to null
    }

    /**
     * Only a manifest for the book the listener asked for (no unsolicited downloads), within bounds and fitting the free
     * space, is accepted; the first file is then requested. The resume point is the phone's position when it was sent.
     */
    fun accept(book: WatchBook, manifest: TransferManifest, freeBytes: Long): Pair<WatchBook, Boolean> {
        if (book.manifest != null || book.requestedAlbumId != manifest.albumId || !manifest.wellFormed()) return book to false
        WatchTransferBounds.watchCheck(manifest.totalBytes, freeBytes, null, manifest.albumId)?.let {
            return book.copy(requestedAlbumId = null, failure = it) to false
        }
        val start = manifest.files[manifest.resumeIndex]
        return book.copy(manifest = manifest, verified = emptyList(), receiving = 0, failure = null,
            position = LocalPosition(start.trackId, manifest.resumePositionMs, manifest.createdAt)) to true
    }

    fun refused(book: WatchBook, albumId: String, reason: TransferRefusal): WatchBook = when {
        book.manifest == null && book.requestedAlbumId == albumId -> book.copy(requestedAlbumId = null, failure = reason)
        book.manifest != null && (book.manifest.albumId == albumId || albumId.isEmpty()) && !book.complete -> book.copy(receiving = null, failure = reason)
        else -> book
    }

    fun expects(book: WatchBook, transferId: String, index: Int): Boolean =
        book.manifest?.transferId == transferId && book.receiving == index

    /** A file arrived and matched its size and hash; the next missing one is requested, or the book is complete. */
    fun verified(book: WatchBook, transferId: String, index: Int): WatchBook {
        if (!expects(book, transferId, index)) return book
        val done = (book.verified + index).distinct()
        val next = book.manifest!!.files.indices.firstOrNull { it !in done }
        return book.copy(verified = done, receiving = next, failure = null)
    }

    /** A file was interrupted or failed verification; nothing partial is kept, and the listener can retry. */
    fun failed(book: WatchBook, transferId: String, index: Int): WatchBook =
        if (!expects(book, transferId, index)) book else book.copy(receiving = null, failure = TransferRefusal.FAILED)

    fun retry(book: WatchBook): WatchBook =
        if (book.manifest == null || book.complete) book else book.copy(receiving = book.nextMissing, failure = null)

    /** Removing the book deletes its files and drops its unsent checkpoints; checkpoints of older books stay queued. */
    fun removed(book: WatchBook): WatchBook =
        WatchBook(pending = book.manifest?.let { WatchCheckpoints.forget(book.pending, it.transferId) } ?: book.pending)

    /** A local position: always kept for resuming on the watch, and queued for the phone when worth sending. */
    fun played(book: WatchBook, checkpoint: Checkpoint): WatchBook {
        if (book.manifest?.transferId != checkpoint.transferId) return book
        return book.copy(position = LocalPosition(checkpoint.trackId, checkpoint.positionMs, checkpoint.capturedAt),
            pending = WatchCheckpoints.record(book.pending, checkpoint))
    }

    fun receipt(book: WatchBook, id: String, outcome: ReceiptOutcome): WatchBook {
        if (book.pending.none { it.id == id }) return book
        return book.copy(pending = WatchCheckpoints.receipt(book.pending, id), notice = if (outcome == ReceiptOutcome.QUEUED) null else outcome)
    }

    /** The listener chose the phone's newer position over the watch's own. */
    fun resumeFromPhone(book: WatchBook, phone: HeldBookPosition, now: Long): WatchBook {
        val manifest = book.manifest ?: return book
        val trackId = phone.trackId?.takeIf { id -> manifest.files.any { it.trackId == id } } ?: return book
        return book.copy(position = LocalPosition(trackId, phone.positionMs.coerceAtLeast(0), now))
    }
}
