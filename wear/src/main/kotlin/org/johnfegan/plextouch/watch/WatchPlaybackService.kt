package org.johnfegan.plextouch.watch

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.johnfegan.plextouch.wear.shared.Checkpoint
import org.johnfegan.plextouch.wear.shared.LocalPosition
import org.johnfegan.plextouch.wear.shared.TransferManifest
import org.johnfegan.plextouch.wear.shared.WatchBooks

/**
 * Plays the book copied to the watch (ticket 141, offline preview). Files come only from app-private storage; there is
 * no network source at all, so this player can never reach Plex. Positions are recorded locally on pause, every
 * [CHECKPOINT_MS] while playing, on file changes and when the service stops, and queued for the phone, which decides
 * through its own outbox rules whether they reach Plex. Nothing here can mark a book finished.
 */
class WatchPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** One id per service lifetime, so the phone's outbox can tell watch sessions apart. */
    private val sessionId = UUID.randomUUID().toString()
    private val heartbeat = object : Runnable {
        override fun run() {
            if (session?.player?.isPlaying == true) record()
            handler.postDelayed(this, CHECKPOINT_MS)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            // Wear OS: never play through the watch speaker by surprise; Media3 offers the output switcher instead.
            .setSuppressPlaybackOnUnsuitableOutput(true)
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) record() }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { record() }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) record()
                    }
                })
            }
        session = MediaSession.Builder(this, player).build()
        handler.postDelayed(heartbeat, CHECKPOINT_MS)
    }

    private fun record() {
        val player = session?.player ?: return
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val transferId = extras.getString(TRANSFER) ?: return
        val checkpoint = Checkpoint(
            id = UUID.randomUUID().toString(), transferId = transferId, trackId = item.mediaId,
            positionMs = player.currentPosition.coerceAtLeast(0), durationMs = extras.getLong(DURATION),
            originPositionMs = extras.getLong(ORIGIN), capturedAt = System.currentTimeMillis(), sessionId = sessionId,
            playing = player.isPlaying,
        )
        val app = applicationContext
        WatchRepository.update(app) { WatchBooks.played(it, checkpoint) }
        scope.launch { PhoneLink.flushPending(app) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        record()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        const val CHECKPOINT_MS = 30_000L
        private const val TRANSFER = "transferId"
        private const val ORIGIN = "origin"
        private const val DURATION = "duration"

        /**
         * The queue for the watch's copy, starting at `start`. The file it starts in records `start`'s offset as its
         * origin (where this listening began); every later file starts at 0.
         */
        fun queue(context: Context, manifest: TransferManifest, start: LocalPosition?): Triple<List<MediaItem>, Int, Long> {
            val index = start?.let { position -> manifest.files.indexOfFirst { it.trackId == position.trackId } }?.takeIf { it >= 0 } ?: manifest.resumeIndex
            val offset = if (start != null && manifest.files[index].trackId == start.trackId) start.positionMs else manifest.resumePositionMs.takeIf { index == manifest.resumeIndex } ?: 0
            val items = manifest.files.mapIndexed { i, file ->
                val extras = Bundle().apply {
                    putString(TRANSFER, manifest.transferId)
                    putLong(ORIGIN, if (i == index) offset else 0)
                    putLong(DURATION, file.durationMs)
                }
                MediaItem.Builder().setMediaId(file.trackId).setUri(Uri.fromFile(WatchFiles.file(context, manifest, i)))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(file.title).setArtist(manifest.author).setAlbumTitle(manifest.title).setExtras(extras).build())
                    .build()
            }
            return Triple(items, index, offset)
        }
    }
}
