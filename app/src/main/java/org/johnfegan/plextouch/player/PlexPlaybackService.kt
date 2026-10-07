package org.johnfegan.plextouch.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.Gson
import org.johnfegan.plextouch.MainActivity
import org.johnfegan.plextouch.data.*
import org.johnfegan.plextouch.wear.WearUpdates
import org.johnfegan.plextouch.widget.LivePlayback
import org.johnfegan.plextouch.widget.WidgetArtworkStore
import org.johnfegan.plextouch.widget.WidgetUpdates
import org.johnfegan.plextouch.widget.artworkKey
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.UUID

/** One playback owner for the app, notification and lock screen. */
class PlexPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var store: PlexStore
    private val handler = Handler(Looper.getMainLooper())
    private var sleepEndsAt = 0L
    private var ticks = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkJob: Job? = null
    private var timelineJob: Job? = null
    private val artwork = LruCache<String, ByteArray>(6)
    /** Store writes leave the main looper in capture order: encrypting a history blob is too slow for a UI tick. */
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)
    private var writer: Job? = null
    private lateinit var pauses: PauseRecordStore
    /** The last qualifying audiobook pause (ticket 134); persisted so a recreated service still rewinds. */
    private var pausedAt: PausedAt? = null
    private var lastRewind: RewindMark? = null
    private var rewoundMs = 0L
    private var rewindAt = 0L
    private var focusSuppressed = false
    /**
     * Voice boost and equaliser (ticket 135). They attach to this ExoPlayer's audio session only, so they shape what the
     * phone plays and never a file on disk, a Plex file or a Sonos handoff (Sonos streams from Plex, outside this session).
     */
    private var unsupportedEffects: Set<AudioEffectKind> = emptySet()
    /** Ticket 138: playing wall-time for the local listening log, measured on the monotonic clock. */
    private val listening = ListeningClock()
    private val effects = AudioEffectsController(PlatformAudioEffects()) { unsupported -> unsupportedEffects = unsupported; publishSessionExtras() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (sleepEndsAt > 0 && SystemClock.elapsedRealtime() >= sleepEndsAt) {
                session?.player?.pause()
                setSleepTimer(0)
            }
            if (++ticks % 5 == 0 && session?.player?.isPlaying == true) saveProgress(checkpoint = true)
            if (ticks % 5 == 0) recordListening(session?.player?.isPlaying == true)
            if (ticks % 10 == 0 && session?.player?.isPlaying == true) {
                session?.player?.currentMediaItem?.let { item -> recordTimeline(item, session?.player?.currentPosition ?: 0, PlexTimelineState.PLAYING) }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        store = PlexStore(this)
        pauses = PauseRecordStore(store::pausedAtJson, store::savePausedAtJson)
        pausedAt = pauses.read()
        // One consumer keeps writes in the order they were captured; a bad write is logged, not fatal to playback.
        writer = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (write in writes) {
                try { write() } catch (error: Exception) { Log.w(TAG, "Dropped a store write: ${error.javaClass.simpleName}") }
            }
        }
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(playbackDataSourceFactory())).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    recordListening(isPlaying)
                    saveProgress()
                    val player = session?.player ?: return
                    player.currentMediaItem?.let { recordTimeline(it, player.currentPosition, if (isPlaying) PlexTimelineState.PLAYING else PlexTimelineState.PAUSED) }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) pauseCaptured(pauseReason(reason))
                }
                /** A call or another app's prompt holds focus briefly; playback resumes by itself when it is returned. */
                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    val player = session?.player ?: return
                    if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS && player.playWhenReady) {
                        focusSuppressed = true
                        pauseCaptured(PauseReason.FOCUS_LOSS)
                    } else if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && focusSuppressed) {
                        focusSuppressed = false
                        if (player.playWhenReady && resumeRewind()) {
                            // Playback is already running, so record the deliberate position now rather than at the next heartbeat.
                            saveProgress()
                            player.currentMediaItem?.let { recordTimeline(it, player.currentPosition, PlexTimelineState.PLAYING) }
                        }
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    saveProgress()
                    if (playbackState == Player.STATE_BUFFERING) {
                        val player = session?.player ?: return
                        player.currentMediaItem?.let { recordTimeline(it, player.currentPosition, PlexTimelineState.BUFFERING) }
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateNotificationArtwork()
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) mediaItem?.let(::applyEffectiveSpeed)
                }
                override fun onAudioSessionIdChanged(audioSessionId: Int) { effects.attach(audioSessionId) }
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_TIMELINE_CHANGED)) publishWidget()
                    // Ticket 141: the watch companion's view of the same events, plus seeks and speed changes.
                    if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) WearUpdates.publish(this@PlexPlaybackService, player)
                }
                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    // Rolling into the next file checkpoints the start of that file, not the tail of the last one; neither is
                    // ever a completion. A seek or queue replacement checkpoints where the old item was left.
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        newPosition.mediaItem?.let { savePosition(it, newPosition.mediaItemIndex, newPosition.positionMs, false) }
                    } else oldPosition.mediaItem?.let { savePosition(it, oldPosition.mediaItemIndex, oldPosition.positionMs, false) }
                }
            })
        }
        // Every play command (app, notification, lock screen, headset) passes here first, so a smart rewind lands before
        // playback starts and the first PLAYING checkpoint already carries the rewound position.
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun play() { if (!player.playWhenReady) resumeRewind(); super.play() }
            override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady && !player.playWhenReady) resumeRewind(); super.setPlayWhenReady(playWhenReady) }
        }
        session = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand(SLEEP, Bundle.EMPTY)).add(SessionCommand(EFFECTS, Bundle.EMPTY)).build())
                        .build()
                }

                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        SLEEP -> setSleepTimer(args.getInt("minutes").coerceIn(0, 120))
                        EFFECTS -> effects.update(audioEffectsSettings(args))
                        else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        // The saved choice applies from the first note; later changes arrive as EFFECTS commands from the app.
        effects.update(store.audioEffects())
        effects.attach(player.audioSessionId)
        handler.post(heartbeat)
        flushTimeline()
    }

    /**
     * Streams send the token as a header, never in the URI. The header is resolved per request from the store, so a
     * re-login takes effect without restarting this service; `file://` downloads bypass the HTTP source entirely.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun playbackDataSourceFactory(): DataSource.Factory {
        val headers = PlexTokenHeaders { store.connection() }
        val http = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)) { dataSpec ->
            val extra = headers.headersFor(dataSpec.uri.host)
            if (extra.isEmpty()) dataSpec else dataSpec.buildUpon().setHttpRequestHeaders(dataSpec.httpRequestHeaders + extra).build()
        }
        return DefaultDataSource.Factory(this, http)
    }

    private fun setSleepTimer(minutes: Int) {
        sleepEndsAt = if (minutes == 0) 0 else SystemClock.elapsedRealtime() + minutes * 60_000L
        publishSessionExtras()
    }

    /** Session extras are replaced as a whole, so every service-owned flag is published together. */
    private fun publishSessionExtras() {
        session?.setSessionExtras(Bundle().apply {
            putLong("sleepEndsAt", sleepEndsAt)
            putStringArrayList(EFFECTS_UNSUPPORTED, ArrayList(unsupportedEffects.map { it.name }))
            putLong(REWOUND_MS, rewoundMs); putLong(REWIND_AT, rewindAt)
        })
    }

    /** Reboots reset `elapsedRealtime`; the boot count tells [RewindPolicy.pausedFor] to fall back to the wall clock. */
    private fun bootCount(): Int = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    /** Remembers (or, for a non-qualifying stop or a non-audiobook, forgets) the pause a later resume may rewind from. */
    private fun pauseCaptured(reason: PauseReason) {
        val player = session?.player ?: return
        val item = player.currentMediaItem
        val extras = item?.mediaMetadata?.extras
        val albumId = runCatching { Gson().fromJson(extras?.getString("album"), PlexAlbum::class.java)?.id }.getOrNull()
        val record = if (item == null || extras == null || albumId == null || reason == PauseReason.OTHER ||
            extras.getString("mode") != LibraryMode.AUDIOBOOK.name || player.playbackState == Player.STATE_ENDED) null
        else {
            val position = player.currentPosition.coerceAtLeast(0)
            PausedAt(
                scope = extras.getString("accountScope").orEmpty(), server = extras.getString("server").orEmpty(), albumId = albumId,
                trackId = item.mediaId, positionMs = position, reason = reason, elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                wallClockMs = System.currentTimeMillis(), bootCount = bootCount(), replayUntilMs = RewindPolicy.replayUntil(lastRewind, item.mediaId, position),
            )
        }
        pausedAt = record
        persist { pauses.save(record) }
    }

    /**
     * Consumes the remembered pause and, when [RewindPolicy] says so, seeks back inside the current file (never before
     * its start or the embedded chapter's start). The seek is an ordinary user-style seek: the old offset is checkpointed
     * locally, the rewound one is what the next PLAYING checkpoint and timeline event carry, and nothing is completed.
     */
    private fun resumeRewind(): Boolean {
        val pause = pausedAt ?: return false
        pausedAt = null
        persist { pauses.save(null) }
        val player = session?.player ?: return false
        val item = player.currentMediaItem ?: return false
        val extras = item.mediaMetadata.extras ?: return false
        val albumId = runCatching { Gson().fromJson(extras.getString("album"), PlexAlbum::class.java)?.id }.getOrNull() ?: return false
        val track = runCatching { Gson().fromJson(extras.getString("track"), PlexTrack::class.java) }.getOrNull()
        val position = player.currentPosition.coerceAtLeast(0)
        val target = RewindPolicy.resumeTarget(
            pause, extras.getString("mode") == LibraryMode.AUDIOBOOK.name, extras.getString("accountScope").orEmpty(),
            extras.getString("server").orEmpty(), albumId, item.mediaId, position, RewindPolicy.chapterStart(track, position),
            store.smartRewindSeconds(), SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCount(),
        ) ?: return false
        lastRewind = RewindMark(item.mediaId, position)
        rewoundMs = position - target
        rewindAt = SystemClock.elapsedRealtime()
        publishSessionExtras()
        player.seekTo(target)
        return true
    }

    /** `checkpoint` marks the heartbeat's routine save, which refreshes the widget less often than a state change. */
    private fun saveProgress(checkpoint: Boolean = false) {
        val player = session?.player ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val item = player.currentMediaItem ?: return
        savePosition(item, player.currentMediaItemIndex, player.currentPosition, finishedBook(player.playbackState, player.currentMediaItemIndex, player.mediaItemCount, player.repeatMode), checkpoint)
    }

    private fun savePosition(item: MediaItem, index: Int, position: Long, finished: Boolean, checkpoint: Boolean = false) {
        val extras = item.mediaMetadata.extras ?: return
        val album = runCatching { Gson().fromJson(extras.getString("album"), PlexAlbum::class.java) }.getOrNull() ?: return
        val progress = ListeningProgress(
            album = album, mode = LibraryMode.valueOf(extras.getString("mode") ?: "MUSIC"),
            server = extras.getString("server").orEmpty(), trackIndex = index,
            positionMs = position.coerceAtLeast(0),
            elapsedMs = bookElapsedMs(extras.getLong("before"), position, extras.getLong("trackDuration")),
            durationMs = extras.getLong("total"), updatedAt = System.currentTimeMillis(),
            finished = finished,
            trackId = item.mediaId, accountScope = extras.getString("accountScope"),
        )
        persist { store.saveProgress(progress); WidgetUpdates.request(this, checkpoint) }
    }

    /** Ticket 139: tells the widget what this session holds; [WidgetUpdates] skips unchanged state and throttles the rest. */
    private fun publishWidget() {
        val player = session?.player
        val extras = player?.currentMediaItem?.mediaMetadata?.extras
        val album = extras?.let { runCatching { Gson().fromJson(it.getString("album"), PlexAlbum::class.java) }.getOrNull() }
        WidgetUpdates.publish(this, if (player == null || album == null) null else LivePlayback(
            album = album, mode = runCatching { LibraryMode.valueOf(extras.getString("mode").orEmpty()) }.getOrDefault(LibraryMode.MUSIC),
            server = extras.getString("server").orEmpty(), scope = extras.getString("accountScope"),
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null,
        ))
    }

    /**
     * Ticket 138: adds the playing time since the last call to the local listening log for the item now playing. Values are
     * captured here; the write joins the ordered writer like every other store write.
     */
    private fun recordListening(playing: Boolean) {
        val delta = listening.take(SystemClock.elapsedRealtime(), playing)
        if (delta <= 0) return
        val extras = session?.player?.currentMediaItem?.mediaMetadata?.extras ?: return
        val album = runCatching { Gson().fromJson(extras.getString("album"), PlexAlbum::class.java) }.getOrNull() ?: return
        val mode = runCatching { LibraryMode.valueOf(extras.getString("mode").orEmpty()) }.getOrDefault(LibraryMode.MUSIC)
        val end = System.currentTimeMillis()
        val sample = ListeningSample(extras.getString("accountScope"), extras.getString("server").orEmpty(), mode, album.id, end - delta, end, ZoneId.systemDefault())
        persist { store.recordListening(sample) }
    }

    /**
     * Whichever controller loaded the queue (the app, the notification's resumption or another media-session client), the
     * session plays a book at its effective speed: its own override (ticket 133), else the mode's default. The store is read
     * on the writer, behind any queued write, and applied only while the same queue is still loaded.
     */
    private fun applyEffectiveSpeed(item: MediaItem) {
        val extras = item.mediaMetadata.extras ?: return
        val mode = runCatching { LibraryMode.valueOf(extras.getString("mode").orEmpty()) }.getOrNull() ?: return
        val albumId = runCatching { Gson().fromJson(extras.getString("album"), PlexAlbum::class.java)?.id }.getOrNull() ?: return
        val scope = extras.getString("accountScope").orEmpty()
        val server = extras.getString("server").orEmpty()
        val queue = extras.getString("playbackSession")
        persist {
            val speed = store.effectiveSpeed(scope, server, albumId, mode)
            handler.post {
                val player = session?.player ?: return@post
                if (player.currentMediaItem?.mediaMetadata?.extras?.getString("playbackSession") != queue) return@post
                if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
            }
        }
    }

    /** Values are captured on the main thread; only the encrypt-and-write happens on the writer. */
    private fun persist(write: () -> Unit) { writes.trySend(write) }

    /** The queue is persisted before a request. Seeks/discontinuities remain local checkpoints, never completion events. */
    private fun recordTimeline(item: MediaItem, position: Long, timelineState: PlexTimelineState) {
        val extras = item.mediaMetadata.extras ?: return
        if (extras.getString("mode") != LibraryMode.AUDIOBOOK.name) return
        val duration = extras.getLong("trackDuration")
        val safePosition = position.coerceAtLeast(0)
        // Initial prepare/noisy state changes do not create speculative server progress.
        if (duration <= 0 || safePosition < MIN_SYNC_POSITION_MS || safePosition >= duration) return
        val scope = extras.getString("accountScope")?.takeIf { it.length == 64 } ?: return
        val server = extras.getString("server")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val album = runCatching { Gson().fromJson(extras.getString("album"), PlexAlbum::class.java) }.getOrNull() ?: return
        val event = TimelineEvent(
            id = UUID.randomUUID().toString(), scope = scope, server = server, albumId = album.id,
            trackId = item.mediaId, originPositionMs = extras.getLong("timelineOrigin").coerceAtLeast(0),
            positionMs = safePosition, durationMs = duration, state = timelineState,
            capturedAt = System.currentTimeMillis(), playbackSessionId = extras.getString("playbackSession").orEmpty(),
            offline = false, baseline = null,
        )
        if (event.playbackSessionId.length < 8) return
        persist {
            // The baseline is read on the writer so it reflects every acknowledgement queued ahead of this event.
            store.enqueueTimeline(event.copy(offline = store.offlineOnly(), baseline = store.timelineBaseline(scope, server, album.id, item.mediaId)))
            handler.post { if (session != null) flushTimeline() }
        }
        TimelineSyncScheduler.schedule(this)
    }

    private fun flushTimeline() {
        if (timelineJob?.isActive == true) return
        timelineJob = scope.launch {
            withContext(Dispatchers.IO) { TimelineSyncGate.exclusive {
                val connection = store.connection() ?: return@exclusive
                val activeScope = store.progressScope(connection)
                store.protectTimelineScopes(activeScope, System.currentTimeMillis())
                val client = PlexClient(connection, clientIdentifier = store.clientIdentifier())
                val result = TimelineDrainer(
                    activeScope, connection.serverUrl, store::nextTimeline, store::acknowledgeTimeline,
                    store::blockTimeline, store::retryTimeline,
                    AudiobookTimelineSync(client::audiobookProgress, client::reportAudiobookTimeline)::deliver,
                ).drain()
                when (result) {
                    is TimelineDrainResult.Retry -> {
                        TimelineSyncScheduler.scheduleRetry(this@PlexPlaybackService, TimelineOutbox.retryDelay(result.event))
                        scope.launch {
                            delay(TimelineOutbox.retryDelay(result.event))
                            flushTimeline()
                        }
                    }
                    else -> Unit
                }
            } }
        }
    }

    /** Give Android the actual image bytes, not a URL exposing a Plex token. */
    private fun updateNotificationArtwork() {
        artworkJob?.cancel()
        val player = session?.player ?: return
        val item = player.currentMediaItem ?: return
        if (item.mediaMetadata.artworkData != null) return
        val extras = item.mediaMetadata.extras ?: return
        val album = Gson().fromJson(extras.getString("album"), PlexAlbum::class.java) ?: return
        val connection = store.connection()?.takeIf { it.serverUrl == extras.getString("server") }
        val key = "${extras.getString("server")}:${album.id}:${album.thumb}"
        artworkJob = scope.launch {
            val bytes = artwork.get(key) ?: withContext(Dispatchers.IO) {
                try {
                    val source = album.localThumb ?: if (store.offlineOnly()) null else album.thumb?.takeIf { it.startsWith("/") && !it.startsWith("//") }?.let { path -> connection?.let { it.serverUrl.trimEnd('/') + path } }
                    if (source == null) return@withContext null
                    val request = ImageRequest.Builder(this@PlexPlaybackService).data(source).size(384).allowHardware(false)
                    if (album.localThumb == null && connection != null) request.addHeader("X-Plex-Token", connection.token)
                    val drawable = imageLoader.execute(request.build()).drawable ?: return@withContext null
                    ByteArrayOutputStream().use { output -> drawable.toBitmap().compress(Bitmap.CompressFormat.JPEG, 80, output); output.toByteArray() }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { null }
            } ?: return@launch
            artwork.put(key, bytes)
            // The widget gets the same bytes, saved privately and downscaled; it never sees the token-bearing URL.
            extras.getString("server")?.let { server ->
                withContext(Dispatchers.IO) { WidgetArtworkStore.remember(this@PlexPlaybackService, artworkKey(server, album.id), bytes) }
                WidgetUpdates.request(this@PlexPlaybackService)
            }
            // The same track may occur twice in a playlist, with different progress extras.
            if (player.currentMediaItem !== item) return@launch
            val metadata = item.mediaMetadata.buildUpon().setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
            player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(metadata).build())
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        recordListening(false)
        WidgetUpdates.publish(this, null)
        WearUpdates.publish(this, null)
        saveProgress()
        session?.player?.currentMediaItem?.let { recordTimeline(it, session?.player?.currentPosition ?: 0, PlexTimelineState.STOPPED) }
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        effects.release()
        session?.run { player.release(); release() }
        session = null
        flushWrites()
        super.onDestroy()
    }

    /** The final position must land before Android drops the process; the wait is bounded so a slow keystore cannot ANR. */
    private fun flushWrites() {
        writes.close()
        val pending = writer ?: return
        runBlocking { withTimeoutOrNull(FLUSH_TIMEOUT_MS) { pending.join() } }
    }

    companion object {
        const val SLEEP = "org.johnfegan.plextouch.SLEEP"
        const val REWOUND_MS = "rewoundMs"
        const val REWIND_AT = "rewindAt"
        const val EFFECTS = "org.johnfegan.plextouch.EFFECTS"
        /** Session-extras key: names of [AudioEffectKind]s the listener chose that this device cannot run. */
        const val EFFECTS_UNSUPPORTED = "effectsUnsupported"
        private const val TAG = "PlexPlaybackService"
        private const val MIN_SYNC_POSITION_MS = 30_000L
        private const val FLUSH_TIMEOUT_MS = 2_000L
    }
}
