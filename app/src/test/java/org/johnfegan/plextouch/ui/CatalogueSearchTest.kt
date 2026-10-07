package org.johnfegan.plextouch.ui

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.johnfegan.plextouch.app.album
import org.johnfegan.plextouch.app.connection
import org.johnfegan.plextouch.app.download
import org.johnfegan.plextouch.app.track
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueSearchTest {
    private fun book(id: String, title: String, artist: String) = PlexAlbum(id, title, artist, null, 1)
    private val catalogue = listOf(
        book("1", "Sigur Rós Live", "Sigur Rós"),
        book("2", "Lemonade", "Beyoncé"),
        book("3", "Die Straße", "Bjørk Guðmundsdóttir"),
        book("4", "the Left Hand of Darkness", "Ursula K. Le Guin"),
        book("5", "Dune", "Frank Herbert"),
        book("6", "Dune Messiah", "Frank Herbert"),
    )

    @Test fun foldingIgnoresCaseAccentsAndSpacing() {
        assertEquals("beyonce", searchKey("BEYONCÉ"))
        assertEquals("sigur ros", searchKey("  Sigur   Rós "))
        assertEquals("die strasse", searchKey("Die Straße"))
        assertEquals("bjork", searchKey("Bjørk"))
        assertEquals(listOf("le", "guin"), searchWords(" LE\tGuin "))
        assertTrue(searchWords("   ").isEmpty())
    }

    @Test fun titleAndArtistMatchingIsCaseAndAccentInsensitiveAndMultiWord() {
        fun ids(query: String) = SearchIndex(catalogue).search(query).map { it.id }
        assertEquals(listOf("2"), ids("beyonce"))
        assertEquals(listOf("2"), ids("BEYONCÉ lemon"))
        assertEquals("accents in the query also fold", listOf("1"), ids("rós live"))
        assertEquals("an author's accented name matches plain letters", listOf("3"), ids("bjork strasse"))
        assertEquals("words may come from the title and the author in any order", listOf("5", "6"), ids("herbert dune"))
        assertEquals(listOf("6"), ids("messiah   FRANK"))
        assertTrue(ids("dune guin").isEmpty())
        // The existing helper agrees with the index, so Library and Search never disagree on what matches.
        assertEquals(ids("bjork strasse"), presentAlbums(catalogue, "bjork strasse", AlbumSort.TITLE).map { it.id })
    }

    @Test fun resultsKeepSearchsTitleOrderAndABlankQueryListsEverything() {
        val index = SearchIndex(catalogue)
        val titleOrder = presentAlbums(catalogue, "", AlbumSort.TITLE)
        assertEquals(listOf("3", "5", "6", "2", "1", "4"), titleOrder.map { it.id })
        assertEquals(titleOrder, index.search(""))
        assertEquals(titleOrder, index.search("   "))
        assertEquals(listOf("5", "6"), index.search("dune").map { it.id })
        assertEquals(6, catalogue.size)
    }

    @Test fun aLargeCatalogueFiltersInMilliseconds() {
        val albums = List(20_000) { book("$it", "Álbum número $it", if (it % 2 == 0) "Artiste Éven" else "Odd Artist") }
        val built = System.nanoTime()
        val index = SearchIndex(albums)
        val buildMs = (System.nanoTime() - built) / 1_000_000
        val started = System.nanoTime()
        repeat(20) { assertEquals(10_000, index.search("even album").size) }
        val perQueryMs = (System.nanoTime() - started) / 1_000_000 / 20.0
        println("SearchIndex 20,000 albums: build $buildMs ms, query $perQueryMs ms")
        assertEquals(1, index.search("numero 19999").size)
        // A generous bound: the point is that it is per-keystroke cheap, not a benchmark gate.
        assertTrue("query took $perQueryMs ms", perQueryMs < 250)
    }

    @Test fun offlineOnlySearchesReadyDownloadsForThisLibraryAndNothingElse() {
        val dune = album("10", "Dune")
        val emma = album("11", "Emma")
        val streamed = album("12", "Streamed only")
        val otherServer = download(album("13", "Elsewhere"), listOf(track("a"))).let { it.copy(record = it.record.copy(server = "https://other:32400")) }
        val music = download(album("14", "Music"), listOf(track("b"))).let { it.copy(record = it.record.copy(mode = LibraryMode.MUSIC)) }
        val state = PlexTouchUiState(
            connection = connection, selectedLibraryId = "1", mode = LibraryMode.AUDIOBOOK, offlineOnly = true, query = "e",
            albums = listOf(streamed),
            downloads = listOf(download(dune, listOf(track("c"))), download(emma, listOf(track("d")), ready = false), otherServer, music),
        )
        val offline = searchInput(state)
        assertTrue(offline.offline)
        assertEquals("only the finished download for this server, mode and library", listOf("10"), offline.albums.map { it.id })
        val online = searchInput(state.copy(offlineOnly = false))
        assertEquals(listOf(streamed), online.albums)
        assertFalse(online.offline)
        assertNotEquals("offline and online are different catalogues", offline.scope, online.scope)
        assertNotEquals("modes never share a catalogue", online.scope, searchInput(state.copy(offlineOnly = false, mode = LibraryMode.MUSIC)).scope)
        assertNotEquals(online.scope, searchInput(state.copy(offlineOnly = false, selectedLibraryId = "2")).scope)
        assertEquals("typing does not change the catalogue's scope", online.scope, searchInput(state.copy(offlineOnly = false, query = "dune")).scope)
    }

    @Test fun onlyAConnectedIdleLibraryMayRefreshFromPlex() {
        val connected = PlexTouchUiState(connection = connection, selectedLibraryId = "1")
        assertTrue(shouldRefreshCatalogue(connected, loading = false))
        assertFalse("offline-only mode never touches the network", shouldRefreshCatalogue(connected.copy(offlineOnly = true), loading = false))
        assertFalse("a running load is not restarted", shouldRefreshCatalogue(connected, loading = true))
        assertFalse(shouldRefreshCatalogue(connected.copy(connection = null), loading = false))
        assertFalse(shouldRefreshCatalogue(connected.copy(selectedLibraryId = null), loading = false))
    }

    private fun input(albums: List<PlexAlbum>, query: String, scope: String = "home", offline: Boolean = false) = SearchInput(scope, albums, query, offline)

    @Test fun aSlowPassForAnOlderQueryNeverReplacesANewerOne() = runBlocking {
        val worker = ManualDispatcher()
        val search = CatalogueSearch(this, worker)
        search.submit(input(catalogue, ""))
        worker.drain(); search.settle()
        assertEquals(6, search.results.value.albums.size)

        search.submit(input(catalogue, "d"))
        yield() // the "d" pass is now waiting on the worker
        search.submit(input(catalogue, "dune m"))
        yield()
        worker.drainNewestFirst()
        search.settle()
        val results = search.results.value
        assertEquals("dune m", results.query)
        assertEquals(listOf("6"), results.albums.map { it.id })
    }

    @Test fun aRefreshWhileTypingKeepsTheListUntilTypingPausesThenUsesTheNewestQuery() = runBlocking {
        var now = 0L
        val search = CatalogueSearch(this, Dispatchers.Unconfined, typingIdleMs = 100, clock = { now })
        search.submit(input(catalogue, "dune"))
        search.settle()
        assertEquals(listOf("5", "6"), search.results.value.albums.map { it.id })

        now = 10 // typing continues
        val refreshed = listOf(book("7", "Children of Dune", "Frank Herbert")) + catalogue.filterNot { it.id == "6" }
        search.submit(input(refreshed, "dune"))
        delay(30)
        assertEquals("the refresh waits; the shown results are neither cleared nor reordered", listOf("5", "6"), search.results.value.albums.map { it.id })

        now = 20
        search.submit(input(refreshed, "dune h")) // another keystroke still filters at once, against the shown catalogue
        delay(30)
        assertEquals("dune h", search.results.value.query)
        assertEquals(listOf("5", "6"), search.results.value.albums.map { it.id })

        now = 1_000 // typing has paused
        search.settle()
        assertEquals("dune h", search.results.value.query)
        assertEquals(listOf("7", "5"), search.results.value.albums.map { it.id })
    }

    @Test fun aRefreshWhenIdleAppliesAtOnceAndAnUnchangedOneLeavesResultsAlone() = runBlocking {
        val search = CatalogueSearch(this, Dispatchers.Unconfined, typingIdleMs = 50)
        search.submit(input(catalogue, "dune"))
        search.settle()
        delay(100) // typing has paused
        val before = search.results.value
        search.submit(input(catalogue.map { it.copy() }, "dune")) // a new list with the same albums
        search.settle()
        assertSame(before, search.results.value)

        search.submit(input(catalogue.filterNot { it.id == "5" }, "dune"))
        search.settle()
        assertEquals(listOf("6"), search.results.value.albums.map { it.id })
    }

    @Test fun aNewScopeReplacesTheResultsStraightAway() = runBlocking {
        val worker = ManualDispatcher()
        val search = CatalogueSearch(this, worker, typingIdleMs = 10_000, clock = { 0 })
        search.submit(input(catalogue, "dune"))
        worker.drain(); search.settle()
        assertEquals(2, search.results.value.albums.size)

        val downloads = listOf(book("9", "Dune", "Frank Herbert"))
        search.submit(input(downloads, "dune", scope = "home-offline", offline = true))
        val cleared = search.results.value
        assertTrue("another catalogue's rows are not left on screen", cleared.albums.isEmpty())
        assertFalse("and No matches is not shown before the first pass", cleared.ready)
        yield(); worker.drain(); search.settle()
        assertEquals(listOf("9"), search.results.value.albums.map { it.id })
        assertTrue(search.results.value.offline)
        assertTrue(search.results.value.ready)
    }

    /** Runs worker tasks only when told, in an order the test picks, to force an old query's pass to finish last. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { synchronized(tasks) { tasks.addLast(block) } }
        private fun next(newest: Boolean): Runnable? = synchronized(tasks) { if (tasks.isEmpty()) null else if (newest) tasks.removeLast() else tasks.removeFirst() }
        suspend fun drain() { while (true) { yield(); (next(false) ?: return).run() } }
        suspend fun drainNewestFirst() { while (true) { yield(); (next(true) ?: return).run() } }
    }
}
