package org.johnfegan.plextouch.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded, disposable metadata cache. Namespace includes an opaque account fingerprint. Every entry counts towards the
 * overall bound ([MAX_ENTRIES] files, [MAX_BYTES]); playlist item lists are also capped at the [PLAYLIST_LIMIT] most
 * recently written per server and account, so a music user's handful of recent playlists stay warm without crowding out
 * album and catalogue entries.
 *
 * Every instance on the same folder shares one lock (the app's gateways and the Storage screen each hold one), so a clear
 * can never land between a write's temporary file and its rename. Readers treat a missing file as a miss.
 */
class PlexCache(val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val value: String, val fresh: Boolean)

    private val lock: Any = locks.getOrPut(root.absoluteFile.normalize().path) { Any() }

    private fun namespace(connection: PlexConnection) = downloadKey(connection.serverUrl, connection.token)
    private fun playlistPrefix(connection: PlexConnection) = "$PLAYLIST_GROUP.${namespace(connection).take(16)}."
    private fun file(connection: PlexConnection, path: String): File {
        val key = downloadKey(namespace(connection), path) + ".json"
        return File(root, if (isPlaylistItems(path)) playlistPrefix(connection) + key else key)
    }

    fun entry(connection: PlexConnection, path: String): Entry? = synchronized(lock) { runCatching {
        val file = file(connection, path)
        if (!file.isFile || file.length() > MAX_BYTES) null
        else file.readText().takeIf { it.isNotBlank() }?.let { Entry(it, clock() - file.lastModified() in 0..TTL_MS) }
    }.getOrNull() }

    fun read(connection: PlexConnection, path: String, freshOnly: Boolean = false): String? =
        entry(connection, path)?.takeIf { !freshOnly || it.fresh }?.value

    fun write(connection: PlexConnection, path: String, value: String): Unit = synchronized(lock) {
        runCatching {
            if (value.toByteArray().size > MAX_BYTES) return
            root.mkdirs()
            val destination = file(connection, path)
            val temporary = File(root, destination.name + ".tmp")
            temporary.writeText(value)
            if (!temporary.renameTo(destination)) { temporary.delete(); return }
            destination.setLastModified(clock())
            if (isPlaylistItems(path)) {
                val prefix = playlistPrefix(connection)
                root.listFiles { f -> f.extension == "json" && f.name.startsWith(prefix) }.orEmpty()
                    .sortedByDescending { it.lastModified() }.drop(PLAYLIST_LIMIT).forEach { it.delete() }
            }
            val entries = root.listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }
            var bytes = 0L
            entries.forEachIndexed { index, entry ->
                bytes += entry.length()
                if (index >= MAX_ENTRIES || bytes > MAX_BYTES) entry.delete()
            }
        }
    }

    fun invalidate(connection: PlexConnection, path: String): Unit = synchronized(lock) { file(connection, path).delete() }

    /** Bytes of saved entries (ticket 137). Temporary files are a write in progress, so they are not counted. */
    fun bytes(): Long = synchronized(lock) { entries().sumOf { it.length() } }

    /**
     * Deletes every saved entry and any temporary file a crashed write left behind (ticket 137). It holds the write lock,
     * so no write is mid-way; each delete is a single unlink, and a reader that finds the file gone treats it as a miss.
     * Only this folder's `.json` and `.tmp` files are touched. Returns false if something could not be deleted.
     */
    fun clear(): Boolean = synchronized(lock) {
        root.listFiles { f -> f.isFile && (f.extension == "json" || f.extension == "tmp") }.orEmpty()
            .map { it.delete() || !it.exists() }.all { it }
    }

    private fun entries(): List<File> = root.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().toList()

    companion object {
        const val TTL_MS = 300_000L
        const val MAX_BYTES = 20 * 1024 * 1024L
        const val MAX_ENTRIES = 100
        /** Recently opened playlist item lists kept per server and account, inside the overall bound. */
        const val PLAYLIST_LIMIT = 20
        private const val PLAYLIST_GROUP = "playlist"
        private val PLAYLIST_ITEMS = Regex("/playlists/[0-9]+/items")
        private fun isPlaylistItems(path: String) = PLAYLIST_ITEMS.matches(path)
        /** The folder under the app's cache directory. */
        const val DIRECTORY = "plex_metadata"
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
