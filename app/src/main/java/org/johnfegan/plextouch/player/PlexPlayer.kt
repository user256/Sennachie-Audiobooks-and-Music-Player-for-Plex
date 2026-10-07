package org.johnfegan.plextouch.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.gson.Gson
import org.johnfegan.plextouch.data.*
import java.util.UUID
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText

/** What is playing and how; it changes on player events, never on the clock. The position lives in [PlaybackPosition]. */
@Immutable
data class PlaybackState(
    val ready: Boolean = false,
    val album: PlexAlbum? = null,
    val track: PlexTrack? = null,
    val mode: LibraryMode = LibraryMode.MUSIC,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val trackIndex: Int = 0,
    val trackCount: Int = 0,
    val speed: Float = 1f,
    val repeat: Boolean = false,
    val shuffle: Boolean = false,
    val sleepEndsAt: Long = 0,
    val error: UiText? = null,
    /** The playing queue's server and account scope (a token fingerprint): which account a per-book speed belongs to. */
    val server: String? = null,
    val accountScope: String? = null,
    /** The last smart rewind (ticket 134): how far it went back and when (`elapsedRealtime`), so it is announced once. */
    val rewoundMs: Long = 0,
    val rewindAt: Long = 0,
    /** Sound effects the listener chose that this device cannot run (ticket 135), reported once by the service. */
    val unsupportedEffects: Set<AudioEffectKind> = emptySet(),
)

/**
 * The offset inside the current item, published twice a second; only the player and mini-player watch it.
 * [bufferedMs] is Media3's buffered position in the same item (ticket 135); a downloaded file counts as fully buffered.
 */
@Immutable
data class PlaybackPosition(val positionMs: Long = 0, val durationMs: Long = 0, val bufferedMs: Long = 0)

/**
 * Normalises the controller's raw values: an unknown duration (`C.TIME_UNSET`) is 0, nothing is negative, the buffered
 * position never passes a known duration, and a local file is buffered to its end (ExoPlayer only reads ahead a little
 * of a file, but nothing about it can stall on the network).
 */
fun playbackPosition(positionMs: Long, durationMs: Long, bufferedMs: Long, local: Boolean): PlaybackPosition {
    val duration = if (durationMs == C.TIME_UNSET) 0 else durationMs.coerceAtLeast(0)
    val buffered = when {
        local && duration > 0 -> duration
        bufferedMs == C.TIME_UNSET -> 0
        duration > 0 -> bufferedMs.coerceIn(0, duration)
        else -> bufferedMs.coerceAtLeast(0)
    }
    return PlaybackPosition(positionMs.coerceAtLeast(0), duration, buffered)
}

/**
 * True when `PlexPlaybackService` will have written listening progress between these two states: it saves on
 * play/pause, on every playback-state change and on item transitions, and otherwise only on its own heartbeat.
 */
fun progressSaved(previous: PlaybackState, current: PlaybackState): Boolean =
    previous.playing != current.playing || previous.buffering != current.buffering || previous.trackIndex != current.trackIndex ||
        previous.track?.id != current.track?.id || previous.album?.id != current.album?.id

