package org.johnfegan.plextouch.wear

import org.johnfegan.plextouch.app.FakePlayer
import org.johnfegan.plextouch.data.DownloadAlbum
import org.johnfegan.plextouch.data.DownloadFile
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.DownloadTrack
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexChapter
import org.johnfegan.plextouch.data.PlexTimelineState
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.data.ReadingListEntry
import org.johnfegan.plextouch.data.RemoteChapterBaseline
import org.johnfegan.plextouch.data.TimelineBlock
import org.johnfegan.plextouch.data.TimelineOutbox
import org.johnfegan.plextouch.data.TimelineSyncState
import org.johnfegan.plextouch.player.PlaybackState
import org.johnfegan.plextouch.wear.shared.Checkpoint
import org.johnfegan.plextouch.wear.shared.Command
import org.johnfegan.plextouch.wear.shared.CommandOutcome
import org.johnfegan.plextouch.wear.shared.PhoneStatus
import org.johnfegan.plextouch.wear.shared.ReceiptOutcome
import org.johnfegan.plextouch.wear.shared.TransferManifest
import org.johnfegan.plextouch.wear.shared.TransferRefusal
import org.johnfegan.plextouch.wear.shared.WatchTransferBounds
import org.johnfegan.plextouch.wear.shared.WearAction
import org.johnfegan.plextouch.wear.shared.WearCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchModelTest {
    private val server = "https://plex.example:32400"
    private val scope = "a".repeat(64)
    private val otherScope = "b".repeat(64)
    private val book = PlexAlbum("7", "The Long Road", "A. Narrator", 2020, 2, thumb = "/library/metadata/7/thumb", localThumb = "file:///cover")
    private val other = PlexAlbum("8", "Second Book", "B. Writer", 2021, 1)
    private fun track(id: String, durationMs: Long = 600_000, chapters: List<PlexChapter> = emptyList()) =
        PlexTrack(id, "Part $id", "A. Narrator", "The Long Road", durationMs, "/library/parts/$id/file.m4b?X-Plex-Token=secret", "m4b", chapterMarkers = chapters)
    private val tracks = listOf(track("101"), track("102"))

    private fun saved(album: PlexAlbum = book, trackIndex: Int = 1, trackId: String? = "102", position: Long = 200_000, updatedAt: Long = 1_000,
                      accountScope: String? = scope, finished: Boolean = false) =
        ListeningProgress(album, LibraryMode.AUDIOBOOK, server, trackIndex, position, 600_000 + position, 1_200_000, updatedAt, finished = finished, trackId = trackId, accountScope = accountScope)

    private fun download(album: PlexAlbum = book, items: List<PlexTrack> = tracks, bytes: Long = 50_000_000, ready: Boolean = true, on: String = server) = DownloadStatus(
        DownloadAlbum(on, "1", LibraryMode.AUDIOBOOK, album, items.map { DownloadTrack(it, DownloadFile("x/${it.id}", 1, if (ready) bytes else 0)) }),
        items.map { it.copy(localUri = if (ready) "file:///storage/offline/${it.id}.m4b" else null) }, null,
        if (ready) items.size else 0, 0, 0, if (ready) bytes * items.size else 0, bytes * items.size,
    )

    private fun live(album: PlexAlbum = book, accountScope: String? = scope, playing: Boolean = true, track: PlexTrack = tracks[1], index: Int = 1, count: Int = 2) =
        LiveBook(album, LibraryMode.AUDIOBOOK, server, accountScope, playing, track, index, count, 90_000, 600_000, 1.25f, 690_000, 1_200_000)

    // --- Phone state ---------------------------------------------------------------------------------------------------

    @Test fun signedOutSendsNothingAboutAnyBook() {
        val state = watchPhoneState(null, null, listOf(saved()), false, listOf(download()), listOf(ReadingListEntry(book, 1)), live(), book.id, 5)
        assertEquals(PhoneStatus.SIGNED_OUT, state.status)
        assertNull(state.nowPlaying)
        assertTrue(state.entries.isEmpty())
        assertNull(state.heldBook)
    }

    @Test fun livePlaybackOfThisAccountIsShownWithItsChapter() {
        val state = watchPhoneState(server, scope, listOf(saved()), false, emptyList(), emptyList(), live(), null, 5)
        assertEquals(PhoneStatus.LOADED, state.status)
        val now = state.nowPlaying!!
        assertEquals(book.title, now.title)
        assertEquals("Part 102", now.chapterTitle)
        assertEquals(2, now.chapterNumber)
        assertEquals(2, now.chapterCount)
        assertEquals(1.25f, now.speed)
        assertEquals(575, now.bookPermille)
        assertTrue(now.live && now.playing)
    }

    @Test fun anotherAccountsPlaybackIsNeverShown() {
        val state = watchPhoneState(server, scope, emptyList(), false, emptyList(), emptyList(), live(accountScope = otherScope), null, 5)
        assertEquals(PhoneStatus.IDLE, state.status)
        assertNull(state.nowPlaying)
    }

    @Test fun withNothingLoadedTheLastBookIsOfferedNotLive() {
        val state = watchPhoneState(server, scope, listOf(saved(), saved(other, accountScope = otherScope, updatedAt = 9_999)), false, emptyList(), emptyList(), null, null, 5)
        assertEquals(PhoneStatus.IDLE, state.status)
        assertEquals(book.id, state.nowPlaying!!.albumId)
        assertFalse(state.nowPlaying!!.live)
    }

    @Test fun offlineOnlyHidesBooksThatAreNotDownloaded() {
        val state = watchPhoneState(server, scope, listOf(saved()), true, emptyList(), emptyList(), live(), null, 5)
        assertNull(state.nowPlaying)
    }

    @Test fun theReadingListIsBoundedAndMarksCompleteDownloadsOnly() {
        val list = (1..15).map { ReadingListEntry(PlexAlbum("$it", "Book $it", "Author", null, 1), it.toLong()) } + ReadingListEntry(book, 99)
        val shortList = listOf(ReadingListEntry(book, 1), ReadingListEntry(other, 2))
        assertEquals(WatchTransferBounds.MAX_LIST_ENTRIES, watchPhoneState(server, scope, emptyList(), false, emptyList(), list, null, null, 5).entries.size)
        val entries = watchPhoneState(server, scope, emptyList(), false, listOf(download(), download(other, ready = false)), shortList, null, null, 5).entries
        assertEquals(100_000_000L, entries[0].downloadedBytes)
        assertNull(entries[1].downloadedBytes)
        val elsewhere = watchPhoneState(server, scope, emptyList(), false, listOf(download(on = "https://other:32400")), shortList, null, null, 5).entries
        assertNull(elsewhere[0].downloadedBytes)
    }

    @Test fun theHeldBookCarriesOnlyThisAccountsPosition() {
        val held = watchPhoneState(server, scope, listOf(saved()), false, emptyList(), emptyList(), null, book.id, 5).heldBook!!
        assertEquals("102", held.trackId)
        assertEquals(200_000L, held.positionMs)
        assertNull(watchPhoneState(server, scope, listOf(saved(accountScope = otherScope)), false, emptyList(), emptyList(), null, book.id, 5).heldBook)
    }

    @Test fun phoneStateNeverCarriesTokensOrAddresses() {
        val bytes = WearCodec.encode(watchPhoneState(server, scope, listOf(saved()), false, listOf(download()), listOf(ReadingListEntry(book, 1)), live(), book.id, 5))
        val text = String(bytes, Charsets.UTF_8)
        listOf(server, scope, "secret", "file://", "/library/").forEach { assertFalse(it, text.contains(it)) }
    }

    // --- Commands ------------------------------------------------------------------------------------------------------

    private fun playback(track: PlexTrack? = tracks[0], index: Int = 0, count: Int = 2, playing: Boolean = true, mode: LibraryMode = LibraryMode.AUDIOBOOK) =
        PlaybackState(ready = true, album = book, track = track, mode = mode, playing = playing, trackIndex = index, trackCount = count)

    @Test fun commandsWithNothingLoadedDoNothing() {
        val player = FakePlayer()
        assertEquals(CommandOutcome.NOTHING_LOADED, runWatchCommand(Command(WearAction.FORWARD_30), PlaybackState(ready = true), 0, player) { })
        assertTrue(player.commands.isEmpty())
    }

    @Test fun transportCommandsUseTheAppsOwnPlayerRules() {
        val player = FakePlayer()
        runWatchCommand(Command(WearAction.BACK_30), playback(), 50_000, player) { }
        runWatchCommand(Command(WearAction.FORWARD_30), playback(), 50_000, player) { }
        runWatchCommand(Command(WearAction.PLAY), playback(playing = true), 50_000, player) { }
        runWatchCommand(Command(WearAction.PAUSE), playback(playing = true), 50_000, player) { }
        runWatchCommand(Command(WearAction.PAUSE), playback(playing = false), 50_000, player) { }
        runWatchCommand(Command(WearAction.PLAY), playback(playing = false), 50_000, player) { }
        assertEquals(listOf("skip:-30000", "skip:30000", "pause", "toggle"), player.commands)
    }

    @Test fun chapterSkipMovesBetweenFilesOrEmbeddedMarkers() {
        val player = FakePlayer()
        runWatchCommand(Command(WearAction.NEXT_CHAPTER), playback(), 50_000, player) { }
        runWatchCommand(Command(WearAction.PREVIOUS_CHAPTER), playback(tracks[1], 1), 1_000, player) { }
        val single = track("201", 3_600_000, listOf(PlexChapter(1, "One", 0, 1_200_000), PlexChapter(2, "Two", 1_200_000, 3_600_000)))
        runWatchCommand(Command(WearAction.NEXT_CHAPTER), playback(single, 0, 1), 50_000, player) { }
        assertEquals(listOf("chapter:1:0", "chapter:0:0", "seek:1200000"), player.commands)
        assertEquals(CommandOutcome.DONE, runWatchCommand(Command(WearAction.NEXT_CHAPTER), playback(single, 0, 1), 2_000_000, player) { })
        assertEquals(3, player.commands.size)
    }

    @Test fun speedIsTheBooksOwnSpeedAndOnlyForAudiobooks() {
        val chosen = mutableListOf<Float>()
        assertEquals(CommandOutcome.DONE, runWatchCommand(Command(WearAction.SPEED, 1.5f), playback(), 0, FakePlayer()) { chosen += it })
        assertEquals(CommandOutcome.NOT_AUDIOBOOK, runWatchCommand(Command(WearAction.SPEED, 1.5f), playback(mode = LibraryMode.MUSIC), 0, FakePlayer()) { chosen += it })
        assertEquals(listOf(1.5f), chosen)
    }

    // --- Transfer selection --------------------------------------------------------------------------------------------

    private fun plan(albumId: String = book.id, list: List<ReadingListEntry> = listOf(ReadingListEntry(book, 1)), history: List<ListeningProgress> = emptyList(),
                     downloads: List<DownloadStatus> = listOf(download()), accountScope: String? = scope) =
        planWatchTransfer(albumId, server, accountScope, list, history, downloads)

    @Test fun onlyAReadingListOrCurrentBookThatIsFullyDownloadedMayGo() {
        assertEquals(TransferPlan.Refused(TransferRefusal.SIGNED_OUT), plan(accountScope = null))
        assertEquals(TransferPlan.Refused(TransferRefusal.NOT_ON_LIST), plan(list = emptyList()))
        assertEquals(TransferPlan.Refused(TransferRefusal.NOT_ON_LIST), plan(list = emptyList(), history = listOf(saved(accountScope = otherScope))))
        assertTrue(plan(list = emptyList(), history = listOf(saved())) is TransferPlan.Ready)
        assertEquals(TransferPlan.Refused(TransferRefusal.NOT_DOWNLOADED), plan(downloads = listOf(download(ready = false))))
        assertEquals(TransferPlan.Refused(TransferRefusal.NOT_DOWNLOADED), plan(downloads = listOf(download(on = "https://other:32400"))))
        assertEquals(TransferPlan.Refused(TransferRefusal.TOO_LARGE), plan(downloads = listOf(download(bytes = WatchTransferBounds.MAX_BOOK_BYTES / 2 + 1))))
    }

    @Test fun theWatchResumesFromThisAccountsUnfinishedPosition() {
        val ready = plan(history = listOf(saved())) as TransferPlan.Ready
        assertEquals(1, ready.resumeIndex)
        assertEquals(200_000L, ready.resumePositionMs)
        val fresh = plan(history = listOf(saved(accountScope = otherScope))) as TransferPlan.Ready
        assertEquals(0, fresh.resumeIndex)
        assertEquals(0L, fresh.resumePositionMs)
        val finished = plan(history = listOf(saved(finished = true))) as TransferPlan.Ready
        assertEquals(0L, finished.resumePositionMs)
        assertNull(ready.album.localThumb)
    }

    @Test fun singleFileBooksCarryTheirChapterMarkers() {
        val single = track("201", 3_600_000, listOf(PlexChapter(1, "One", 0, 1_200_000), PlexChapter(2, "Two", 1_200_000, 3_600_000)))
        val ready = plan(downloads = listOf(download(items = listOf(single)))) as TransferPlan.Ready
        assertEquals(listOf(0L, 1_200_000L), ready.files.single().entry.chapterStartsMs)
    }

    @Test fun theManifestCarriesNoCredentialsOrStreamPaths() {
        val ready = plan(history = listOf(saved())) as TransferPlan.Ready
        val manifest = TransferManifest("transfer-0001", book.id, book.title, book.artist, ready.files.map { it.entry.copy(sha256 = "0".repeat(64)) }, ready.resumeIndex, ready.resumePositionMs, 1)
        assertTrue(manifest.wellFormed())
        val text = String(WearCodec.encode(manifest), Charsets.UTF_8)
        listOf(server, scope, "secret", "X-Plex-Token", "/library/", "file://").forEach { assertFalse(it, text.contains(it)) }
    }

    // --- Checkpoints into the outbox -----------------------------------------------------------------------------------

    private val record = WatchTransferRecord("transfer-0001", scope, server, book.copy(localThumb = null),
        (plan() as TransferPlan.Ready).files.map { it.entry.copy(sha256 = "0".repeat(64)) }, 1)

    private fun checkpoint(position: Long = 120_000, at: Long = 5_000, trackId: String = "102", transfer: String = "transfer-0001", playing: Boolean = false) =
        Checkpoint("c1", transfer, trackId, position, 999_999_999, 200_000, at, "session-1", playing)

    private fun sync(cp: Checkpoint = checkpoint(), rec: WatchTransferRecord? = record, activeScope: String? = scope, history: List<ListeningProgress> = listOf(saved()),
                     baseline: RemoteChapterBaseline? = null, loaded: String? = null) =
        watchCheckpointSync(cp, rec, activeScope, server, history, false, baseline, loaded)

    @Test fun unknownTransfersAndOtherAccountsAreRefused() {
        assertEquals(ReceiptOutcome.UNKNOWN_TRANSFER, sync(rec = null).outcome)
        assertEquals(ReceiptOutcome.UNKNOWN_TRANSFER, sync(checkpoint(transfer = "transfer-0002")).outcome)
        assertEquals(ReceiptOutcome.SCOPE_CHANGED, sync(activeScope = otherScope).outcome)
        assertEquals(ReceiptOutcome.SCOPE_CHANGED, sync(activeScope = null).outcome)
        assertNull(sync(activeScope = otherScope).event)
    }

    @Test fun earlyEndAndForeignPositionsAreNeverSynced() {
        assertEquals(ReceiptOutcome.IGNORED, sync(checkpoint(position = 10_000)).outcome)
        // The phone's own file length decides, so a watch cannot report the end (a completion) of a file.
        assertEquals(ReceiptOutcome.IGNORED, sync(checkpoint(position = 600_000)).outcome)
        assertEquals(ReceiptOutcome.IGNORED, sync(checkpoint(trackId = "999")).outcome)
    }

    @Test fun aNewerPhonePositionIsKept() {
        assertEquals(ReceiptOutcome.STALE, sync(checkpoint(at = 500)).outcome)
        // A loaded phone queue (playing or paused) would later save its older offset, so the phone keeps its own.
        assertEquals(ReceiptOutcome.STALE, sync(loaded = book.id).outcome)
        assertEquals(ReceiptOutcome.QUEUED, sync(loaded = other.id).outcome)
        assertNull(sync(checkpoint(at = 500)).progress)
    }

    @Test fun anAcceptedCheckpointIsAnOrdinaryOutboxEventAndNeverACompletion() {
        val baseline = RemoteChapterBaseline("102", 200_000, 0, 10)
        // 120 s is behind the phone's older 200 s: a rewind on the watch is a real position.
        val result = sync(baseline = baseline)
        assertEquals(ReceiptOutcome.QUEUED, result.outcome)
        val event = result.event!!
        assertEquals(scope, event.scope)
        assertEquals(server, event.server)
        assertEquals(book.id, event.albumId)
        assertEquals("102", event.trackId)
        assertEquals(120_000L, event.positionMs)
        assertEquals(600_000L, event.durationMs)
        assertEquals(200_000L, event.originPositionMs)
        assertEquals(baseline, event.baseline)
        assertEquals(PlexTimelineState.PAUSED, event.state)
        assertTrue(event.playbackSessionId.length >= 8)
        val progress = result.progress!!
        assertFalse(progress.finished)
        assertEquals(scope, progress.accountScope)
        assertEquals(1, progress.trackIndex)
        assertEquals(720_000L, progress.elapsedMs)
        assertEquals(1_200_000L, progress.durationMs)
        assertEquals(5_000L, progress.updatedAt)
        assertNotNull(TimelineOutbox.enqueue(TimelineSyncState(), event).events.single())
    }

    @Test fun aBlockedChapterStaysBlockedWhateverTheWatchSends() {
        val event = sync().event!!
        val blocked = TimelineOutbox.block(TimelineOutbox.enqueue(TimelineSyncState(), event.copy(id = "phone")), event.copy(id = "phone"), TimelineBlock.CONFLICT, null, 1)
        assertEquals(blocked, TimelineOutbox.enqueue(blocked, event))
    }
}
