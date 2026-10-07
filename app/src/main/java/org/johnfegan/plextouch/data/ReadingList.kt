package org.johnfegan.plextouch.data

import androidx.compose.runtime.Immutable
import com.google.gson.Gson

/**
 * A book the listener saved to read next (ticket 131). The album is a snapshot so the list still has a title and author to
 * show when the catalogue is not loaded (offline, or before Plex answers). Device-local artwork paths are not kept.
 */
@Immutable
data class ReadingListEntry(val album: PlexAlbum, val addedAt: Long)

/** What adding a title did. */
enum class ReadingListAdd { ADDED, ALREADY_LISTED, FULL }

/**
 * The phone-local reading lists: one ordered list per account scope and server, never shared between accounts and never
 * written to Plex. Pure functions, so the bounds and ordering are unit-tested without Android.
 */
object ReadingLists {
    /** Titles kept per account and server; adding to a full list is refused rather than silently dropping an older title. */
    const val MAX_ITEMS = 100
    /** Account/server lists kept on the phone; the least recently changed list beyond this is dropped. */
    const val MAX_LISTS = 10

    data class Saved(val scope: String, val server: String, val entries: List<ReadingListEntry>, val updatedAt: Long)
    data class State(val lists: List<Saved> = emptyList())

    fun entries(state: State, scope: String, server: String): List<ReadingListEntry> =
        state.lists.firstOrNull { it.scope == scope && it.server == server }?.entries.orEmpty()

    fun add(entries: List<ReadingListEntry>, album: PlexAlbum, now: Long): Pair<List<ReadingListEntry>, ReadingListAdd> = when {
        album.id.startsWith("playlist:") -> entries to ReadingListAdd.ALREADY_LISTED
        entries.any { it.album.id == album.id } -> entries to ReadingListAdd.ALREADY_LISTED
        entries.size >= MAX_ITEMS -> entries to ReadingListAdd.FULL
        else -> (entries + ReadingListEntry(album.copy(localThumb = null), now)) to ReadingListAdd.ADDED
    }

    fun remove(entries: List<ReadingListEntry>, albumId: String): List<ReadingListEntry> = entries.filterNot { it.album.id == albumId }

    /** Moves one entry up (`direction` -1) or down (+1); a move past either end leaves the list unchanged. */
    fun move(entries: List<ReadingListEntry>, albumId: String, direction: Int): List<ReadingListEntry> {
        val from = entries.indexOfFirst { it.album.id == albumId }
        val to = from + direction
        if (from < 0 || to !in entries.indices) return entries
        return entries.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Stores `entries` for one account and server; an emptied list is removed, and at most [MAX_LISTS] lists are kept. */
    fun put(state: State, scope: String, server: String, entries: List<ReadingListEntry>, now: Long): State {
        val others = state.lists.filterNot { it.scope == scope && it.server == server }
        val updated = if (entries.isEmpty()) others else others + Saved(scope, server, entries.distinctBy { it.album.id }.take(MAX_ITEMS), now)
        return State(updated.sortedByDescending { it.updatedAt }.take(MAX_LISTS))
    }

    /** Gson can hand back nulls for fields a damaged or older file lacks; those lists and entries are dropped. */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun sanitise(state: State?): State = State((state?.lists ?: emptyList()).filter { it != null && it.scope != null && it.server != null }
        .map { saved -> saved.copy(entries = (saved.entries ?: emptyList()).filter { it != null && it.album != null && it.album.id != null }) })
}

/** The reading-list slice of the phone store. Every change is a read-modify-write under the store's one lock. */
interface ReadingListStore {
    fun readingList(scope: String, server: String): List<ReadingListEntry>
    /** Applies `change` to the current list and saves it; returns the saved list. */
    fun updateReadingList(scope: String, server: String, change: (List<ReadingListEntry>) -> List<ReadingListEntry>): List<ReadingListEntry>
}

/** Persists [ReadingLists.State] as JSON through `load`/`save` (a preference entry in [PlexStore]) under `lock`. */
class ReadingListStateStore(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val lock: Any,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadingListStore {
    private val gson = Gson()

    private fun state(): ReadingLists.State =
        ReadingLists.sanitise(runCatching { gson.fromJson(load(), ReadingLists.State::class.java) }.getOrNull())

    override fun readingList(scope: String, server: String): List<ReadingListEntry> = synchronized(lock) { ReadingLists.entries(state(), scope, server) }

    override fun updateReadingList(scope: String, server: String, change: (List<ReadingListEntry>) -> List<ReadingListEntry>): List<ReadingListEntry> = synchronized(lock) {
        val current = state()
        val before = ReadingLists.entries(current, scope, server)
        val after = change(before)
        if (after == before) return@synchronized before
        val next = ReadingLists.put(current, scope, server, after, clock())
        save(gson.toJson(next))
        ReadingLists.entries(next, scope, server)
    }
}

/** For services built without a [PlexStore] (JVM tests): the same rules, kept in memory. */
class MemoryReadingListStore(clock: () -> Long = System::currentTimeMillis) : ReadingListStore {
    private var json: String? = null
    private val store = ReadingListStateStore({ json }, { json = it }, Any(), clock)
    override fun readingList(scope: String, server: String) = store.readingList(scope, server)
    override fun updateReadingList(scope: String, server: String, change: (List<ReadingListEntry>) -> List<ReadingListEntry>) = store.updateReadingList(scope, server, change)
}
