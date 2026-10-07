package org.johnfegan.plextouch.watch

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.johnfegan.plextouch.wear.shared.ChapterSkip
import org.johnfegan.plextouch.wear.shared.LocalPosition
import org.johnfegan.plextouch.wear.shared.TransferManifest

@Immutable
data class LocalState(
    val connected: Boolean = false,
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val index: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** The watch screen's controller for [WatchPlaybackService]; created while the book screen is shown, released after. */
class LocalPlayer(private val context: Context) {
    val state = mutableStateOf(LocalState())
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, WatchPlaybackService::class.java))).buildAsync()
    private val tick = object : Runnable {
        override fun run() { publish(); handler.postDelayed(this, 1_000) }
    }

    init {
        future.addListener({
            runCatching { future.get() }.onSuccess {
                controller = it
                it.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { publish() }
                })
                handler.post(tick)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun publish() {
        val c = controller ?: return
        state.value = LocalState(
            connected = true, loaded = c.mediaItemCount > 0, playing = c.playWhenReady && c.playbackState != Player.STATE_ENDED,
            index = c.currentMediaItemIndex, positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
        )
    }

    /** Loads the watch's copy at `start` (the local position, or the phone's when the listener chose it) and plays. */
    fun start(manifest: TransferManifest, start: LocalPosition?) {
        val c = controller ?: return
        val (items, index, offset) = WatchPlaybackService.queue(context, manifest, start)
        c.setMediaItems(items, index, offset)
        c.prepare()
        c.play()
        publish()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.playWhenReady) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
        publish()
    }

    fun skip(deltaMs: Long) {
        val c = controller ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, duration))
        publish()
    }

    fun chapter(manifest: TransferManifest, forward: Boolean) {
        val c = controller ?: return
        val markers = manifest.files.getOrNull(c.currentMediaItemIndex)?.markers.orEmpty()
        val target = ChapterSkip.target(c.mediaItemCount, c.currentMediaItemIndex, c.currentPosition, markers, forward) ?: return
        c.seekTo(target.index, target.positionMs)
        publish()
    }

    /** Before removal: stop and drop the queue so no file stays open. */
    fun clear() {
        controller?.run { stop(); clearMediaItems() }
        publish()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        MediaController.releaseFuture(future)
        controller = null
    }
}
