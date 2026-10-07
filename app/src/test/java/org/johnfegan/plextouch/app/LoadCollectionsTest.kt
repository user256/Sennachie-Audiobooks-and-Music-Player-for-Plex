package org.johnfegan.plextouch.app

import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexCache
import org.johnfegan.plextouch.data.PlexClientGateways
import org.johnfegan.plextouch.data.PlexCollection
import org.johnfegan.plextouch.data.PlexConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Ticket 131: collections through the real client, its disk cache and a loopback Plex. */
class LoadCollectionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 1_700_000_000_000L
    private val collectionsPath = "/library/sections/1/collections"
    private val childrenPath = "/library/collections/50/children"

    private val collectionsJson = """{"MediaContainer":{"Metadata":[
        {"ratingKey":"50","title":"Discworld","childCount":"3","thumb":"/library/collections/50/thumb","subtype":"album","smart":"0"},
        {"ratingKey":"51","title":"Recently added","childCount":12,"smart":"1"}]}}"""

    /** Plex order is kept; a track, an artist and an album from another library are dropped. */
    private val mixedChildrenJson = """{"MediaContainer":{"Metadata":[
        {"ratingKey":"3","type":"album","title":"Mort","parentTitle":"Terry Pratchett","year":1987,"leafCount":9,"librarySectionID":1},
        {"ratingKey":"7","type":"track","title":"A song","parentTitle":"An album","librarySectionID":1},
        {"ratingKey":"8","type":"artist","title":"A band","librarySectionID":1},
        {"ratingKey":"9","type":"album","title":"Music album","parentTitle":"A band","librarySectionID":2},
        {"ratingKey":"1","type":"album","title":"Guards! Guards!","parentTitle":"Terry Pratchett","year":1989,"leafCount":10,"librarySectionID":"1"}]}}"""

    private class Recorder<T> { val shown = mutableListOf<List<T>>() }

    private fun gateways() = PlexClientGateways(PlexCache(temporary.newFolder()) { now }) { "test-device" }

    private fun collections(load: LoadCollections, connection: PlexConnection, force: Boolean = false, offline: Boolean = false): Pair<Recorder<PlexCollection>, ShelfLoad> {
        val recorder = Recorder<PlexCollection>()
        val result = runBlocking { load.collections(connection, "1", force, offline) { recorder.shown += it } }
        return recorder to result
    }

    private fun books(load: LoadCollections, connection: PlexConnection, offline: Boolean = false): Pair<Recorder<PlexAlbum>, ShelfLoad> {
        val recorder = Recorder<PlexAlbum>()
        val result = runBlocking { load.books(connection, "1", "50", offlineOnly = offline) { recorder.shown += it } }
        return recorder to result
    }

    @Test fun collectionsParseAndAFreshCacheAnswersWithoutPlex() {
        FakePlexServer { _, path -> if (path == collectionsPath) 200 to collectionsJson else 404 to "" }.use { server ->
            val load = LoadCollections(gateways())
            val (first, firstResult) = collections(load, server.connection)
            assertEquals(ShelfLoad.SERVER, firstResult)
            assertEquals(listOf(PlexCollection("50", "Discworld", 3, "/library/collections/50/thumb", false, "album"), PlexCollection("51", "Recently added", 12, null, true, null)), first.shown.single())
            val (second, secondResult) = collections(load, server.connection)
            assertEquals(ShelfLoad.FRESH_CACHE, secondResult)
            assertEquals(first.shown.single(), second.shown.single())
            assertEquals("the fresh copy is reused", 1, server.gets(collectionsPath))
        }
    }

    @Test fun aStaleCacheIsShownFirstThenReplacedAndKeptWhenPlexFails() {
        var failing = false
        FakePlexServer { _, path -> if (failing) 500 to "" else if (path == collectionsPath) 200 to collectionsJson else 404 to "" }.use { server ->
            val load = LoadCollections(gateways())
            collections(load, server.connection)
            now += PlexCache.TTL_MS + 60_000
            val (refreshed, result) = collections(load, server.connection)
            assertEquals(ShelfLoad.SERVER, result)
            assertEquals("stale copy, then Plex's", 2, refreshed.shown.size)
            now += PlexCache.TTL_MS + 60_000
            failing = true
            val (kept, keptResult) = collections(load, server.connection)
            assertEquals(ShelfLoad.STALE_KEPT, keptResult)
            assertEquals("the last-known list stays on screen", listOf("50", "51"), kept.shown.single().map { it.id })
        }
    }

    @Test fun offlineShowsTheLastKnownCopyAndNeverAsksPlex() {
        FakePlexServer { _, path -> if (path == collectionsPath) 200 to collectionsJson else 404 to "" }.use { server ->
            val load = LoadCollections(gateways())
            val (nothing, nothingResult) = collections(load, server.connection, offline = true)
            assertEquals(ShelfLoad.NOTHING_SAVED, nothingResult)
            assertEquals(listOf(emptyList<PlexCollection>()), nothing.shown)
            assertEquals(0, server.calls.size)
            collections(load, server.connection)
            now += PlexCache.TTL_MS * 10
            val (saved, savedResult) = collections(load, server.connection, offline = true)
            assertEquals(ShelfLoad.OFFLINE_CACHE, savedResult)
            assertEquals(listOf("50", "51"), saved.shown.single().map { it.id })
            assertEquals("offline never reaches Plex", 1, server.calls.size)
        }
    }

    @Test fun pullToRefreshAsksPlexEvenWhenTheCacheIsFresh() {
        FakePlexServer { _, path -> if (path == collectionsPath) 200 to collectionsJson else 404 to "" }.use { server ->
            val load = LoadCollections(gateways())
            collections(load, server.connection)
            val (_, result) = collections(load, server.connection, force = true)
            assertEquals(ShelfLoad.SERVER, result)
            assertEquals(2, server.gets(collectionsPath))
        }
    }

    @Test fun anotherAccountOrServerNeverSeesThisAccountsCachedCollections() {
        FakePlexServer { _, path -> if (path == collectionsPath) 200 to collectionsJson else 404 to "" }.use { server ->
            val load = LoadCollections(gateways())
            collections(load, server.connection)
            val otherAccount = server.connection.copy(token = "someone-else")
            val (other, result) = collections(load, otherAccount, offline = true)
            assertEquals("the cache is namespaced by account", ShelfLoad.NOTHING_SAVED, result)
            assertEquals(listOf(emptyList<PlexCollection>()), other.shown)
            val otherServer = PlexConnection("http://127.0.0.1:1", server.connection.token)
            assertEquals(ShelfLoad.NOTHING_SAVED, collections(load, otherServer, offline = true).second)
        }
    }

    @Test fun mixedMediaCollectionsKeepOnlyThisLibrarysAlbumsInCollectionOrder() {
        FakePlexServer { _, path -> if (path == childrenPath) 200 to mixedChildrenJson else 404 to "" }.use { server ->
            val (shown, result) = books(LoadCollections(gateways()), server.connection)
            assertEquals(ShelfLoad.SERVER, result)
            val books = shown.shown.single()
            assertEquals(listOf("3", "1"), books.map { it.id })
            assertEquals(listOf("Mort", "Guards! Guards!"), books.map { it.title })
            assertEquals("Terry Pratchett", books.first().artist)
        }
    }

    @Test fun anEmptyCollectionShowsAnEmptyList() {
        FakePlexServer { _, path -> if (path == childrenPath) 200 to """{"MediaContainer":{"size":0}}""" else 404 to "" }.use { server ->
            val (shown, result) = books(LoadCollections(gateways()), server.connection)
            assertEquals(ShelfLoad.SERVER, result)
            assertEquals(listOf(emptyList<PlexAlbum>()), shown.shown)
        }
    }

    @Test fun aDeletedCollectionShowsEmptyAndForgetsItsCachedBooks() {
        var deleted = false
        FakePlexServer { _, path -> if (deleted || path != childrenPath) 404 to "" else 200 to mixedChildrenJson }.use { server ->
            val load = LoadCollections(gateways())
            books(load, server.connection)
            now += PlexCache.TTL_MS + 60_000
            deleted = true
            val (shown, result) = books(load, server.connection)
            assertEquals(ShelfLoad.GONE, result)
            assertEquals("stale copy first, then the empty state", listOf(listOf("3", "1"), emptyList()), shown.shown.map { list -> list.map { it.id } })
            val (offline, offlineResult) = books(load, server.connection, offline = true)
            assertEquals("a deleted collection is not resurrected offline", ShelfLoad.NOTHING_SAVED, offlineResult)
            assertTrue(offline.shown.single().isEmpty())
        }
    }

    @Test fun anUnreachableServerWithNothingSavedIsAnError() {
        FakePlexServer { _, _ -> 500 to "" }.use { server ->
            val failure = runCatching { collections(LoadCollections(gateways()), server.connection) }.exceptionOrNull()
            assertTrue(failure is org.johnfegan.plextouch.data.PlexHttpException)
        }
    }
}
