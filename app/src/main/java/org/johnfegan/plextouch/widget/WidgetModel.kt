package org.johnfegan.plextouch.widget

import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.belongsToScope

/**
 * What the playback service has loaded right now (ticket 139). It is published from the service's own player events, so
 * the widget never asks the player and never polls; null once the service has stopped or holds no queue.
 */
data class LivePlayback(val album: PlexAlbum, val mode: LibraryMode, val server: String, val scope: String?, val playing: Boolean)

/** A complete download: the only titles offline-only mode may show, and the source of a locally saved cover. */
data class DownloadedTitle(val server: String, val albumId: String, val coverUri: String?)

/**
 * Lock-screen privacy for one widget instance. [keyguardHost] is true when the host placed this instance on the lock screen
 * (`widgetCategory="keyguard"`); [showPrivateOnLockScreen] mirrors the user's "show sensitive notification content" choice,
 * or null when the platform does not let the app read it, which is treated as "hide".
 */
data class WidgetPrivacy(val keyguardHost: Boolean = false, val showPrivateOnLockScreen: Boolean? = null) {
    val redact: Boolean get() = keyguardHost && showPrivateOnLockScreen != true
}

enum class EmptyReason { SIGNED_OUT, NOTHING_PLAYED }

sealed interface WidgetState {
    /** Opens the app; nothing about any account is shown. */
    data class Empty(val reason: EmptyReason) : WidgetState

    /**
     * The current or last title. With [redacted] the title, creator and artwork are withheld (null) and only the transport
     * and progress remain. [progressPermille] is 0–1000; [live] means the playback service holds this title's queue now.
     */
    data class Item(
        val albumId: String,
        val server: String,
        val mode: LibraryMode,
        val title: String?,
        val creator: String?,
        val progressPermille: Int,
        val playing: Boolean,
        val live: Boolean,
        val artwork: WidgetArtwork?,
        val redacted: Boolean,
    ) : WidgetState
}

/** Where the widget's cover comes from: never a URL, only image data the app saved itself. */
sealed interface WidgetArtwork {
    /** The cover saved with a download (`file://` inside the app's own storage). */
    data class Downloaded(val uri: String) : WidgetArtwork
    /** Bytes the playback service already fetched with the token header for its notification, cached under [key]. */
    data class Fetched(val key: String) : WidgetArtwork
}

/** The cache key for artwork the service fetched; it names the title, never a URL or a token. */
fun artworkKey(server: String, albumId: String): String = "$server\u0000$albumId"

/**
 * The widget's content, as a pure function of saved state so it can be tested on the JVM.
 *
 * - Signed out (no `server` or `scope`): empty, whatever history remains on the phone.
 * - Only history and live playback from this server and account scope count; another account's titles never show.
 * - Offline-only mode shows only complete downloads.
 * - The playing title wins; otherwise the most recently saved progress (audiobook or music, as `lastPlayed` orders it).
 * - Privacy withholds the text and artwork, never the transport.
 */
fun widgetModel(
    history: List<ListeningProgress>,
    playback: LivePlayback?,
    scope: String?,
    server: String?,
    offlineOnly: Boolean,
    downloads: List<DownloadedTitle>,
    privacy: WidgetPrivacy,
): WidgetState {
    if (server.isNullOrBlank() || scope.isNullOrBlank()) return WidgetState.Empty(EmptyReason.SIGNED_OUT)
    val local = downloads.filter { it.server == server }.associateBy { it.albumId }
    fun allowed(albumId: String) = !offlineOnly || albumId in local
    val ours = history.filter { it.server == server && it.belongsToScope(scope) && allowed(it.album.id) }
    val live = playback?.takeIf { it.server == server && it.scope == scope && allowed(it.album.id) }
    val saved = if (live != null) ours.firstOrNull { it.album.id == live.album.id && it.mode == live.mode } else ours.maxByOrNull { it.updatedAt }
    val album = live?.album ?: saved?.album ?: return WidgetState.Empty(EmptyReason.NOTHING_PLAYED)
    val mode = live?.mode ?: saved?.mode ?: LibraryMode.MUSIC
    val redact = privacy.redact
    val cover = local[album.id]?.coverUri ?: album.localThumb?.takeIf { it.startsWith("file:") }
    val artwork = when {
        redact -> null
        cover != null -> WidgetArtwork.Downloaded(cover)
        else -> WidgetArtwork.Fetched(artworkKey(server, album.id))
    }
    return WidgetState.Item(
        albumId = album.id, server = server, mode = mode,
        title = album.title.takeUnless { redact }, creator = album.artist.takeIf { !redact && it.isNotBlank() },
        progressPermille = ((saved?.fraction ?: 0f) * 1000).toInt().coerceIn(0, 1000),
        playing = live?.playing == true, live = live != null, artwork = artwork, redacted = redact,
    )
}

/**
 * Coalesces widget refresh requests. A state change (play, pause, track change, sign-out, download) renders at most once
 * per [minGapMs]; a routine progress checkpoint, which the service writes every few seconds while playing, at most once
 * per [checkpointGapMs]. Requests that arrive while a render is already scheduled are folded into it. Not thread-safe:
 * the caller confines it to the main looper.
 */
class WidgetThrottle(private val minGapMs: Long = 1_000, private val checkpointGapMs: Long = 15_000) {
    private var lastRenderAt: Long? = null
    private var scheduled = false

    /** The delay before rendering, or null when nothing needs scheduling (one is already pending, or a checkpoint is too soon). */
    fun request(now: Long, checkpoint: Boolean = false): Long? {
        val last = lastRenderAt
        if (checkpoint && last != null && now - last < checkpointGapMs) return null
        if (scheduled) return null
        scheduled = true
        return if (last == null) 0 else (last + minGapMs - now).coerceAtLeast(0)
    }

    /** Call when the scheduled render starts, so requests from then on schedule the next one. */
    fun rendered(now: Long) {
        scheduled = false
        lastRenderAt = now
    }
}

/** Ticket 140: the widget bar's whole percent for TalkBack (0–1000 permille, rounded half up). */
fun widgetPercent(permille: Int): Int = (permille.coerceIn(0, 1000) + 5) / 10
