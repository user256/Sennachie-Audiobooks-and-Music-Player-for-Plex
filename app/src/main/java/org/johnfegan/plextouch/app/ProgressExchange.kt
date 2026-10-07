package org.johnfegan.plextouch.app

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import org.johnfegan.plextouch.data.AudiobookProgressExchange
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PhoneCheckpoint
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexChapterProgress
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.data.PlexHttpException
import org.johnfegan.plextouch.data.ProgressStore
import org.johnfegan.plextouch.data.belongsToScope
import org.johnfegan.plextouch.data.effectiveSpeed
import org.johnfegan.plextouch.data.phoneCheckpoint
import org.johnfegan.plextouch.player.PlaybackController
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText
import org.johnfegan.plextouch.ui.checkText
import org.johnfegan.plextouch.ui.userText

/** Plex's per-chapter offsets for one book beside the phone's own checkpoint (or why there is none). */
@Immutable
data class ProgressComparison(
    val album: PlexAlbum,
    val connection: PlexConnection,
    val chapters: List<PlexChapterProgress>,
    val phone: PhoneCheckpoint?,
    val phoneNotice: UiText?,
)

/** What an exchange must see unchanged from the moment it starts to the moment it writes. */
data class ProgressContext(
    val connection: PlexConnection?,
    val mode: LibraryMode,
    val selectedAlbumId: String?,
    val accountScope: String?,
    val offlineOnly: Boolean,
    val playing: Boolean,
    val buffering: Boolean,
    val playerReady: Boolean,
)

/** Only a paused, online phone showing that very book on that very connection may read or write its Plex position. */
fun progressContextMatches(context: ProgressContext, connection: PlexConnection, album: PlexAlbum): Boolean =
    context.connection == connection && context.mode == LibraryMode.AUDIOBOOK && context.selectedAlbumId == album.id &&
        !context.offlineOnly && !context.playing && !context.buffering

sealed interface CompareResult {
    data class Ready(val comparison: ProgressComparison) : CompareResult
    /** The guards refused before anything was read. */
    data class Refused(val error: UiText) : CompareResult
    /** The context changed while Plex was being read; the stale answer is dropped silently. */
    data object Stale : CompareResult
    data class Failed(val error: UiText) : CompareResult
}

sealed interface ExchangeResult {
    data object Done : ExchangeResult
    data class Failed(val notice: UiText) : ExchangeResult
}

/** The explicit progress exchange: compare, send the phone's checkpoint, or resume from a Plex chapter. Never a background write. */
class ProgressExchange(
    private val gateways: PlexGateways,
    private val store: ProgressStore,
    private val player: PlaybackController,
    private val context: () -> ProgressContext,
) {
    private fun matches(connection: PlexConnection, album: PlexAlbum) = progressContextMatches(context(), connection, album)
    private fun matches(comparison: ProgressComparison) = matches(comparison.connection, comparison.album)

    private fun checkpoint(connection: PlexConnection, album: PlexAlbum, chapters: List<PlexChapterProgress>): PhoneCheckpoint {
        val scope = context().accountScope
        val saved = store.history().firstOrNull {
            it.server == connection.serverUrl && it.mode == LibraryMode.AUDIOBOOK && it.album.id == album.id && it.belongsToScope(scope)
        }
        return phoneCheckpoint(saved, connection, store.progressScope(connection), chapters)
    }

    suspend fun compare(connection: PlexConnection, album: PlexAlbum): CompareResult {
        if (!matches(connection, album)) return CompareResult.Refused(uiText(R.string.progress_compare_refused))
        return try {
            val chapters = gateways.open(connection, force = true).audiobookProgress(album.id)
            if (!matches(connection, album)) return CompareResult.Stale
            val phone = runCatching { checkpoint(connection, album, chapters) }
            CompareResult.Ready(ProgressComparison(album, connection, chapters, phone.getOrNull(), phone.exceptionOrNull()?.userText(uiText(R.string.progress_exchange_failed))))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            CompareResult.Failed(uiText(R.string.progress_read_failed))
        }
    }

    suspend fun send(comparison: ProgressComparison, phone: PhoneCheckpoint): ExchangeResult = exchange {
        checkText(matches(comparison)) { uiText(R.string.progress_pause_and_reconnect) }
        val api = gateways.open(comparison.connection, force = true)
        val saved = AudiobookProgressExchange({ api.audiobookProgress(comparison.album.id) }, api::sendAudiobookCheckpoint)
            .export(comparison.chapters, phone) {
                matches(comparison) && runCatching { checkpoint(comparison.connection, comparison.album, comparison.chapters) == phone }.getOrDefault(false)
            }
        store.resolveTimeline(store.progressScope(comparison.connection), comparison.connection.serverUrl, comparison.album.id, saved)
    }

    /** `download` is this phone's copy of the book, if any; a complete one plays instead of streaming. */
    suspend fun resume(comparison: ProgressComparison, chapter: PlexChapterProgress, download: DownloadStatus?): ExchangeResult = exchange {
        checkText(matches(comparison)) { uiText(R.string.progress_pause_and_reconnect) }
        val api = gateways.open(comparison.connection, force = true)
        checkText(api.audiobookProgress(comparison.album.id) == comparison.chapters) { uiText(R.string.progress_changed_close) }
        val local = download?.takeIf { it.ready }
        val tracks = local?.tracks ?: api.albumTracks(comparison.album.id)
        val index = tracks.indexOfFirst { it.id == chapter.id }
        checkText(index >= 0 && chapter.positionMs < tracks[index].durationMs) { uiText(R.string.progress_chapter_changed) }
        checkText(matches(comparison) && context().playerReady) { uiText(R.string.progress_playback_changed) }
        val scope = store.progressScope(comparison.connection)
        store.resolveTimeline(scope, comparison.connection.serverUrl, comparison.album.id, chapter)
        player.play(local?.album ?: comparison.album, tracks, comparison.connection, LibraryMode.AUDIOBOOK, scope, index, chapter.positionMs, speed = store.effectiveSpeed(scope, comparison.connection.serverUrl, comparison.album.id, LibraryMode.AUDIOBOOK))
    }

    private inline fun exchange(work: () -> Unit): ExchangeResult = try {
        work(); ExchangeResult.Done
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: Exception) { ExchangeResult.Failed(progressError(error)) }
}

fun progressError(error: Exception): UiText = when {
    error is PlexHttpException && error.authentication -> uiText(R.string.progress_http_rejected, error.status)
    error is IllegalStateException || error is IllegalArgumentException -> error.userText(uiText(R.string.progress_exchange_failed))
    else -> uiText(R.string.progress_connection_interrupted)
}
