package org.johnfegan.plextouch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 131: the local, account- and server-scoped reading list. */
class ReadingListTest {
    private fun book(id: String) = PlexAlbum(id, "Book $id", "Author", 2001, 3, thumb = "/t/$id", localThumb = "file:///covers/$id.jpg")
    private fun ids(entries: List<ReadingListEntry>) = entries.map { it.album.id }
    private fun listOf(vararg ids: String) = ids.fold(emptyList<ReadingListEntry>()) { list, id -> ReadingLists.add(list, book(id), 1).first }

    @Test fun addKeepsOrderRefusesRepeatsAndDropsDeviceArtwork() {
        val (one, first) = ReadingLists.add(emptyList(), book("1"), 10)
        val (two, second) = ReadingLists.add(one, book("2"), 20)
        val (again, repeat) = ReadingLists.add(two, book("1"), 30)
        assertEquals(ReadingListAdd.ADDED, first)
        assertEquals(ReadingListAdd.ADDED, second)
        assertEquals(ReadingListAdd.ALREADY_LISTED, repeat)
        assertEquals(listOf("1", "2").map { it.album.id }, ids(again))
        assertNull("a device-local cover path is not saved", again.first().album.localThumb)
        assertEquals("/t/1", again.first().album.thumb)
    }

    @Test fun playlistsAreNotBooks() {
        val (list, result) = ReadingLists.add(emptyList(), PlexAlbum("playlist:4", "Mix", "", null, 1), 1)
        assertTrue(list.isEmpty())
        assertEquals(ReadingListAdd.ALREADY_LISTED, result)
    }

    @Test fun aFullListRefusesRatherThanDroppingATitle() {
        val full = (1..ReadingLists.MAX_ITEMS).fold(emptyList<ReadingListEntry>()) { list, id -> ReadingLists.add(list, book("$id"), 1).first }
        val (after, result) = ReadingLists.add(full, book("999"), 2)
        assertEquals(ReadingListAdd.FULL, result)
        assertEquals(full, after)
        assertEquals(ReadingLists.MAX_ITEMS, after.size)
    }

    @Test fun removeAndReorder() {
        val list = listOf("1", "2", "3")
        assertEquals(kotlin.collections.listOf("1", "3"), ids(ReadingLists.remove(list, "2")))
        assertEquals(kotlin.collections.listOf("2", "1", "3"), ids(ReadingLists.move(list, "2", -1)))
        assertEquals(kotlin.collections.listOf("1", "3", "2"), ids(ReadingLists.move(list, "2", 1)))
        assertEquals("past the top is a no-op", list, ReadingLists.move(list, "1", -1))
        assertEquals("past the bottom is a no-op", list, ReadingLists.move(list, "3", 1))
        assertEquals("an unknown title is a no-op", list, ReadingLists.move(list, "9", 1))
    }

    @Test fun listsAreScopedByAccountAndServer() {
        var state = ReadingLists.put(ReadingLists.State(), "alice", "https://a", listOf("1"), 1)
        state = ReadingLists.put(state, "bob", "https://a", listOf("2"), 2)
        state = ReadingLists.put(state, "alice", "https://b", listOf("3"), 3)
        assertEquals(kotlin.collections.listOf("1"), ids(ReadingLists.entries(state, "alice", "https://a")))
        assertEquals(kotlin.collections.listOf("2"), ids(ReadingLists.entries(state, "bob", "https://a")))
        assertEquals(kotlin.collections.listOf("3"), ids(ReadingLists.entries(state, "alice", "https://b")))
        assertTrue(ReadingLists.entries(state, "bob", "https://b").isEmpty())
    }

    @Test fun storedListsAreBoundedAndAnEmptiedListIsDropped() {
        val state = (1..ReadingLists.MAX_LISTS + 3).fold(ReadingLists.State()) { state, n -> ReadingLists.put(state, "scope$n", "https://s", listOf("$n"), n.toLong()) }
        assertEquals(ReadingLists.MAX_LISTS, state.lists.size)
        assertTrue("the least recently changed lists go first", ReadingLists.entries(state, "scope1", "https://s").isEmpty())
        assertEquals(kotlin.collections.listOf("${ReadingLists.MAX_LISTS + 3}"), ids(ReadingLists.entries(state, "scope${ReadingLists.MAX_LISTS + 3}", "https://s")))
        val emptied = ReadingLists.put(state, "scope5", "https://s", emptyList(), 99)
        assertEquals(ReadingLists.MAX_LISTS - 1, emptied.lists.size)
    }

    @Test fun theStateStorePersistsUnderItsLockAndSurvivesDamage() {
        var json: String? = null
        var saves = 0
        val store = ReadingListStateStore({ json }, { json = it; saves++ }, Any()) { 5 }
        store.updateReadingList("alice", "https://a") { ReadingLists.add(it, book("1"), 1).first }
        store.updateReadingList("alice", "https://a") { ReadingLists.add(it, book("2"), 2).first }
        store.updateReadingList("alice", "https://a") { ReadingLists.move(it, "2", -1) }
        assertEquals(3, saves)
        val reopened = ReadingListStateStore({ json }, { json = it }, Any())
        assertEquals(kotlin.collections.listOf("2", "1"), ids(reopened.readingList("alice", "https://a")))
        assertTrue("another account sees nothing", reopened.readingList("bob", "https://a").isEmpty())
        store.updateReadingList("alice", "https://a") { it }
        assertEquals("an unchanged list is not rewritten", 3, saves)

        json = "{not json"
        assertTrue(reopened.readingList("alice", "https://a").isEmpty())
        json = """{"lists":[{"scope":"alice","server":"https://a","entries":[null,{"album":null},{"album":{"id":"7","title":"Kept"},"addedAt":1}]},{"server":"x"}]}"""
        assertEquals("damaged entries are dropped, readable ones kept", kotlin.collections.listOf("7"), ids(reopened.readingList("alice", "https://a")))
    }

    @Test fun signOutKeepsTheAccountScopedReadingList() {
        val kept = PlexStore.keptAfterSignOut(mapOf("reading_list" to "{}", "token" to "secret"))
        assertEquals(setOf("reading_list"), kept.keys)
    }

    @Test fun theMemoryStoreFollowsTheSameRules() {
        val store = MemoryReadingListStore()
        store.updateReadingList("alice", "https://a") { ReadingLists.add(it, book("1"), 1).first }
        assertEquals(kotlin.collections.listOf("1"), ids(store.readingList("alice", "https://a")))
        assertTrue(store.readingList("alice", "https://b").isEmpty())
    }
}
