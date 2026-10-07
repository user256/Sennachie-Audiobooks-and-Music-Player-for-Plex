package org.johnfegan.plextouch.wear.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearProtocolTest {
    private val sha = "a".repeat(64)
    private fun file(index: Int, bytes: Long = 1_000, markers: List<Long> = emptyList()) =
        TransferFile(index, "${100 + index}", "Part $index", 600_000, bytes, sha, "m4b", markers)
    private fun manifest(files: List<TransferFile> = listOf(file(0), file(1))) =
        TransferManifest("transfer-0001", "42", "Book", "Author", files, 1, 90_000, 5_000)

    private fun roundTrip(message: WearMessage): WearMessage = (WearCodec.decode(WearCodec.encode(message)) as Decoded.Message).message

    @Test fun everyMessageTypeRoundTrips() {
        val messages = listOf(
            Command(WearAction.FORWARD_30), Command(WearAction.SPEED, 1.25f), CommandResult(WearAction.TOGGLE, CommandOutcome.NOTHING_LOADED),
            RequestState("42"), RequestState(null), RequestTransfer("42"), TransferRefused("42", TransferRefusal.TOO_LARGE), manifest(), RequestFile("transfer-0001", 1),
            Checkpoint("c1", "transfer-0001", "101", 95_000, 600_000, 90_000, 7_000, "session-1", false),
            CheckpointReceipt("c1", ReceiptOutcome.STALE), TransferRemoved("transfer-0001"),
            PhoneState(PhoneStatus.LOADED, NowPlaying("42", "Book", "Author", true, true, true, null, 3, 12, 1_000, 2_000, 1.5f, 250),
                listOf(ListEntry("42", "Book", "Author", 12_345), ListEntry("43", "Other", "", null)), HeldBookPosition("42", "101", 5, 6, false), 9),
        )
        messages.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test fun unknownTypesAreToleratedAndNewerVersionsReported() {
        assertEquals(Decoded.Unknown("hologram"), WearCodec.decode("""{"v":1,"type":"hologram","body":{}}""".toByteArray()))
        assertEquals(Decoded.Unsupported(2), WearCodec.decode("""{"v":2,"type":"command","body":{"action":"PLAY"}}""".toByteArray()))
        // New optional fields from a newer build of the same version are ignored.
        assertEquals(Decoded.Message(Command(WearAction.PLAY)), WearCodec.decode("""{"v":1,"type":"command","body":{"action":"PLAY","haptics":true}}""".toByteArray()))
    }

    @Test fun malformedInputIsRejectedNotThrown() {
        listOf("", "not json", "[]", """{"type":"command"}""", """{"v":1,"type":"command"}""", """{"v":1,"type":"command","body":{}}""",
            """{"v":1,"type":"command","body":{"action":"LAUNCH"}}""", """{"v":1,"type":"command","body":{"action":"SPEED"}}""",
            """{"v":0,"type":"command","body":{"action":"PLAY"}}""", """{"v":1,"type":"requestTransfer","body":{"albumId":" "}}""",
        ).forEach { assertEquals(it, Decoded.Malformed, WearCodec.decode(it.toByteArray())) }
        assertEquals(Decoded.Malformed, WearCodec.decode(null))
        assertEquals(Decoded.Malformed, WearCodec.decode(ByteArray(WearCodec.MAX_BYTES + 1) { 'a'.code.toByte() }))
    }

    @Test fun manifestsOutsideTheBoundsAreMalformed() {
        assertTrue(manifest().wellFormed())
        assertFalse(manifest(emptyList()).wellFormed())
        assertFalse(manifest(listOf(file(0), file(2))).wellFormed())
        assertFalse(manifest(listOf(file(0, bytes = WatchTransferBounds.MAX_BOOK_BYTES), file(1))).wellFormed())
        assertFalse(manifest(listOf(file(0).copy(sha256 = "xyz"), file(1))).wellFormed())
        assertFalse(manifest(listOf(file(0).copy(extension = "../x"), file(1))).wellFormed())
        assertFalse(manifest().copy(transferId = "../../etc").wellFormed())
        assertFalse(manifest().copy(resumeIndex = 2).wellFormed())
        // A manifest whose files list is missing decodes as malformed rather than crashing on null.
        assertEquals(Decoded.Malformed, WearCodec.decode("""{"v":1,"type":"manifest","body":{"transferId":"transfer-0001","albumId":"1","title":"t","author":"a","resumeIndex":0}}""".toByteArray()))
    }

    @Test fun missingListsReadAsEmpty() {
        val decoded = WearCodec.decode("""{"v":1,"type":"state","body":{"status":"IDLE"}}""".toByteArray()) as Decoded.Message
        assertEquals(emptyList<ListEntry>(), (decoded.message as PhoneState).entries)
        val noMarkers = WearCodec.decode(WearCodec.encode(manifest()).toString(Charsets.UTF_8).replace(""","chapterStartsMs":[]""", "").toByteArray()) as Decoded.Message
        assertEquals(emptyList<Long>(), (noMarkers.message as TransferManifest).files[0].markers)
    }

    @Test fun aLoadedStateNeedsWhatIsPlaying() {
        assertEquals(Decoded.Malformed, WearCodec.decode("""{"v":1,"type":"state","body":{"status":"LOADED"}}""".toByteArray()))
    }

    @Test fun filePathsRoundTripAndRejectTraversal() {
        assertEquals("transfer-0001" to 3, WearPaths.parseFilePath(WearPaths.filePath("transfer-0001", 3)))
        assertNull(WearPaths.parseFilePath("/plextouch/v1/file/../../x/1"))
        assertNull(WearPaths.parseFilePath("/plextouch/v1/file/transfer-0001/-1"))
        assertNull(WearPaths.parseFilePath("/plextouch/v1/file/transfer-0001/${WatchTransferBounds.MAX_FILES}"))
        assertNull(WearPaths.parseFilePath("/plextouch/v1/file/transfer-0001"))
        assertNull(WearPaths.parseFilePath("/other/transfer-0001/1"))
        assertNull(WearPaths.parseFilePath(null))
    }

    @Test fun theProtocolHasNoPlaceForCredentials() {
        val fields = listOf(Command::class.java, RequestState::class.java, TransferManifest::class.java, TransferFile::class.java,
            Checkpoint::class.java, PhoneState::class.java, NowPlaying::class.java, ListEntry::class.java, HeldBookPosition::class.java)
            .flatMap { type -> type.declaredFields.map { it.name.lowercase() } }
        listOf("token", "server", "url", "scope", "uri", "password").forEach { secret ->
            assertTrue("field named like $secret", fields.none { it.contains(secret) })
        }
    }
}
