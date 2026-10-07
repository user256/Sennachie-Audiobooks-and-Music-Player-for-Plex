package org.johnfegan.plextouch.data

import androidx.compose.runtime.Immutable
import java.security.MessageDigest
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.requireText
import org.johnfegan.plextouch.ui.uiText
import org.johnfegan.plextouch.ui.uiPlural

enum class TransferState { QUEUED, DOWNLOADING, WAITING, COMPLETE, FAILED }

data class DownloadFile(val path: String, val id: Long = 0, val verifiedBytes: Long = 0)
data class DownloadTrack(val track: PlexTrack, val file: DownloadFile)
@Immutable
data class DownloadAlbum(
    val server: String, val libraryId: String, val mode: LibraryMode, val album: PlexAlbum,
    val tracks: List<DownloadTrack>, val cover: DownloadFile? = null,
    /**
     * The account scope that downloaded it (ticket 136). Records from before 136 have none: they are adopted by the account
     * that next connects to their server through sign-in, server choice or launch restore ([OfflineDownloads.adoptLegacy]),
     * never by a Home switch, and stay hidden from every scoped account until then.
     */
    val accountScope: String? = null,
) {
    fun belongsTo(server: String?, scope: String?): Boolean = server != null && this.server == server && accountScope == scope
}
data class Transfer(val state: TransferState, val bytes: Long = 0, val total: Long = 0)
@Immutable
data class DownloadStatus(
    val record: DownloadAlbum, val tracks: List<PlexTrack>, val coverUri: String?,
    val completed: Int, val failed: Int, val waiting: Int, val bytes: Long, val total: Long,
) {
    val ready get() = tracks.isNotEmpty() && completed == tracks.size
    val active get() = !ready && completed + failed < tracks.size
    val album get() = record.album.copy(localThumb = coverUri)
    val label: UiText get() = when {
        ready -> uiText(R.string.download_ready, downloadSize(bytes))
        failed > 0 -> uiPlural(R.plurals.download_failed_some, completed, completed, tracks.size, failed)
        waiting > 0 -> uiPlural(R.plurals.download_waiting, completed, completed, tracks.size)
        else -> uiPlural(R.plurals.download_progress, completed, completed, tracks.size, downloadSize(bytes))
    }
}

interface DownloadCatalog {
    fun load(): List<DownloadAlbum>
    fun save(albums: List<DownloadAlbum>)
}

interface DownloadBackend {
    fun refresh() {}
    /** `title` is the system download notification's title: a track title, or the cover label. */
    fun enqueue(connection: PlexConnection, source: String, path: String, title: UiText): Long
    fun transfer(file: DownloadFile): Transfer?
    fun length(path: String): Long
    fun uri(path: String): String
    fun remove(file: DownloadFile)
    fun recover(path: String): Long?
}

/** Platform-free download policy: receipts are saved before/after enqueue, completed files are never requeued. */
class OfflineDownloads(private val catalog: DownloadCatalog, private val backend: DownloadBackend) {
    /** Refresh metadata without replacing transfer receipts, files, or offline artwork. */
    fun updateAlbumMetadata(server: String, libraryId: String, albums: List<PlexAlbum>) {
        val byId = albums.associateBy { it.id }
        val original = catalog.load()
        val updated = original.map { record ->
            val fresh = byId[record.album.id]
            if (record.server == server && record.libraryId == libraryId && fresh != null) record.copy(album = fresh.copy(localThumb = null)) else record
        }
        if (updated != original) catalog.save(updated)
    }

    fun snapshots(): List<DownloadStatus> {
        backend.refresh()
        val stored = catalog.load()
        val verified = stored.map { album -> album.copy(tracks = album.tracks.map { it.copy(file = reconcile(it.file)) }, cover = album.cover?.let(::reconcile)) }
        if (verified != stored) catalog.save(verified)
        return verified.map { album ->
            val states = album.tracks.map { status(it.file) }
            DownloadStatus(album,
                album.tracks.map { item -> item.track.copy(localUri = if (complete(item.file)) backend.uri(item.file.path) else null) },
                album.cover?.takeIf(::complete)?.let { backend.uri(it.path) },
                states.count { it.state == TransferState.COMPLETE }, states.count { it.state == TransferState.FAILED },
                states.count { it.state == TransferState.WAITING }, states.sumOf { it.bytes }, states.sumOf { it.total },
            )
        }
    }

