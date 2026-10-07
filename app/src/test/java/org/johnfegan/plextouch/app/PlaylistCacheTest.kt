package org.johnfegan.plextouch.app

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.data.CachedTrackList
import org.johnfegan.plextouch.data.PlexCache
import org.johnfegan.plextouch.data.PlexClientGateways
import org.johnfegan.plextouch.data.PlexGateway
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.ui.uiText
import org.johnfegan.plextouch.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Ticket 130: recently opened playlist contents stay warm and are only dropped once Plex confirms an edit. */
class PlaylistCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val items = "/playlists/7/items"
    private val list = "/playlists?playlistType=audio"
    private val playlistJson = """{"MediaContainer":{"Metadata":[{"ratingKey":"7","playlistType":"audio","title":"Road","leafCount":2,"smart":false}]}}"""
    private var now = System.currentTimeMillis()
    private var failMutations = false
    private var failReads = false

    private fun server() = FakePlexServer { method, path ->
        when {
            method != "GET" && failMutations -> 500 to ""
            method == "GET" && failReads -> 503 to ""
            path == "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"machine"}}"""
            method == "GET" && path.startsWith("/playlists/") -> 200 to FakePlexServer.items("1", "2", playlist = true)
            path.startsWith("/playlists") && (method == "GET" || method == "POST") -> 200 to playlistJson
            else -> 200 to ""
        }
    }

    private fun opened(gateways: PlexClientGateways, server: FakePlexServer): Pair<List<List<PlexTrack>>, TracksLoaded> {
        val shown = mutableListOf<List<PlexTrack>>()
        val loaded = runBlocking { AlbumPlayback(gateways, FakePlayer(), FakeStore()).tracks(server.connection, "playlist:7") { shown += it } }
        return shown to loaded
    }

    @Test fun aFreshPlaylistOpensFromTheCacheWithoutASecondRequest() {
        server().use { server ->
            val gateways = PlexClientGateways(PlexCache(temporary.newFolder()) { now }) { "test-device" }
            assertEquals(TracksLoaded.SERVER, opened(gateways, server).second)
            val (shown, loaded) = opened(gateways, server)
            assertEquals(TracksLoaded.FRESH_CACHE, loaded)
            assertEquals(listOf(listOf("10", "20")), shown.map { tracks -> tracks.map { it.playlistItemId } })
            assertEquals(1, server.gets(items))
        }
    }

    @Test fun aStaleListIsShownAtOnceThenRefreshed() {
        server().use { server ->
            val gateways = PlexClientGateways(PlexCache(temporary.newFolder()) { now }) { "test-device" }
            opened(gateways, server)
            now += PlexCache.TTL_MS + 1
            val (shown, loaded) = opened(gateways, server)
            assertEquals(TracksLoaded.SERVER, loaded)
            assertEquals("the stale copy first, then Plex's", 2, shown.size)
            assertEquals(2, server.gets(items))
        }
    }

    @Test fun aFailedRefreshKeepsTheLastKnownListUsable() {
        server().use { server ->
            val gateways = PlexClientGateways(PlexCache(temporary.newFolder()) { now }) { "test-device" }
            val (first, _) = opened(gateways, server)
            now += PlexCache.TTL_MS + 1
            failReads = true
            val (shown, loaded) = opened(gateways, server)
            assertEquals(TracksLoaded.STALE_KEPT, loaded)
            assertEquals("the saved list stays on screen rather than going blank", first, shown)
        }
    }

    @Test fun withNothingCachedAFailedLoadIsStillAnError() = runBlocking {
        val gateway = FakeGateway().apply { trackList = { throw IOException("unreachable") } }
        try {
            AlbumPlayback(FakeGateways(gateway), FakePlayer(), FakeStore()).tracks(connection, "playlist:7") { fail("nothing to show") }
            fail("expected the failure to reach the job's error slot")
        } catch (_: IOException) { }
    }

    @Test fun staleRefreshFailureOnTheFakeGatewayReportsStaleKept() = runBlocking {
        val gateway = FakeGateway().apply {
            cachedTracks = { CachedTrackList(listOf(track("1")), fresh = false) }
            trackList = { throw IOException("unreachable") }
        }
        val shown = mutableListOf<List<PlexTrack>>()
        assertEquals(TracksLoaded.STALE_KEPT, AlbumPlayback(FakeGateways(gateway), FakePlayer(), FakeStore()).tracks(connection, "playlist:7") { shown += it })
        assertEquals(listOf(listOf(track("1"))), shown)
    }

    /** Primes both caches, runs `edit` failing then succeeding, and checks what each outcome left behind. */
    private fun mutation(name: String, dropsItems: Boolean = true, edit: suspend (PlexGateway) -> Unit) {
        failMutations = false
        server().use { server ->
            val cache = PlexCache(temporary.newFolder()) { now }
            val gateways = PlexClientGateways(cache) { "test-device" }
            runBlocking { gateways.open(server.connection).apply { playlists(); albumTracks("playlist:7") } }
            val mutations = PlaylistMutations(gateways)
            val label = uiText(R.string.job_saving_playlist)

            failMutations = true
            val failed = runBlocking { mutations.mutate(server.connection, label) { edit(it); null } }
            assertTrue("$name: a refused edit is reported", failed is MutationResult.Failed)
            assertTrue("$name: an unconfirmed edit leaves the playlist contents cached", cache.entry(server.connection, items)?.fresh == true)
            assertTrue("$name: and the playlist list cached", cache.entry(server.connection, list)?.fresh == true)
            assertEquals("$name: no refresh after a refused edit", 1, server.gets(list))

            failMutations = false
            val done = runBlocking { mutations.mutate(server.connection, label) { edit(it); null } }
            assertTrue("$name: confirmed", done is MutationResult.Done)
            if (dropsItems) assertNull("$name: the edited playlist's contents are dropped once Plex confirms", cache.entry(server.connection, items))
            else assertNotNull("$name: other playlists' contents are untouched", cache.entry(server.connection, items))
            assertEquals("$name: the playlist list is re-read from Plex, not reused", 2, server.gets(list))
        }
    }

    private val track = PlexTrack("123", "Song", "Artist", "Album", 1000, "/file.mp3", "mp3")

    @Test fun createInvalidatesThePlaylistListOnlyAfterPlexConfirms() = mutation("create", dropsItems = false) { it.createPlaylist("New", listOf(track)) }
    @Test fun addInvalidatesTheTargetPlaylistOnlyAfterPlexConfirms() = mutation("add") { it.addToPlaylist("7", listOf(track)) }
    @Test fun renameInvalidatesThePlaylistOnlyAfterPlexConfirms() = mutation("rename") { it.renamePlaylist("7", "Evening") }
    @Test fun removeInvalidatesThePlaylistOnlyAfterPlexConfirms() = mutation("remove") { it.removePlaylistItem("7", "20") }
    @Test fun moveInvalidatesThePlaylistOnlyAfterPlexConfirms() = mutation("move") { it.movePlaylistItem("7", "20", "10") }
    @Test fun deleteInvalidatesThePlaylistOnlyAfterPlexConfirms() = mutation("delete") { it.deletePlaylist("7") }

    @Test fun aReloadAfterAConfirmedEditStoresTheNewContentsFresh() {
        server().use { server ->
            val cache = PlexCache(temporary.newFolder()) { now }
            val gateways = PlexClientGateways(cache) { "test-device" }
            runBlocking {
                PlaylistMutations(gateways).mutate(server.connection, uiText(R.string.job_removing_playlist_entry)) { api ->
                    api.removePlaylistItem("7", "20"); api.albumTracks("playlist:7"); null
                }
            }
            assertTrue(cache.entry(server.connection, items)?.fresh == true)
            assertEquals(TracksLoaded.FRESH_CACHE, opened(gateways, server).second)
            assertEquals(1, server.gets(items))
        }
    }
}
