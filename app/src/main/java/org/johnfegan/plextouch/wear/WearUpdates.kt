package org.johnfegan.plextouch.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.wear.shared.WearCodec
import org.johnfegan.plextouch.wear.shared.WearPaths
import org.johnfegan.plextouch.widget.WidgetUpdates

/**
 * Publishes the phone's state to a paired watch as one Data Layer item (ticket 141). The playback service calls [publish]
 * on its own player events (play/pause, item, seek, speed), the watch asks with a `RequestState` message when its screen
 * opens, and nothing runs on a timer: the watch extrapolates the position from the published offset, time and speed.
 * When no watch with the companion is paired (or Play services is missing) nothing is sent at all.
 */
object WearUpdates {
    private const val TAG = "WearUpdates"
    private const val MIN_GAP_MS = 1_000L
    private const val PAIRING_CACHE_MS = 5 * 60_000L
    private const val TIMEOUT_S = 10L
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private val gson = Gson()

    /** What the service holds now; in memory only, like the widget's live state. */
    @Volatile var live: LiveBook? = null
        private set
    /** The book the watch last said it holds, so the published state carries the phone's position in it. */
    @Volatile private var heldAlbumId: String? = null
    @Volatile private var paired: Pair<Long, Boolean>? = null
    private var scheduled = false
    private var lastSentAt = 0L

    /** Main thread, from the playback service; `player` null when the service is going away. */
    fun publish(context: Context, player: Player?) {
        val book = player?.let(::liveBook)
        if (book == live) return
        live = book
        request(context)
    }

    /** From the listener when the watch asks; `held` replaces the remembered held book. */
    fun publishNow(context: Context, held: String?) {
        heldAlbumId = held
        scope.launch { send(context.applicationContext) }
    }

    private fun request(context: Context) {
        val app = context.applicationContext
        if (scheduled) return
        scheduled = true
        val delay = (lastSentAt + MIN_GAP_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        handler.postDelayed({
            scheduled = false
            lastSentAt = SystemClock.elapsedRealtime()
            scope.launch { send(app) }
        }, delay)
    }

    private fun liveBook(player: Player): LiveBook? {
        val item = player.currentMediaItem ?: return null
        val extras = item.mediaMetadata.extras ?: return null
        val album = runCatching { gson.fromJson(extras.getString("album"), PlexAlbum::class.java) }.getOrNull() ?: return null
        val track = runCatching { gson.fromJson(extras.getString("track"), PlexTrack::class.java) }.getOrNull()
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: extras.getLong("trackDuration")
        val before = extras.getLong("before")
        return LiveBook(
            album = album, mode = runCatching { LibraryMode.valueOf(extras.getString("mode").orEmpty()) }.getOrDefault(LibraryMode.MUSIC),
            server = extras.getString("server").orEmpty(), scope = extras.getString("accountScope"),
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null,
            track = track, trackIndex = player.currentMediaItemIndex, trackCount = player.mediaItemCount,
            positionMs = position, durationMs = duration, speed = player.playbackParameters.speed,
            bookElapsedMs = before + if (duration > 0) position.coerceAtMost(duration) else position, bookTotalMs = extras.getLong("total"),
        )
    }

    private suspend fun send(app: Context) = sending.withLock {
        try {
            if (!watchPaired(app)) return@withLock
            val inputs = WidgetUpdates.inputs(app)
            val server = inputs.connection?.serverUrl
            val accountScope = inputs.scope
            val readingList = if (server != null && accountScope != null) WidgetUpdates.store(app).readingList(accountScope, server) else emptyList()
            val state = watchPhoneState(server, accountScope, inputs.history, inputs.offlineOnly, inputs.downloads, readingList, live, heldAlbumId, System.currentTimeMillis())
            val request = PutDataRequest.create(WearPaths.STATE).setData(WearCodec.encode(state)).setUrgent()
            Tasks.await(Wearable.getDataClient(app).putDataItem(request), TIMEOUT_S, TimeUnit.SECONDS)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { Log.i(TAG, "Watch state not published: ${error.javaClass.simpleName}") }
    }

    /** Whether any watch with the companion is paired; cached so a phone without a watch asks Play services rarely. */
    private fun watchPaired(app: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        paired?.let { (at, value) -> if (now - at < PAIRING_CACHE_MS) return value }
        val value = runCatching {
            Tasks.await(Wearable.getCapabilityClient(app).getCapability(WearPaths.WATCH_CAPABILITY, CapabilityClient.FILTER_ALL), TIMEOUT_S, TimeUnit.SECONDS).nodes.isNotEmpty()
        }.getOrDefault(false)
        paired = now to value
        return value
    }

    /** A message from the watch proves it is paired, whatever the cache says. */
    fun watchSeen() { paired = SystemClock.elapsedRealtime() to true }
}
