package org.johnfegan.plextouch.wear.shared

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearRulesTest {
    @Test fun multiFileBooksSkipBetweenFiles() {
        assertEquals(SkipTarget(2, 0), ChapterSkip.target(5, 1, 40_000, emptyList(), forward = true))
        assertNull(ChapterSkip.target(5, 4, 40_000, emptyList(), forward = true))
        // Back restarts the current file first, then goes to the previous one.
        assertEquals(SkipTarget(1, 0), ChapterSkip.target(5, 1, 40_000, emptyList(), forward = false))
        assertEquals(SkipTarget(0, 0), ChapterSkip.target(5, 1, 2_000, emptyList(), forward = false))
        assertEquals(SkipTarget(0, 0), ChapterSkip.target(5, 0, 1_000, emptyList(), forward = false))
    }

    @Test fun singleFileBooksSkipBetweenMarkers() {
        val markers = listOf(60_000L, 0L, 120_000L)
        assertEquals(SkipTarget(0, 60_000), ChapterSkip.target(1, 0, 10_000, markers, forward = true))
        assertEquals(SkipTarget(0, 120_000), ChapterSkip.target(1, 0, 60_000, markers, forward = true))
        assertNull(ChapterSkip.target(1, 0, 130_000, markers, forward = true))
        assertEquals(SkipTarget(0, 60_000), ChapterSkip.target(1, 0, 90_000, markers, forward = false))
        assertEquals(SkipTarget(0, 0), ChapterSkip.target(1, 0, 61_000, markers, forward = false))
        assertEquals(SkipTarget(0, 0), ChapterSkip.target(1, 0, 1_000, markers, forward = false))
    }

    @Test fun skipRefusesAnEmptyQueue() {
        assertNull(ChapterSkip.target(0, 0, 0, emptyList(), forward = true))
        assertNull(ChapterSkip.target(2, 5, 0, emptyList(), forward = false))
    }

    @Test fun phoneSendsOnlyCompleteBoundedDownloads() {
        assertNull(WatchTransferBounds.phoneCheck(listOf(10, 20)))
        assertEquals(TransferRefusal.NOT_DOWNLOADED, WatchTransferBounds.phoneCheck(emptyList()))
        assertEquals(TransferRefusal.NOT_DOWNLOADED, WatchTransferBounds.phoneCheck(listOf(10, null)))
        assertEquals(TransferRefusal.NOT_DOWNLOADED, WatchTransferBounds.phoneCheck(listOf(10, 0)))
        assertEquals(TransferRefusal.TOO_LARGE, WatchTransferBounds.phoneCheck(listOf(WatchTransferBounds.MAX_BOOK_BYTES, 1)))
        assertEquals(TransferRefusal.TOO_MANY_FILES, WatchTransferBounds.phoneCheck(List(WatchTransferBounds.MAX_FILES + 1) { 1L }))
    }

    @Test fun watchHoldsOneBookAndKeepsAReserve() {
        val gib = 1L shl 30
        assertNull(WatchTransferBounds.watchCheck(100, gib, null, "1"))
        assertEquals(TransferRefusal.ALREADY_HOLDING, WatchTransferBounds.watchCheck(100, gib, "2", "1"))
        assertEquals(TransferRefusal.BUSY, WatchTransferBounds.watchCheck(100, gib, "1", "1"))
        assertEquals(TransferRefusal.INSUFFICIENT_SPACE, WatchTransferBounds.watchCheck(gib / 2 + 1, gib, null, "1"))
        assertEquals(TransferRefusal.TOO_LARGE, WatchTransferBounds.watchCheck(gib + 1, 8 * gib, null, "1"))
        assertEquals(TransferRefusal.NOT_DOWNLOADED, WatchTransferBounds.watchCheck(0, gib, null, "1"))
    }

    private fun checkpoint(id: String, track: String, position: Long, at: Long, transfer: String = "transfer-0001") =
        Checkpoint(id, transfer, track, position, 600_000, 0, at, "session-1", true)

    @Test fun checkpointsKeepTheLatestCaptureNotTheLargestOffset() {
        var pending = WatchCheckpoints.record(emptyList(), checkpoint("a", "1", 300_000, 10))
        // A rewind recorded later replaces the further position: the watch never inflates progress.
        pending = WatchCheckpoints.record(pending, checkpoint("b", "1", 100_000, 20))
        assertEquals(listOf("b"), pending.map { it.id })
        // An older capture arriving late does not replace a newer one.
        pending = WatchCheckpoints.record(pending, checkpoint("c", "1", 400_000, 15))
        assertEquals(listOf("b"), pending.map { it.id })
        pending = WatchCheckpoints.record(pending, checkpoint("d", "2", 100_000, 30))
        assertEquals(listOf("b", "d"), pending.map { it.id })
        assertEquals(listOf("d"), WatchCheckpoints.receipt(pending, "b").map { it.id })
        assertEquals(emptyList<Checkpoint>(), WatchCheckpoints.forget(pending, "transfer-0001"))
    }

    @Test fun checkpointsNeverQueueTheStartOrTheEnd() {
        assertEquals(emptyList<Checkpoint>(), WatchCheckpoints.record(emptyList(), checkpoint("a", "1", 10_000, 1)))
        assertEquals(emptyList<Checkpoint>(), WatchCheckpoints.record(emptyList(), checkpoint("a", "1", 600_000, 1)))
        assertEquals(emptyList<Checkpoint>(), WatchCheckpoints.record(emptyList(), checkpoint("a", "1", 100_000, 1).copy(durationMs = 0)))
    }

    @Test fun theCheckpointQueueIsBounded() {
        val pending = (1..50).fold(emptyList<Checkpoint>()) { list, n -> WatchCheckpoints.record(list, checkpoint("c$n", "$n", 100_000, n.toLong())) }
        assertEquals(WatchCheckpoints.MAX_PENDING, pending.size)
        assertEquals("c50", pending.last().id)
    }

    @Test fun resumeAsksWhenThePhoneMovedOnSince() {
        val watch = LocalPosition("1", 100_000, 1_000)
        assertEquals(ResumeChoice.WATCH, WatchCheckpoints.resumeChoice(watch, null))
        assertEquals(ResumeChoice.WATCH, WatchCheckpoints.resumeChoice(watch, HeldBookPosition("b", "1", 500_000, 900, false)))
        assertEquals(ResumeChoice.ASK, WatchCheckpoints.resumeChoice(watch, HeldBookPosition("b", "1", 500_000, 2_000, false)))
        assertEquals(ResumeChoice.ASK, WatchCheckpoints.resumeChoice(watch, HeldBookPosition("b", "2", 100_000, 2_000, false)))
        assertEquals(ResumeChoice.WATCH, WatchCheckpoints.resumeChoice(watch, HeldBookPosition("b", "1", 102_000, 2_000, false)))
        assertEquals(ResumeChoice.WATCH, WatchCheckpoints.resumeChoice(watch, HeldBookPosition("b", "1", 500_000, 2_000, true)))
    }

    @Test fun digestsAreStableHex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FileDigest.sha256(ByteArrayInputStream(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileDigest.sha256(ByteArrayInputStream("abc".toByteArray())))
    }
}