class PlexPlayer(context: Context, private val changed: (PlaybackState) -> Unit, private val moved: (PlaybackPosition) -> Unit) : PlaybackController {
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    /** Whether the current item is a downloaded file; set on player events so the tick does not parse metadata. */
    private var localItem = false
    // Session extras (sleep timer, unsupported effects, smart rewind) change without a player event, so they publish too.
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlexPlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) { publish() }
        }).buildAsync()
    private val tick = object : Runnable {
        override fun run() { publishPosition(); handler.postDelayed(this, 500) }
    }

    init {
        future.addListener({
            runCatching { future.get() }.onSuccess {
                controller = it
                it.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { publish() }
                })
                // A newly connected controller fires no player event until a media item is set, so publish the
                // connection once here; the library's playback controls depend on it (tickets 139 and 0.7.1).
                publish()
                handler.post(tick)
            }.onFailure { changed(PlaybackState(error = uiText(R.string.player_connect_failed))) }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun play(album: PlexAlbum, tracks: List<PlexTrack>, connection: PlexConnection, mode: LibraryMode, accountScope: String, index: Int, positionMs: Long, infinite: Boolean, shuffled: Boolean, speed: Float) {
        val player = controller ?: return
        // Book-level offsets come from one timeline, so files of unknown length add nothing to `before` or `total`.
        val timeline = BookTimeline.of(tracks)
        val playbackSession = UUID.randomUUID().toString()
        val items = tracks.mapIndexed { trackIndex, track ->
            val extras = Bundle().apply {
                putString("album", gson.toJson(album)); putString("track", gson.toJson(track))
                putString("mode", mode.name); putString("server", connection.serverUrl)
                putString("accountScope", accountScope)
                putString("playbackSession", playbackSession)
                putLong("timelineOrigin", if (trackIndex == index) positionMs.coerceAtLeast(0) else 0)
                putLong("trackDuration", track.durationMs)
                putLong("before", timeline.startOf(trackIndex)); putLong("total", timeline.totalMs)
            }
            MediaItem.Builder().setMediaId(track.id).setUri(track.localUri ?: playbackUri(track, connection.serverUrl))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                    .setAlbumTitle(album.title).setExtras(extras).build()).build()
        }
        sleep(0)
        player.setMediaItems(items, index.coerceIn(0, tracks.lastIndex), positionMs.coerceAtLeast(0))
        player.setPlaybackSpeed(speed.coerceIn(PlexStore.MIN_SPEED, PlexStore.MAX_SPEED))
        player.repeatMode = if (infinite) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = shuffled
        player.prepare()
        player.play()
        publish()
    }

    override fun toggle() {
        controller?.let {
            when {
                it.playerError != null -> { it.prepare(); it.play() }
                it.isPlaying || it.playWhenReady -> it.pause()
                else -> { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0, 0); if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
            }
        }
        publish()
    }
    override fun pause() { controller?.pause(); publish() }
    override fun stop() { controller?.let { it.pause(); it.clearMediaItems() }; publish() }
    override fun next() { controller?.seekToNextMediaItem(); publish() }
    override fun previous() { controller?.seekToPrevious(); publish() }
    override fun seek(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0)); publish() }
    override fun skip(deltaMs: Long) {
        val p = controller ?: return
        val durations = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaMetadata.extras?.getLong("trackDuration") ?: 0L }
        val target = bookSkip(durations, p.currentMediaItemIndex, p.currentPosition, deltaMs, p.duration, p.repeatMode != Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        if (target.trackIndex == p.currentMediaItemIndex) seek(target.offsetMs) else chapter(target.trackIndex, target.offsetMs)
    }
    override fun speed(value: Float) { controller?.setPlaybackSpeed(value.coerceIn(PlexStore.MIN_SPEED, PlexStore.MAX_SPEED)); publish() }
    override fun repeat() { controller?.let { it.repeatMode = if (it.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF }; publish() }
    override fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }; publish() }
    override fun chapter(index: Int, positionMs: Long) { controller?.seekTo(index, positionMs.coerceAtLeast(0)); publish() }
    override fun queue(): List<PlexTrack> = controller?.let { player -> (0 until player.mediaItemCount).mapNotNull { gson.fromJson(player.getMediaItemAt(it).mediaMetadata.extras?.getString("track"), PlexTrack::class.java) } }.orEmpty()
    override fun sleep(minutes: Int) { controller?.sendCustomCommand(SessionCommand(PlexPlaybackService.SLEEP, Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) }) }
    override fun effects(settings: AudioEffectsSettings) { controller?.sendCustomCommand(SessionCommand(PlexPlaybackService.EFFECTS, Bundle.EMPTY), settings.toBundle()) }

    private fun publish() {
        val p = controller ?: return
        val extras = p.mediaMetadata.extras
        val track = gson.fromJson(extras?.getString("track"), PlexTrack::class.java)
        localItem = track?.localUri != null
        changed(PlaybackState(
            ready = true,
            album = gson.fromJson(extras?.getString("album"), PlexAlbum::class.java),
            track = track,
            mode = runCatching { LibraryMode.valueOf(extras?.getString("mode").orEmpty()) }.getOrDefault(LibraryMode.MUSIC),
            playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playerError == null,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            trackIndex = p.currentMediaItemIndex, trackCount = p.mediaItemCount,
            speed = p.playbackParameters.speed, repeat = p.repeatMode != Player.REPEAT_MODE_OFF,
            shuffle = p.shuffleModeEnabled, sleepEndsAt = p.sessionExtras.getLong("sleepEndsAt"),
            error = p.playerError?.let { uiText(R.string.player_interrupted) },
            server = extras?.getString("server"), accountScope = extras?.getString("accountScope"),
            rewoundMs = p.sessionExtras.getLong(PlexPlaybackService.REWOUND_MS), rewindAt = p.sessionExtras.getLong(PlexPlaybackService.REWIND_AT),
            unsupportedEffects = p.sessionExtras.getStringArrayList(PlexPlaybackService.EFFECTS_UNSUPPORTED).orEmpty()
                .mapNotNullTo(mutableSetOf()) { name -> AudioEffectKind.entries.firstOrNull { it.name == name } },
        ))
        publishPosition()
    }

    private fun publishPosition() {
        val p = controller ?: return
        moved(playbackPosition(p.currentPosition, p.duration, p.bufferedPosition, localItem))
    }

    override fun release() { handler.removeCallbacksAndMessages(null); MediaController.releaseFuture(future); controller = null }
}

/**
 * The phone's stream URI carries no credential: `PlexPlaybackService` adds the token as a request header,
 * so an ExoPlayer error message or logcat line quoting this URI cannot expose it.
 */
fun playbackUri(track: PlexTrack, serverUrl: String): String = serverUrl.trimEnd('/') + track.streamPath

/**
 * A 30-second skip across a multi-file book: past a file boundary it carries into the neighbouring file (see
 * [BookTimeline.skip]) instead of clamping. Shuffled queues have no meaningful neighbour, so they keep the in-file [skipTarget].
 */
fun bookSkip(durationsMs: List<Long>, index: Int, positionMs: Long, deltaMs: Long, liveDurationMs: Long, repeat: Boolean, shuffled: Boolean): BookPoint =
    if (shuffled || index !in durationsMs.indices) BookPoint(index.coerceAtLeast(0), skipTarget(positionMs, deltaMs, liveDurationMs))
    else BookTimeline(durationsMs).skip(index, positionMs, deltaMs, liveDurationMs, repeat)

/** Skips stay within a known duration; while it is still unknown (buffering, `C.TIME_UNSET`) only the floor applies. */
fun skipTarget(currentMs: Long, deltaMs: Long, durationMs: Long): Long {
    val target = (currentMs + deltaMs).coerceAtLeast(0)
    return if (durationMs == C.TIME_UNSET || durationMs <= 0) target else target.coerceAtMost(durationMs)
}