    fun download(connection: PlexConnection, libraryId: String, mode: LibraryMode, album: PlexAlbum, tracks: List<PlexTrack>, accountScope: String? = null) {
        requireText(tracks.isNotEmpty()) { uiText(R.string.download_no_tracks) }
        snapshots()
        var all = catalog.load()
        val existing = all.firstOrNull { it.belongsTo(connection.serverUrl, accountScope) && it.album.id == album.id }
        // Scoped records get their own folder, so two Home users' copies of one album never share (or delete) files.
        val prefix = if (accountScope == null) downloadKey(connection.serverUrl, album.id) else downloadKey("${connection.serverUrl}\u0000$accountScope", album.id)
        var record = existing ?: DownloadAlbum(connection.serverUrl, libraryId, mode, album.copy(localThumb = null),
            tracks.map { DownloadTrack(it.copy(localUri = null), DownloadFile("$prefix/${downloadKey(it.id, "audio")}.${it.container?.takeIf { ext -> ext.matches(Regex("[a-zA-Z0-9]{1,8}")) } ?: "audio"}")) },
            album.thumb?.takeIf { it.startsWith("/") && !it.startsWith("//") }?.let { DownloadFile("$prefix/cover") }, accountScope)
        fun save() {
            all = all.filterNot { it.belongsTo(record.server, record.accountScope) && it.album.id == record.album.id } + record
            catalog.save(all)
        }
        save()
        record.tracks.indices.forEach { index ->
            val item = record.tracks[index]
            if (status(item.file).state !in setOf(TransferState.FAILED)) return@forEach
            backend.remove(item.file)
            record = record.copy(tracks = record.tracks.toMutableList().also { it[index] = item.copy(file = DownloadFile(item.file.path)) })
            save()
            // On interruption after enqueue, reconcile finds the receipt by our private destination.
            val id = backend.enqueue(connection, item.track.streamPath, item.file.path, UiText.Raw(item.track.title))
            record = record.copy(tracks = record.tracks.toMutableList().also { it[index] = item.copy(file = DownloadFile(item.file.path, id)) })
            save()
        }
        record.cover?.let { cover ->
            if (status(cover).state == TransferState.FAILED) {
                backend.remove(cover)
                record = record.copy(cover = DownloadFile(cover.path)); save()
                runCatching { backend.enqueue(connection, album.thumb!!, cover.path, uiText(R.string.download_cover_title, album.title)) }.onSuccess { id ->
                    record = record.copy(cover = DownloadFile(cover.path, id)); save()
                }
            }
        }
    }

    fun remove(server: String, albumId: String, accountScope: String? = null) {
        val all = catalog.load()
        val record = all.firstOrNull { it.belongsTo(server, accountScope) && it.album.id == albumId } ?: return
        (record.tracks.map { it.file } + listOfNotNull(record.cover)).forEach(backend::remove)
        catalog.save(all.filterNot { it.belongsTo(server, accountScope) && it.album.id == albumId })
    }

    /** Pre-136 records for [server] (no account scope) become [accountScope]'s; one already scoped there for the same album wins. */
    fun adoptLegacy(server: String, accountScope: String) {
        val all = catalog.load()
        val owned = all.filter { it.belongsTo(server, accountScope) }.mapTo(HashSet()) { it.album.id }
        val adopted = all.map { if (it.belongsTo(server, null) && it.album.id !in owned) it.copy(accountScope = accountScope) else it }
        if (adopted != all) catalog.save(adopted)
    }

    private fun reconcile(original: DownloadFile): DownloadFile {
        val file = if (original.id == 0L) original.copy(id = backend.recover(original.path) ?: 0) else original
        val transfer = backend.transfer(file)
        val length = backend.length(file.path)
        return if (transfer?.state == TransferState.COMPLETE && length > 0 && (transfer.total <= 0 || length == transfer.total)) file.copy(verifiedBytes = length)
        else if (file.verifiedBytes > 0 && length != file.verifiedBytes) file.copy(verifiedBytes = 0) else file
    }

    private fun complete(file: DownloadFile) = file.verifiedBytes > 0 && backend.length(file.path) == file.verifiedBytes

    private fun status(file: DownloadFile): Transfer {
        if (complete(file)) return Transfer(TransferState.COMPLETE, file.verifiedBytes, file.verifiedBytes)
        val transfer = backend.transfer(file)
        return if (transfer == null || transfer.state == TransferState.COMPLETE) Transfer(TransferState.FAILED) else transfer
    }
}

fun downloadKey(server: String, id: String): String = MessageDigest.getInstance("SHA-256").digest("$server\u0000$id".toByteArray()).joinToString("") { "%02x".format(it) }
fun downloadSize(bytes: Long): UiText =
    if (bytes >= 1_073_741_824) uiText(R.string.size_gb, bytes / 1_073_741_824.0) else uiText(R.string.size_mb, bytes.coerceAtLeast(0) / 1_048_576.0)
