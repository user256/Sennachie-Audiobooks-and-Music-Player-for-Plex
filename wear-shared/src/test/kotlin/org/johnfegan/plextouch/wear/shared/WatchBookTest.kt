package org.johnfegan.plextouch.wear.shared

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchBookTest {
    private val gib = 1L shl 30
    private val files = listOf(
        TransferFile(0, "101", "One", 600_000, 1_000, "a".repeat(64), "mp3"),
        TransferFile(1, "102", "Two", 600_000, 2_000, "b".repeat(64), "mp3"),
    )
    private val manifest = TransferManifest("transfer-0001", "42", "Book", "Author", files, 1, 90_000, 50)
    private fun requested(now: Long = 1_000) = WatchBooks.request(WatchBook(), "42", 3_000, gib, now).first

    @Test fun aRequestNeedsSpaceAndNoOtherBook() {
        val (book, refusal) = WatchBooks.request(WatchBook(), "42", 3_000, gib, 1_000)
        assertNull(refusal)
        assertEquals("42", book.heldAlbumId(1_000))
        assertEquals(TransferRefusal.ALREADY_HOLDING, WatchBooks.request(book, "43", 3_000, gib, 2_000).second)
        assertEquals(TransferRefusal.BUSY, WatchBooks.request(book, "42", 3_000, gib, 2_000).second)
        // An unanswered request expires and can be made again.
        assertNull(WatchBooks.request(book, "43", 3_000, gib, 1_000 + WatchBooks.REQUEST_TIMEOUT_MS).second)
        assertEquals(TransferRefusal.INSUFFICIENT_SPACE, WatchBooks.request(WatchBook(), "42", 3_000, WatchTransferBounds.RESERVE_BYTES, 1).second)
    }

    @Test fun onlyTheRequestedManifestIsAccepted() {
        assertFalse(WatchBooks.accept(WatchBook(), manifest, gib).second)
        assertFalse(WatchBooks.accept(WatchBooks.request(WatchBook(), "43", 3_000, gib, 1).first, manifest, gib).second)
        val (book, accepted) = WatchBooks.accept(requested(), manifest, gib)
        assertTrue(accepted)
        assertEquals(0, book.receiving)
        assertEquals(LocalPosition("102", 90_000, 50), book.position)
        // A second manifest for the same request is ignored.
        assertFalse(WatchBooks.accept(book, manifest.copy(transferId = "transfer-0002"), gib).second)
    }

    @Test fun aManifestThatNoLongerFitsIsRefused() {
        val (book, accepted) = WatchBooks.accept(requested(), manifest, 1_000)
        assertFalse(accepted)
        assertEquals(TransferRefusal.INSUFFICIENT_SPACE, book.failure)
        assertNull(book.heldAlbumId(1_000))
    }

    @Test fun filesArriveInOrderAndFailuresCanBeRetried() {
        var book = WatchBooks.accept(requested(), manifest, gib).first
        assertEquals(book, WatchBooks.verified(book, "transfer-0001", 1))
        assertEquals(book, WatchBooks.verified(book, "transfer-0002", 0))
        book = WatchBooks.verified(book, "transfer-0001", 0)
        assertEquals(1, book.receiving)
        book = WatchBooks.failed(book, "transfer-0001", 1)
        assertEquals(TransferRefusal.FAILED, book.failure)
        assertNull(book.receiving)
        assertFalse(WatchBooks.expects(book, "transfer-0001", 1))
        book = WatchBooks.retry(book)
        assertEquals(1, book.receiving)
        book = WatchBooks.verified(book, "transfer-0001", 1)
        assertTrue(book.complete)
        assertNull(book.receiving)
        assertEquals(book, WatchBooks.retry(book))
    }

    @Test fun aPhoneRefusalEndsTheRequest() {
        val book = WatchBooks.refused(requested(), "42", TransferRefusal.NOT_DOWNLOADED)
        assertEquals(TransferRefusal.NOT_DOWNLOADED, book.failure)
        assertNull(book.heldAlbumId(1_000))
        val receiving = WatchBooks.accept(requested(), manifest, gib).first
        assertEquals(TransferRefusal.NOT_DOWNLOADED, WatchBooks.refused(receiving, "", TransferRefusal.NOT_DOWNLOADED).failure)
    }

    private fun checkpoint(id: String, position: Long, transfer: String = "transfer-0001") = Checkpoint(id, transfer, "102", position, 600_000, 90_000, 100, "session-1", false)

    @Test fun playingUpdatesThePositionAndQueuesOnlyWorthwhileCheckpoints() {
        var book = WatchBooks.accept(requested(), manifest, gib).first
        book = WatchBooks.played(book, checkpoint("c1", 10_000))
        assertEquals(10_000L, book.position!!.positionMs)
        assertTrue(book.pending.isEmpty())
        book = WatchBooks.played(book, checkpoint("c2", 120_000))
        assertEquals(listOf("c2"), book.pending.map { it.id })
        assertEquals(book, WatchBooks.played(book, checkpoint("c3", 130_000, "transfer-0009")))
        book = WatchBooks.receipt(book, "c2", ReceiptOutcome.STALE)
        assertTrue(book.pending.isEmpty())
        assertEquals(ReceiptOutcome.STALE, book.notice)
    }

    @Test fun removingDropsOnlyThisBooksCheckpoints() {
        var book = WatchBooks.accept(requested(), manifest, gib).first
        book = WatchBooks.played(book, checkpoint("c1", 120_000))
        book = book.copy(pending = book.pending + checkpoint("old", 120_000, "transfer-0000"))
        val removed = WatchBooks.removed(book)
        assertNull(removed.manifest)
        assertEquals(listOf("old"), removed.pending.map { it.id })
    }

    @Test fun resumingFromThePhoneUsesItsTrackOnlyWhenTheBookHasIt() {
        val book = WatchBooks.accept(requested(), manifest, gib).first
        assertEquals(LocalPosition("101", 5_000, 9), WatchBooks.resumeFromPhone(book, HeldBookPosition("42", "101", 5_000, 8, false), 9).position)
        assertEquals(book, WatchBooks.resumeFromPhone(book, HeldBookPosition("42", "999", 5_000, 8, false), 9))
    }

    @Test fun aDamagedStoredStateIsSanitised() {
        val stored = Gson().fromJson("""{"requestedAlbumId":"42","verified":[0,5],"receiving":7}""", WatchBook::class.java)
        val clean = WatchBooks.sanitise(stored)
        assertEquals(emptyList<Int>(), clean.verified)
        assertNull(clean.receiving)
        assertEquals(emptyList<Checkpoint>(), clean.pending)
        assertEquals(WatchBook(), WatchBooks.sanitise(null))
        val full = WatchBooks.accept(requested(), manifest, gib).first
        assertEquals(full, WatchBooks.sanitise(Gson().fromJson(Gson().toJson(full), WatchBook::class.java)))
    }
}
