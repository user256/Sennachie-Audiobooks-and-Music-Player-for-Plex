package org.johnfegan.plextouch.data

import com.google.gson.Gson

/**
 * The serialised outbox behind one lock. Every mutation is a read-modify-write of a single JSON blob, so
 * every instance touching the same blob (player service, sync worker, ViewModel) must share [lock] or one
 * of them overwrites another's change: an acknowledged event comes back, or a fresh checkpoint is dropped.
 */
class TimelineStateStore(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val lock: Any = Any(),
) {
    private val gson = Gson()

    fun state(): TimelineSyncState = synchronized(lock) { read() }

    /** Apply [change] to the current state and persist the result atomically with respect to other instances. */
    fun update(change: (TimelineSyncState) -> TimelineSyncState): TimelineSyncState = synchronized(lock) {
        change(read()).also { save(gson.toJson(it)) }
    }

    private fun read(): TimelineSyncState = runCatching {
        gson.fromJson(load() ?: "{}", TimelineSyncState::class.java) ?: TimelineSyncState()
    }.getOrDefault(TimelineSyncState())
}
