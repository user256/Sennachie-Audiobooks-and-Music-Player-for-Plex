package org.johnfegan.plextouch.app

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.data.CachedAlbumList
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectAndLoadTest {
    private val virtual = PlexConnection("https://10.0.0.1:32400", "t")
    private val lan = PlexConnection("https://plex.lan:32400", "t")
    private val dune = album("10", "Dune")
    private val emma = album("11", "Emma")

    @Test fun connectSkipsTheUnreachableAddressThenShowsCachedAndFreshAlbums() = runBlocking {
        val gateway = FakeGateway().apply {
            cachedAlbums = { CachedAlbumList(listOf(dune), fresh = false) }
            albumList = { listOf(dune, emma) }
        }
        val gateways = FakeGateways(gateway).apply { probe = { if (it == virtual) throw IOException("No route") else listOf(section) } }
        val store = FakeStore()

        val session = ConnectToServer(gateways, store).connect(PlexServer("Home", listOf(virtual, lan)))
        assertEquals(lan, session.connection)
        assertEquals(lan, store.saved)
        assertEquals(listOf(section), session.sections)
        assertEquals("saved progress is upgraded to the account scope on connect", listOf(lan), store.migrated)
        assertEquals(store.progressScope(lan), session.accountScope)

        val shown = mutableListOf<List<PlexAlbum>>()
        val downloads = FakeDownloads()
        LoadLibrary(gateways, downloads).albums(session.connection, section.id) { shown += it }
        assertEquals("the stale cache is shown first, then the server's list", listOf(listOf(dune), listOf(dune, emma)), shown)
        assertEquals("only the fresh list reaches the offline catalogue", listOf(listOf(dune, emma)), downloads.metadata)
        assertEquals(listOf("cachedAlbumList", "albums"), gateway.calls)
    }

    @Test fun freshCacheIsShownWithoutAskingTheServer() = runBlocking {
        val gateway = FakeGateway().apply { cachedAlbums = { CachedAlbumList(listOf(dune), fresh = true) }; albumList = { error("must not fetch") } }
        val downloads = FakeDownloads()
        val shown = mutableListOf<List<PlexAlbum>>()
        LoadLibrary(FakeGateways(gateway), downloads).albums(lan, section.id) { shown += it }
        assertEquals(listOf(listOf(dune)), shown)
        assertEquals(listOf(listOf(dune)), downloads.metadata)
    }

    @Test fun forcedRefreshSkipsTheCacheAndBypassesItOnTheServerRead() = runBlocking {
        val gateway = FakeGateway().apply { cachedAlbums = { error("must not read the cache") }; albumList = { listOf(emma) } }
        val gateways = FakeGateways(gateway)
        val shown = mutableListOf<List<PlexAlbum>>()
        LoadLibrary(gateways, FakeDownloads()).albums(lan, section.id, force = true) { shown += it }
        assertEquals(listOf(listOf(emma)), shown)
        assertEquals(listOf(lan to true), gateways.opened)
    }

    @Test fun manualConnectionIsTrimmedAndRestoredAtLaunch() {
        val store = FakeStore()
        val connect = ConnectToServer(FakeGateways(), store)
        assertNull(connect.restore())
        val session = connect.saveManual("https://plex.lan:32400/ ", " token ")
        assertEquals(PlexConnection("https://plex.lan:32400", "token"), session.connection)
        assertTrue(session.sections.isEmpty())
        assertEquals(session.connection, connect.restore()?.connection)
        assertEquals("both the save and the restore migrate scope", 2, store.migrated.size)
    }
}
