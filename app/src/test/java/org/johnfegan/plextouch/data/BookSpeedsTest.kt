package org.johnfegan.plextouch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSpeedsTest {
    private val server = "https://plex.lan:32400"
    private val prefs = mutableMapOf<String, String>()
    private var now = 1_000L
    private fun store() = BookSpeedStore({ prefs["book_speeds"] }, { prefs["book_speeds"] = it }, clock = { now++ })
    private val scope = PlexConnection(server, "server-token").progressScope("account-token")

    @Test fun aBookOverrideWinsAndOtherBooksKeepTheDefault() {
        val store = store()
        store.save(scope, server, "10", 1.5f)
        assertEquals(1.5f, BookSpeeds.effective(LibraryMode.AUDIOBOOK, store.speed(scope, server, "10"), 1.25f))
        assertEquals("a new book follows the default", 1.25f, BookSpeeds.effective(LibraryMode.AUDIOBOOK, store.speed(scope, server, "11"), 1.25f))
        assertEquals("music has no per-album speed", 1f, BookSpeeds.effective(LibraryMode.MUSIC, 1.5f, 1f))
        assertEquals("out-of-range values are clamped", PlexStore.MAX_SPEED, BookSpeeds.effective(LibraryMode.AUDIOBOOK, 9f, 1f))
    }

    @Test fun choosingTheDefaultClearsTheOverride() {
        assertNull(BookSpeeds.overrideFor(1.25f, 1.25f))
        assertEquals(1.75f, BookSpeeds.overrideFor(1.75f, 1.25f))
    }

    @Test fun resetForgetsOnlyThatBook() {
        val store = store()
        store.save(scope, server, "10", 1.5f)
        store.save(scope, server, "11", 2f)
        store.save(scope, server, "10", null)
        assertNull(store.speed(scope, server, "10"))
        assertEquals(2f, store.speed(scope, server, "11"))
    }

    @Test fun overridesSurviveARestartThroughThePreferenceValue() {
        store().save(scope, "$server/", "10", 1.5f)
        val reopened = store()
        assertEquals("a trailing slash names the same server", 1.5f, reopened.speed(scope, server, "10"))
        assertEquals(listOf("10"), reopened.entries().map { it.albumId })
        assertFalse("no token is written", prefs.values.any { it.contains("server-token") || it.contains("account-token") })
    }

    @Test fun anotherAccountOrATokenReplacedServerDoesNotInheritTheSpeed() {
        val store = store()
        store.save(scope, server, "10", 1.5f)
        val otherAccount = PlexConnection(server, "server-token").progressScope("someone-else")
        val replacedToken = PlexConnection(server, "new-server-token").progressScope()
        val unlinkedOriginal = PlexConnection(server, "server-token").progressScope()
        listOf(otherAccount, replacedToken, unlinkedOriginal).forEach { assertNull(store.speed(it, server, "10")) }
        assertNull("another server's album with the same id", store.speed(scope, "https://other.lan:32400", "10"))
        assertEquals("the same Plex account behind a refreshed server token keeps it",
            1.5f, store.speed(PlexConnection(server, "new-server-token").progressScope("account-token"), server, "10"))
    }

    @Test fun theStoreKeepsTheMostRecentlyChangedTwoHundredBooks() {
        var entries = emptyList<BookSpeed>()
        repeat(BookSpeeds.MAX_BOOKS + 5) { entries = BookSpeeds.set(entries, scope, server, "$it", 1.5f, it.toLong()) }
        assertEquals(BookSpeeds.MAX_BOOKS, entries.size)
        assertNull(BookSpeeds.find(entries, scope, server, "0"))
        assertEquals(1.5f, BookSpeeds.find(entries, scope, server, "5"))
        // Changing an old book refreshes it, so the next eviction takes the following one instead.
        entries = BookSpeeds.set(entries, scope, server, "5", 2f, 1_000)
        entries = BookSpeeds.set(entries, scope, server, "new", 1.25f, 1_001)
        assertEquals(2f, BookSpeeds.find(entries, scope, server, "5"))
        assertNull(BookSpeeds.find(entries, scope, server, "6"))
        assertEquals(BookSpeeds.MAX_BOOKS, entries.size)
    }

    @Test fun anUnreadableValueMeansNoOverrides() {
        prefs["book_speeds"] = "{not json"
        assertTrue(store().entries().isEmpty())
        prefs["book_speeds"] = """[{"scope":null,"server":"x","albumId":"1","speed":1.5,"usedAt":1}]"""
        assertTrue(store().entries().isEmpty())
    }
}
