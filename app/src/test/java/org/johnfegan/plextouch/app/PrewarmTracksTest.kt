package org.johnfegan.plextouch.app

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.data.CachedTrackList
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexCache
import org.johnfegan.plextouch.data.PlexClientGateways
import org.johnfegan.plextouch.data.PlexTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrewarmTracksTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = "account"
    private val catalogue = (1..20).map { album("$it") }
    private fun played(id: String, at: Long, mode: LibraryMode = LibraryMode.AUDIOBOOK, server: String = connection.serverUrl, owner: String? = scope) =
        progress(album(id), 0, 1_000, scope = owner, mode = mode).copy(updatedAt = at, server = server)
    private fun favourite(album: PlexAlbum) = album.copy(userRating = 10f)
    private fun candidates(history: List<org.johnfegan.plextouch.data.ListeningProgress>, albums: List<PlexAlbum> = catalogue, downloads: List<org.johnfegan.plextouch.data.DownloadStatus> = emptyList(), mode: LibraryMode = LibraryMode.AUDIOBOOK, offline: Boolean = false) =
        prewarmCandidates(history, albums, downloads, connection.serverUrl, scope, mode, offline)

    @Test fun recentTitlesComeFirstNewestFirstThenFavouritesWithoutRepeats() {
        val albums = catalogue.map { if (it.id in setOf("5", "6", "2")) favourite(it) else it }
        val history = listOf(played("3", at = 10), played("2", at = 30), played("9", at = 20))
        assertEquals(listOf("2", "9", "3", "5", "6"), candidates(history, albums))
    }

    @Test fun onlyThisServerAccountModeAndCatalogueAreConsidered() {
        val history = listOf(
            played("1", at = 1, server = "https://other:32400"),
            played("2", at = 2, owner = "someone-else"),
            played("3", at = 3, mode = LibraryMode.MUSIC),
            played("99", at = 4),
            played("4", at = 5),
        )
        assertEquals("other servers, accounts, modes and titles outside the catalogue are skipped", listOf("4"), candidates(history))
    }

    @Test fun selectionIsBoundedPerShelfAndOverall() {
        val history = (1..10).map { played("$it", at = it.toLong()) }
        val albums = catalogue.map(::favourite)
        val picked = candidates(history, albums)
        assertEquals(PrewarmTracks.LIMIT, picked.size)
        assertEquals("the four newest recent titles", listOf("10", "9", "8", "7"), picked.take(PrewarmTracks.RECENT_LIMIT))
        assertEquals("then at most four favourites not already chosen", listOf("1", "2", "3", "4"), picked.drop(PrewarmTracks.RECENT_LIMIT))
        assertTrue("no catalogue is never prewarmed wholesale", picked.size < catalogue.size)
    }

    @Test fun downloadedTitlesAreLeftToTheirAuthoritativeDownload() {
        val history = listOf(played("1", at = 2), played("2", at = 1))
        val local = download(album("1"), listOf(track("11", local = true)))
        assertEquals(listOf("2"), candidates(history, downloads = listOf(local)))
    }

    @Test fun recentPlaylistsAreWarmedInMusicModeOnly() {
        val history = listOf(played("playlist:7", at = 2, mode = LibraryMode.MUSIC))
        assertEquals(listOf("playlist:7"), candidates(history, mode = LibraryMode.MUSIC))
        assertEquals(emptyList<String>(), candidates(history.map { it.copy(mode = LibraryMode.AUDIOBOOK) }))
    }

    @Test fun nothingIsSelectedOfflineOrBeforeACatalogueArrives() {
        val history = listOf(played("1", at = 1))
        assertEquals(emptyList<String>(), candidates(history, offline = true))
        assertEquals(emptyList<String>(), candidates(history, albums = emptyList()))
    }

    @Test fun freshEntriesAreSkippedAndStaleOrMissingOnesFetched() = runBlocking {
        val gateway = FakeGateway().apply {
            cachedTracks = { id -> when (id) { "1" -> CachedTrackList(listOf(track("11")), fresh = true); "2" -> CachedTrackList(listOf(track("21")), fresh = false); else -> null } }
            trackList = { listOf(track("x")) }
        }
        val fetched = PrewarmTracks(FakeGateways(gateway), Dispatchers.Unconfined).prewarm(connection, listOf("1", "2", "3"), offlineOnly = false)
        assertEquals(listOf("2", "3"), fetched)
        assertEquals(listOf("2", "3"), gateway.trackRequests)
    }

    @Test fun offlineOnlyModeNeverOpensAGateway() = runBlocking {
        val gateways = FakeGateways()
        assertEquals(emptyList<String>(), PrewarmTracks(gateways, Dispatchers.Unconfined).prewarm(connection, listOf("1"), offlineOnly = true))
        assertTrue(gateways.opened.isEmpty())
    }

    @Test fun neverFetchesMoreThanTheLimitEvenIfAskedTo() = runBlocking {
        val gateway = FakeGateway()
        PrewarmTracks(FakeGateways(gateway), Dispatchers.Unconfined).prewarm(connection, (1..30).map { "$it" }, offlineOnly = false)
        assertEquals(PrewarmTracks.LIMIT, gateway.trackRequests.size)
    }

    @Test fun stopsWhenTheLibraryModeServerOrAccountChanges() = runBlocking {
        var current = true
        val gateway = FakeGateway().apply { trackList = { current = false; emptyList() } }
        val fetched = PrewarmTracks(FakeGateways(gateway), Dispatchers.Unconfined).prewarm(connection, listOf("1", "2", "3"), offlineOnly = false) { current }
        assertEquals(listOf("1"), fetched)
        assertEquals(listOf("1"), gateway.trackRequests)
    }

    @Test fun stopsWhenItsJobIsCancelled() = runBlocking {
        lateinit var job: Job
        val gateway = FakeGateway().apply { trackList = { job.cancel(); emptyList() } }
        val run = async(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            PrewarmTracks(FakeGateways(gateway), Dispatchers.Unconfined).prewarm(connection, listOf("1", "2", "3"), offlineOnly = false)
        }
        job = run
        val outcome = runCatching { run.await() }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertEquals(listOf("1"), gateway.trackRequests)
    }

    @Test fun stopsAtTheFirstFailureRatherThanRetryingEveryTitle() = runBlocking {
        val gateway = FakeGateway().apply { trackList = { throw IOException("unreachable") } }
        assertEquals(emptyList<String>(), PrewarmTracks(FakeGateways(gateway), Dispatchers.Unconfined).prewarm(connection, listOf("1", "2"), offlineOnly = false))
        assertEquals(listOf("1"), gateway.trackRequests)
    }

    @Test fun openingAFreshCachedTitleAsksPlexNothingAndAStaleOneRefreshes() = runBlocking {
        val gateway = FakeGateway().apply { trackList = { listOf(track("new")) } }
        val playback = AlbumPlayback(FakeGateways(gateway), FakePlayer(), FakeStore())
        val shown = mutableListOf<List<PlexTrack>>()
        gateway.cachedTracks = { CachedTrackList(listOf(track("old")), fresh = true) }
        playback.tracks(connection, "1") { shown += it }
        assertEquals(listOf(listOf(track("old"))), shown)
        assertTrue(gateway.trackRequests.isEmpty())
        gateway.cachedTracks = { CachedTrackList(listOf(track("old")), fresh = false) }
        playback.tracks(connection, "1") { shown += it }
        assertEquals(listOf(listOf(track("old")), listOf(track("old")), listOf(track("new"))), shown)
        assertEquals(listOf("1"), gateway.trackRequests)
    }

    @Test fun aPrewarmedTitleOpensFromTheRealCacheUntilItGoesStale() = runBlocking {
        var now = System.currentTimeMillis()
        FakePlexServer { _, _ -> 200 to FakePlexServer.items("11", "12") }.use { server ->
            val gateways = PlexClientGateways(PlexCache(temporary.newFolder()) { now }) { "test-device" }
            val path = "/library/metadata/10/children?includeChapters=1"
            assertEquals(listOf("10"), PrewarmTracks(gateways).prewarm(server.connection, listOf("10"), offlineOnly = false))
            assertEquals(1, server.gets(path))

            val shown = mutableListOf<List<PlexTrack>>()
            AlbumPlayback(gateways, FakePlayer(), FakeStore()).tracks(server.connection, "10") { shown += it }
            assertEquals(listOf(listOf("11", "12")), shown.map { list -> list.map { it.id } })
            assertEquals("opening the prewarmed title made no new request", 1, server.gets(path))
            assertEquals("a second prewarm skips the fresh entry", emptyList<String>(), PrewarmTracks(gateways).prewarm(server.connection, listOf("10"), offlineOnly = false))

            now += PlexCache.TTL_MS + 1
            AlbumPlayback(gateways, FakePlayer(), FakeStore()).tracks(server.connection, "10") { shown += it }
            assertEquals("a stale entry is refreshed", 2, server.gets(path))
            assertTrue("listening progress is never requested", server.calls.none { it.contains("timeline") })
        }
    }
}
