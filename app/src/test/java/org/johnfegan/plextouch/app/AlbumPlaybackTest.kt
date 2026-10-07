package org.johnfegan.plextouch.app

import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.uiText

class AlbumPlaybackTest {
    private val store = FakeStore().apply { speeds[LibraryMode.AUDIOBOOK] = 1.5f }
    private val player = FakePlayer()
    private val gateway = FakeGateway()
    private val playback = AlbumPlayback(FakeGateways(gateway), player, store)
    private val book = album("10", "Dune")
    private val tracks = listOf(track("11"), track("12"), track("13"), track("14"))
    private val scope = store.progressScope(connection)

    private fun play(history: List<org.johnfegan.plextouch.data.ListeningProgress>, current: PlaybackState = PlaybackState(ready = true), offline: Boolean = false, index: Int? = null, offsetMs: Long = 0, shuffled: Boolean = false, infinite: Boolean = false) =
        playback.play(connection, book, tracks, LibraryMode.AUDIOBOOK, current, history, scope, offline, infinite, index, shuffled, offsetMs)

    @Test fun unfinishedAudiobookResumesAtItsSavedChapterAndOffset() {
        assertEquals(PlayOutcome.Started, play(listOf(progress(book, trackIndex = 2, positionMs = 42_000, scope = scope))))
        val started = player.plays.single()
        assertEquals(2, started.index)
        assertEquals(42_000L, started.positionMs)
        assertEquals("the saved audiobook speed is applied", 1.5f, started.speed)
        assertEquals(scope, started.scope)
    }

    @Test fun finishedAudiobookStartsFromTheBeginning() {
        assertEquals(PlayOutcome.Started, play(listOf(progress(book, trackIndex = 3, positionMs = 50_000, finished = true, scope = scope))))
        val started = player.plays.single()
        assertEquals(0, started.index)
        assertEquals(0L, started.positionMs)
    }

    @Test fun progressFromAnotherAccountOrServerIsNotResumed() {
        play(listOf(progress(book, trackIndex = 2, positionMs = 42_000, scope = "someone-else")))
        assertEquals(PlayPlan.Start(0, 0), player.plays.single().let { PlayPlan.Start(it.index, it.positionMs) })
    }

    @Test fun anExplicitChapterStartsThereAndIgnoresSavedProgress() {
        play(listOf(progress(book, trackIndex = 2, positionMs = 42_000, scope = scope)), index = 0, offsetMs = 7_500)
        val started = player.plays.single()
        assertEquals(0, started.index)
        assertEquals(7_500L, started.positionMs)
    }

    @Test fun theLoadedAlbumIsResumedWithAToggleRatherThanRestarted() {
        val paused = PlaybackState(ready = true, album = book, playing = false, mode = LibraryMode.AUDIOBOOK)
        assertEquals(PlayOutcome.Started, play(emptyList(), current = paused))
        assertTrue(player.plays.isEmpty())
        assertEquals(listOf("toggle"), player.commands)
        play(emptyList(), current = paused.copy(playing = true))
        assertEquals("already playing: nothing to do", listOf("toggle"), player.commands)
    }

    @Test fun offlineAnIncompleteDownloadIsRefusedBeforeTouchingThePlayer() {
        val outcome = play(emptyList(), offline = true)
        assertEquals(PlayOutcome.Refused(uiText(R.string.play_incomplete_download)), outcome)
        assertTrue(player.plays.isEmpty() && player.commands.isEmpty())
    }

    @Test fun lastPlayedBarResumesAnUnfinishedBookAndRestartsAFinishedOne() = runBlocking {
        gateway.trackList = { tracks }
        assertEquals(PlayOutcome.Started, playback.resume(connection, progress(book, 1, 9_000, scope = scope), null, offlineOnly = false))
        assertEquals(1 to 9_000L, player.plays.last().let { it.index to it.positionMs })
        assertEquals(PlayOutcome.Started, playback.resume(connection, progress(book, 1, 9_000, finished = true, scope = scope), null, offlineOnly = false))
        assertEquals(0 to 0L, player.plays.last().let { it.index to it.positionMs })
    }

    @Test fun lastPlayedOfflineNeedsACompleteDownloadAndThenPlaysTheLocalFiles() = runBlocking {
        val local = tracks.map { track(it.id, local = true) }
        val refused = playback.resume(connection, progress(book, 1, 9_000, scope = scope), download(book, local, ready = false), offlineOnly = true)
        assertEquals(PlayOutcome.Refused(uiText(R.string.resume_needs_download)), refused)
        assertTrue(player.plays.isEmpty())
        gateway.trackList = { error("offline must not stream") }
        assertEquals(PlayOutcome.Started, playback.resume(connection, progress(book, 1, 9_000, scope = scope), download(book, local), offlineOnly = true))
        assertEquals(local, player.plays.single().tracks)
    }

    @Test fun aCheckpointOnAFileBoundaryResumesAtTheStartOfTheNextFile() = runBlocking {
        // Each file is 60 s; the service checkpointed the very end of file 1.
        play(listOf(progress(book, trackIndex = 1, positionMs = 60_000, trackId = "12", scope = scope)))
        assertEquals(2 to 0L, player.plays.last().let { it.index to it.positionMs })
        gateway.trackList = { tracks }
        playback.resume(connection, progress(book, 1, 60_000, trackId = "12", scope = scope), null, offlineOnly = false)
        assertEquals(2 to 0L, player.plays.last().let { it.index to it.positionMs })
    }

    @Test fun resumeFollowsTheSavedTrackIdWhenTheFileListChanged() {
        val grown = listOf(track("10")) + tracks
        playback.play(connection, book, grown, LibraryMode.AUDIOBOOK, PlaybackState(ready = true), listOf(progress(book, 1, 42_000, trackId = "12", scope = scope)), scope, false)
        assertEquals("file 12 is now third", 2 to 42_000L, player.plays.single().let { it.index to it.positionMs })
    }

    @Test fun replacingTheQueueWithAnotherBookUsesOnlyThatBooksProgress() {
        val other = album("20", "Emma")
        val playingDune = PlaybackState(ready = true, album = book, playing = true, mode = LibraryMode.AUDIOBOOK, trackIndex = 3)
        val history = listOf(progress(book, trackIndex = 3, positionMs = 50_000, trackId = "14", scope = scope), progress(other, trackIndex = 1, positionMs = 7_000, trackId = "12", scope = scope))
        playback.play(connection, other, tracks, LibraryMode.AUDIOBOOK, playingDune, history, scope, false)
        val started = player.plays.single()
        assertEquals(other, started.album)
        assertEquals("Emma's own checkpoint, not Dune's", 1 to 7_000L, started.index to started.positionMs)
        assertTrue(player.commands.isEmpty())
        playback.play(connection, album("30"), tracks, LibraryMode.AUDIOBOOK, playingDune.copy(album = other), history, scope, false)
        assertEquals("a book with no history starts at the top", 0 to 0L, player.plays.last().let { it.index to it.positionMs })
    }

    @Test fun anEmptyTrackListIsIgnored() = runBlocking {
        assertEquals(PlayOutcome.Ignored, playback.resume(connection, progress(book, 0, 0, scope = scope), null, offlineOnly = false))
        assertTrue(player.plays.isEmpty())
    }
}
