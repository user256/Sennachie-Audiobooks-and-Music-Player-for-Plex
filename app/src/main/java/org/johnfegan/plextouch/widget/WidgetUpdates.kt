package org.johnfegan.plextouch.widget

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.OfflineDownloadManager
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexStore

/** Everything the widget reads from saved state, read once per render on a background thread. */
internal data class WidgetInputs(
    val connection: PlexConnection?,
    val scope: String?,
    val history: List<ListeningProgress>,
    val offlineOnly: Boolean,
    val downloads: List<DownloadStatus>,
) {
    val downloaded: List<DownloadedTitle> get() = downloads.filter { it.ready }.map { DownloadedTitle(it.record.server, it.record.album.id, it.coverUri) }

    fun model(live: LivePlayback?, privacy: WidgetPrivacy): WidgetState =
        widgetModel(history, live, scope, connection?.serverUrl, offlineOnly, downloaded, privacy)
}

/**
 * Refreshes the home-screen widget when something it shows changes (ticket 139). Nothing here runs on a timer: the playback
 * service calls [publish] on its own player events and [request] after progress checkpoints and artwork, the app calls
 * [request] on sign-in, sign-out, offline toggles and download changes, and the system calls the widget provider. Requests
 * are coalesced by [WidgetThrottle]; when no widget is placed a render returns before reading anything.
 */
object WidgetUpdates {
    private const val TAG = "WidgetUpdates"
    private val handler = Handler(Looper.getMainLooper())
    private val throttle = WidgetThrottle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rendering = Mutex()

    /** What the playback service holds now; in memory only, so a restarted process falls back to saved history. */
    @Volatile var live: LivePlayback? = null
        private set

    /** Called by the playback service on play/pause, track and queue changes; unchanged state renders nothing. */
    fun publish(context: Context, playback: LivePlayback?) {
        if (playback == live) return
        live = playback
        request(context)
    }

    /** Asks for a render; `checkpoint` marks a routine progress save, which is throttled harder than a state change. */
    fun request(context: Context, checkpoint: Boolean = false) {
        val app = context.applicationContext
        handler.post {
            val delay = throttle.request(SystemClock.elapsedRealtime(), checkpoint) ?: return@post
            handler.postDelayed({
                throttle.rendered(SystemClock.elapsedRealtime())
                scope.launch { render(app) }
            }, delay)
        }
    }

    /** For broadcast receivers: renders at once and finishes the receiver's pending result afterwards. */
    fun renderFrom(receiver: BroadcastReceiver, context: Context) {
        val app = context.applicationContext
        val pending = receiver.goAsync()
        scope.launch { try { render(app) } finally { pending.finish() } }
    }

    /** Fresh handles per read: both share the process's preference files and locks, and nothing holds a context statically. */
    internal fun store(app: Context): PlexStore = PlexStore(app)

    /** Reads the saved state the widget and its resume action share. Call off the main thread. */
    internal suspend fun inputs(app: Context): WidgetInputs {
        val store = store(app)
        val connection = store.connection()
        val offlineOnly = store.offlineOnly()
        val downloads = try { OfflineDownloadManager(app).snapshots() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        return WidgetInputs(connection, connection?.let(store::progressScope), store.history(), offlineOnly, downloads)
    }

    private suspend fun render(app: Context) = rendering.withLock {
        try {
            val manager = AppWidgetManager.getInstance(app) ?: return@withLock
            val ids = manager.getAppWidgetIds(ComponentName(app, NowPlayingWidget::class.java))
            if (ids.isEmpty()) return@withLock
            val inputs = inputs(app)
            if (inputs.connection == null) WidgetArtworkStore.clear(app)
            val playback = live
            for (id in ids) {
                val state = inputs.model(playback, privacy(app, manager, id))
                manager.updateAppWidget(id, WidgetViews.build(app, state))
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { Log.w(TAG, "Widget not refreshed: ${error.javaClass.simpleName}") }
    }

    /**
     * Best effort, as Android allows: a widget the host placed on the lock screen withholds titles and artwork unless the
     * user lets notifications show sensitive content there. The setting is not public API; when it cannot be read the
     * widget assumes "hide". A device without a secure lock screen has nothing to protect.
     */
    private fun privacy(app: Context, manager: AppWidgetManager, id: Int): WidgetPrivacy {
        val category = manager.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, -1) ?: -1
        val keyguard = category == AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD
        if (!keyguard) return WidgetPrivacy()
        return WidgetPrivacy(keyguardHost = true, showPrivateOnLockScreen = lockScreenShowsPrivate(app))
    }

    private fun lockScreenShowsPrivate(app: Context): Boolean? = runCatching {
        if (app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == false) return@runCatching true
        val resolver = app.contentResolver
        Settings.Secure.getInt(resolver, "lock_screen_show_notifications") != 0 &&
            Settings.Secure.getInt(resolver, "lock_screen_allow_private_notifications") != 0
    }.getOrNull()
}
