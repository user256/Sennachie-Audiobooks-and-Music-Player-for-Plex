package org.johnfegan.plextouch.data

import com.google.gson.Gson

/**
 * One audiobook's saved speed. [scope] is the account fingerprint from [progressScope] (a hash of the server address and
 * the account or server token, never a token), so a different account, or a server whose token was replaced without a
 * linked Plex account, never inherits another listener's values. [usedAt] orders the least-recently-used eviction.
 */
data class BookSpeed(val scope: String, val server: String, val albumId: String, val speed: Float, val usedAt: Long)

/** The pure rules for per-book speed overrides: precedence, reset and the bound. */
object BookSpeeds {
    /** Enough for a large personal library; the least recently changed book is forgotten first. */
    const val MAX_BOOKS = 200

    private fun same(entry: BookSpeed, scope: String, server: String, albumId: String) =
        entry.scope == scope && entry.server == server.trimEnd('/') && entry.albumId == albumId

    fun find(entries: List<BookSpeed>, scope: String, server: String, albumId: String): Float? =
        entries.lastOrNull { same(it, scope, server, albumId) }?.speed?.coerceIn(PlexStore.MIN_SPEED, PlexStore.MAX_SPEED)

    /** A null [speed] resets the book to the default. The newest entry goes last and only [MAX_BOOKS] are kept. */
    fun set(entries: List<BookSpeed>, scope: String, server: String, albumId: String, speed: Float?, now: Long): List<BookSpeed> {
        val others = entries.filterNot { same(it, scope, server, albumId) }
        if (speed == null) return others
        return (others + BookSpeed(scope, server.trimEnd('/'), albumId, speed.coerceIn(PlexStore.MIN_SPEED, PlexStore.MAX_SPEED), now))
            .sortedBy { it.usedAt }.takeLast(MAX_BOOKS)
    }

    /**
     * The speed a queue starts at: an audiobook's own override, else the user's default for that mode (ticket 117). Music
     * has no per-album speeds.
     */
    fun effective(mode: LibraryMode, override: Float?, default: Float): Float =
        (if (mode == LibraryMode.AUDIOBOOK) override ?: default else default).coerceIn(PlexStore.MIN_SPEED, PlexStore.MAX_SPEED)

    /**
     * What choosing [chosen] in the player saves for a book: picking the default clears the override, so a later change of
     * the default still reaches this book; anything else becomes the book's own speed.
     */
    fun overrideFor(chosen: Float, default: Float): Float? = chosen.coerceIn(PlexStore.MIN_SPEED, PlexStore.MAX_SPEED).takeIf { it != default }
}

/** The serialised overrides behind the store's process-wide lock, like [TimelineStateStore]. */
class BookSpeedStore(
    private val load: () -> String?,
    private val write: (String) -> Unit,
    private val lock: Any = Any(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val gson = Gson()

    fun speed(scope: String, server: String, albumId: String): Float? = synchronized(lock) { BookSpeeds.find(read(), scope, server, albumId) }

    fun save(scope: String, server: String, albumId: String, speed: Float?) = synchronized(lock) {
        write(gson.toJson(BookSpeeds.set(read(), scope, server, albumId, speed, clock()).toTypedArray()))
    }

    fun entries(): List<BookSpeed> = synchronized(lock) { read() }

    private fun read(): List<BookSpeed> = runCatching {
        gson.fromJson(load() ?: "[]", Array<BookSpeed>::class.java)?.toList().orEmpty()
            .filter { entry -> runCatching { entry.scope.isNotBlank() && entry.server.isNotBlank() && entry.albumId.isNotBlank() && !entry.speed.isNaN() }.getOrDefault(false) }
    }.getOrDefault(emptyList())
}
