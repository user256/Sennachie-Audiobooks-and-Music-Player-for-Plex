package org.johnfegan.plextouch.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlexCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val connection = PlexConnection("http://home", "private-token")

    @Test fun persistsSeparatesAccountsAndExpiresFreshnessWithoutDiscardingSavedData() {
        var now = System.currentTimeMillis()
        val root = temporary.newFolder()
        val cache = PlexCache(root) { now }
        cache.write(connection, "/library/sections", "{\"items\":[]}")
        assertEquals("{\"items\":[]}", PlexCache(root) { now }.read(connection, "/library/sections", true))
        assertTrue(cache.entry(connection, "/library/sections")!!.fresh)
        assertNull(cache.read(connection.copy(token = "other"), "/library/sections"))
        assertNull(cache.read(connection.copy(serverUrl = "http://other"), "/library/sections"))
        now += PlexCache.TTL_MS + 1
        assertNull(cache.read(connection, "/library/sections", true))
        assertNotNull(cache.read(connection, "/library/sections"))
        assertFalse(cache.entry(connection, "/library/sections")!!.fresh)
        assertFalse(root.listFiles()!!.any { it.name.contains("private-token") || it.readText().contains("private-token") })
        cache.invalidate(connection, "/library/sections")
        assertNull(cache.read(connection, "/library/sections"))
    }

    @Test fun boundsEntryCountAndRejectsOversizedEntries() {
        var now = System.currentTimeMillis()
        val root = temporary.newFolder()
        val cache = PlexCache(root) { now++ }
        repeat(105) { cache.write(connection, "/item/$it", "{}") }
        assertEquals(100, root.listFiles()!!.size)
        assertNull(cache.read(connection, "/item/0"))
        cache.write(connection, "/oversized", "x".repeat(PlexCache.MAX_BYTES.toInt() + 1))
        assertNull(cache.read(connection, "/oversized"))
    }

    @Test fun keepsOnlyTheMostRecentlyOpenedPlaylistsPerAccount() {
        var now = System.currentTimeMillis()
        val root = temporary.newFolder()
        val cache = PlexCache(root) { now++ }
        val other = connection.copy(token = "other-account")
        cache.write(connection, "/library/metadata/5/children?includeChapters=1", "{}")
        cache.write(other, "/playlists/1/items", "{}")
        repeat(PlexCache.PLAYLIST_LIMIT + 5) { cache.write(connection, "/playlists/$it/items", "{\"n\":$it}") }
        (0 until 5).forEach { assertNull("playlist $it is the oldest and evicted", cache.read(connection, "/playlists/$it/items")) }
        (5 until PlexCache.PLAYLIST_LIMIT + 5).forEach { assertEquals("{\"n\":$it}", cache.read(connection, "/playlists/$it/items")) }
        assertNotNull("album track lists do not count towards the playlist bound", cache.read(connection, "/library/metadata/5/children?includeChapters=1"))
        assertNotNull("another account's playlists are bounded separately", cache.read(other, "/playlists/1/items"))
        assertTrue(cache.entry(connection, "/playlists/${PlexCache.PLAYLIST_LIMIT + 4}/items")!!.fresh)
        assertFalse(root.listFiles()!!.any { it.name.contains("private-token") })
        cache.invalidate(connection, "/playlists/6/items")
        assertNull(cache.read(connection, "/playlists/6/items"))
    }
}
