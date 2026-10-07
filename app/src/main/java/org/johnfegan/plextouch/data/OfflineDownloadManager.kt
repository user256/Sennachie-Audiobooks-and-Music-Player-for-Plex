package org.johnfegan.plextouch.data

import android.app.DownloadManager
import android.content.Context
import org.johnfegan.plextouch.R
import android.net.Uri
import android.os.Environment
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.asString
import org.johnfegan.plextouch.ui.checkText
import org.johnfegan.plextouch.ui.requireText
import org.johnfegan.plextouch.ui.uiText

/** Android's durable transfer queue; metadata contains no Plex tokens or authenticated URLs. */
class OfflineDownloadManager(context: Context) : DownloadsGateway {
    private val app = context.applicationContext
    private val manager = app.getSystemService(DownloadManager::class.java)
    private val prefs = app.getSharedPreferences("offline_catalog", Context.MODE_PRIVATE)
    private val gson = Gson()
    // Process-wide: the app and the widget (ticket 139) each hold a manager, and both read-modify-write one catalogue.
    private val mutex = catalogLock
    private val root get() = requireNotNull(offlineRoot(app))
    /** Keeps downloads out of gallery and music scanners; failing to write the marker must never block a download. */
    private fun markNoMedia() { runCatching { File(root, ".nomedia").takeUnless { it.exists() }?.createNewFile() } }
    private fun file(path: String): File {
        val result = File(root, path).canonicalFile
        requireText(result.path.startsWith(root.path + File.separator)) { uiText(R.string.download_invalid_destination) }
        return result
    }
    private val engine = OfflineDownloads(object : DownloadCatalog {
        override fun load(): List<DownloadAlbum> = gson.fromJson(prefs.getString("albums", "[]"), Array<DownloadAlbum>::class.java).toList()
        override fun save(albums: List<DownloadAlbum>) {
            checkText(prefs.edit().putString("albums", gson.toJson(albums)).commit()) { uiText(R.string.download_catalogue_save_failed) }
        }
    }, object : DownloadBackend {
        private val transfers = mutableMapOf<Long, Transfer>()
        private val destinations = mutableMapOf<String, Long>()
        override fun refresh() {
            transfers.clear(); destinations.clear()
            manager.query(DownloadManager.Query())?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.let { destinations[it] = id }
                    val state = when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_PENDING -> TransferState.QUEUED
                        DownloadManager.STATUS_RUNNING -> TransferState.DOWNLOADING
                        DownloadManager.STATUS_PAUSED -> TransferState.WAITING
                        DownloadManager.STATUS_SUCCESSFUL -> TransferState.COMPLETE
                        else -> TransferState.FAILED
                    }
                    transfers[id] = Transfer(state, cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0))
                }
            }
        }
        override fun enqueue(connection: PlexConnection, source: String, path: String, title: UiText): Long {
            require(source.startsWith("/") && !source.startsWith("//"))
            val destination = file(path)
            check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
            markNoMedia()
            val request = DownloadManager.Request(Uri.parse(connection.serverUrl.trimEnd('/') + source))
                .addRequestHeader("X-Plex-Token", connection.token)
                .setTitle(title.asString(app)).setDescription(app.getString(R.string.download_description, app.getString(R.string.app_name)))
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destination))
            return manager.enqueue(request)
        }
        override fun transfer(file: DownloadFile): Transfer? = transfers[file.id]
        override fun length(path: String) = file(path).takeIf { it.isFile }?.length() ?: 0
        override fun uri(path: String) = Uri.fromFile(file(path)).toString()
        override fun remove(file: DownloadFile) {
            val id = file.id.takeIf { it != 0L } ?: recover(file.path)
            id?.let { manager.remove(it) }
            val destination = file(file.path)
            checkText(!destination.exists() || destination.delete()) { uiText(R.string.download_remove_local_failed) }
        }
        override fun recover(path: String): Long? = destinations[uri(path)]
    })

    override suspend fun snapshots(): List<DownloadStatus> = withContext(Dispatchers.IO) { mutex.withLock { engine.snapshots() } }
    override suspend fun updateAlbumMetadata(server: String, libraryId: String, albums: List<PlexAlbum>) =
        withContext(Dispatchers.IO) { mutex.withLock { engine.updateAlbumMetadata(server, libraryId, albums) } }
    override suspend fun download(connection: PlexConnection, libraryId: String, mode: LibraryMode, album: PlexAlbum, tracks: List<PlexTrack>, accountScope: String?) =
        withContext(Dispatchers.IO) { mutex.withLock { engine.download(connection, libraryId, mode, album, tracks, accountScope) } }
    override suspend fun remove(server: String, albumId: String, accountScope: String?) = withContext(Dispatchers.IO) { mutex.withLock { engine.remove(server, albumId, accountScope) } }
    override suspend fun adoptLegacy(server: String, accountScope: String) = withContext(Dispatchers.IO) { mutex.withLock { engine.adoptLegacy(server, accountScope) } }

    companion object {
        /** Where offline audio and covers live; the Storage screen (ticket 137) guards it from every cache clear. */
        fun offlineRoot(context: Context): File? = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { File(it, "offline").canonicalFile }
    }
}

/** Serialises every [OfflineDownloadManager] instance in the process over the shared catalogue preferences. */
private val catalogLock = Mutex()
