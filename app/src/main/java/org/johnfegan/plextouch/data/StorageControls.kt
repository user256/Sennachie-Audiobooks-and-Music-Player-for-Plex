package org.johnfegan.plextouch.data

import androidx.compose.runtime.Immutable
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ticket 137: the artwork disk-cache budgets the Storage screen offers. Coil evicts least-recently-used covers once the
 * cache passes the budget. The budget is read when the image loader is built, so a new choice applies from the next app
 * start (see `PlexTouchApplication`); rebuilding the loader mid-session would put two caches on one folder.
 */
enum class ArtworkBudget(val megabytes: Int) {
    MB_50(50), MB_100(100), MB_250(250), MB_500(500);

    val bytes: Long get() = megabytes * 1024L * 1024L

    companion object {
        /** Coil's own default tops out at 250 MB, so existing installs keep the cache they had. */
        val DEFAULT = MB_250
        /** A stored value that is not one of the choices (an old or hand-edited preference) falls back to [DEFAULT]. */
        fun fromMegabytes(value: Int): ArtworkBudget = entries.firstOrNull { it.megabytes == value } ?: DEFAULT
    }
}

/** What the Storage screen shows: three separate figures, never added together. */
@Immutable
data class StorageReport(val downloadBytes: Long = 0, val artworkBytes: Long = 0, val metadataBytes: Long = 0)

/** The artwork disk cache, as the Storage screen measures and clears it. */
interface ArtworkCacheGateway {
    /** The cache folder, or null when the image loader has no disk cache. */
    val directory: File?
    fun bytes(): Long
    fun clear()
}

/**
 * Coil's disk cache. Its journal tracks the size, and clearing goes through the cache itself: an entry being written is
 * detached (that write is discarded) and an entry being read is only removed once its reader closes, so neither an
 * in-use file nor a half-written entry can result. Local download covers are `file://` images that Coil never copies
 * into its disk cache, so they are not in this folder.
 */
class CoilArtworkCache(private val cache: () -> DiskCache?) : ArtworkCacheGateway {
    override val directory: File? get() = cache()?.directory?.toFile()
    override fun bytes(): Long = cache()?.size ?: 0
    override fun clear() { cache()?.clear() }
}

/** Bytes of downloads whose files were verified complete; partial and failed transfers count for nothing. */
fun verifiedDownloadBytes(albums: List<DownloadAlbum>): Long =
    albums.sumOf { album -> album.tracks.sumOf { it.file.verifiedBytes.coerceAtLeast(0) } + (album.cover?.verifiedBytes?.coerceAtLeast(0) ?: 0) }

/**
 * True when [cache] can be cleared without reaching a protected folder: it must not be, sit inside, or contain any of
 * [protectedRoots] (the offline downloads, the app's files and its preferences, which hold credentials, listening history
 * and the timeline outbox).
 */
fun isSafeCacheTarget(cache: File, protectedRoots: List<File>): Boolean {
    val target = runCatching { cache.canonicalFile }.getOrNull() ?: return false
    return protectedRoots.none { root ->
        val guarded = runCatching { root.canonicalFile }.getOrNull() ?: return@none false
        target == guarded || target.startsWith(guarded) || guarded.startsWith(target)
    }
}

/**
 * Measures and clears the disposable caches. Every scan and delete runs on [io], never the main thread. Downloads are
 * only measured here; removing one stays on the Downloads screen with its confirmation and playback safety.
 */
class StorageControls(
    private val metadata: PlexCache,
    private val artwork: ArtworkCacheGateway,
    private val protectedRoots: () -> List<File>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun measure(downloads: List<DownloadAlbum>): StorageReport = withContext(io) {
        StorageReport(
            downloadBytes = verifiedDownloadBytes(downloads),
            artworkBytes = runCatching { artwork.bytes() }.getOrDefault(0),
            metadataBytes = runCatching { metadata.bytes() }.getOrDefault(0),
        )
    }

    /** False when nothing was cleared: the cache folder overlaps a protected folder, or the cache refused. */
    suspend fun clearArtwork(): Boolean = withContext(io) {
        val folder = artwork.directory ?: return@withContext true
        isSafeCacheTarget(folder, protectedRoots()) && runCatching { artwork.clear() }.isSuccess
    }

    /** Saved Plex metadata only; the app refetches it on the next browse. False when it could not all be cleared safely. */
    suspend fun clearMetadata(): Boolean = withContext(io) {
        isSafeCacheTarget(metadata.root, protectedRoots()) && metadata.clear()
    }
}
