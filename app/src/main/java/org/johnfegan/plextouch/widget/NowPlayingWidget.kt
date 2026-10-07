package org.johnfegan.plextouch.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.johnfegan.plextouch.MainActivity
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.app.AlbumPlayback
import org.johnfegan.plextouch.app.PlayOutcome
import org.johnfegan.plextouch.data.PlexCache
import org.johnfegan.plextouch.data.PlexClientGateways
import org.johnfegan.plextouch.data.belongsToScope
import org.johnfegan.plextouch.player.PlaybackState
import org.johnfegan.plextouch.player.PlexPlayer
import org.johnfegan.plextouch.ui.label
import java.io.File

/**
 * The "Now playing" home-screen widget (ticket 139), built on `AppWidgetProvider` and `RemoteViews`: Glance is not among
 * the project's dependencies and could not be added offline. `updatePeriodMillis` is 0; every refresh comes from
 * [WidgetUpdates] on a playback, download or account event, or from the system here (placement, reboot, resize).
 */
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdates.renderFrom(this, context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        WidgetUpdates.renderFrom(this, context)
    }
}

/** A finished system download can make a title playable offline or give it a saved cover, even while the app is closed. */
class DownloadWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE) WidgetUpdates.renderFrom(this, context)
    }
}

/** Builds the widget's views for one [WidgetState]. */
internal object WidgetViews {
    private const val OPEN_APP = 0
    private const val TOGGLE = 1

    fun build(context: Context, state: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
        val appName = context.getString(R.string.app_name)
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, OPEN_APP,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        when (state) {
            is WidgetState.Empty -> {
                views.setTextViewText(R.id.widget_title, appName)
                views.setTextViewText(R.id.widget_subtitle, context.getString(
                    if (state.reason == EmptyReason.SIGNED_OUT) R.string.widget_empty_signed_out else R.string.widget_empty_nothing, appName))
                views.setViewVisibility(R.id.widget_progress, View.GONE)
                views.setViewVisibility(R.id.widget_toggle, View.GONE)
                views.setImageViewResource(R.id.widget_art, R.drawable.ic_sennachie_mark)
            }
            is WidgetState.Item -> {
                val title = state.title ?: context.getString(if (state.playing) R.string.widget_private_playing else R.string.widget_private_paused)
                val subtitle = state.creator ?: context.getString(state.mode.label)
                views.setTextViewText(R.id.widget_title, title)
                views.setTextViewText(R.id.widget_subtitle, subtitle)
                views.setViewVisibility(R.id.widget_progress, View.VISIBLE)
                views.setProgressBar(R.id.widget_progress, 1000, state.progressPermille, false)
                // Ticket 140: TalkBack reads "40 percent played" rather than a bare progress bar.
                views.setContentDescription(R.id.widget_progress, widgetPercent(state.progressPermille).let { percent -> context.resources.getQuantityString(R.plurals.a11y_widget_progress, percent, percent) })
                views.setViewVisibility(R.id.widget_toggle, View.VISIBLE)
                views.setImageViewResource(R.id.widget_toggle, if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                views.setContentDescription(R.id.widget_toggle, context.getString(if (state.playing) R.string.widget_pause else R.string.widget_play))
                views.setOnClickPendingIntent(R.id.widget_toggle, PendingIntent.getBroadcast(context, TOGGLE,
                    Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_TOGGLE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                val art = WidgetArtworkStore.load(context, state.artwork)
                if (art != null) views.setImageViewBitmap(R.id.widget_art, art) else views.setImageViewResource(R.id.widget_art, R.drawable.ic_sennachie_mark)
            }
        }
        return views
    }
}

/**
 * The widget's play/pause button. It never touches ExoPlayer directly: it connects a `MediaController` (through
 * [PlexPlayer]) to the playback service's media session. A loaded queue is toggled; with nothing loaded it resumes the title
 * the widget shows through the app's own last-played path ([AlbumPlayback.resume]), so offline, finished-title and account
 * rules are the same as the in-app resume bar. Signed out or with nothing to resume, it does nothing.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE) return
        val pending = goAsync()
        WidgetTransport(context.applicationContext).toggle { pending.finish() }
    }

    companion object { const val ACTION_TOGGLE = "org.johnfegan.plextouch.widget.TOGGLE" }
}

private class WidgetTransport(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun toggle(done: () -> Unit) {
        val connected = CompletableDeferred<PlaybackState>()
        var started: CompletableDeferred<Unit>? = null
        val player = PlexPlayer(app, { state ->
            connected.complete(state)
            if (state.trackCount > 0) started?.complete(Unit)
        }, { })
        scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    val state = connected.await()
                    if (!state.ready) return@withTimeoutOrNull
                    if (state.trackCount > 0) { player.toggle(); return@withTimeoutOrNull }
                    val wait = CompletableDeferred<Unit>().also { started = it }
                    if (resume(player)) withTimeoutOrNull(SETTLE_MS) { wait.await() }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { Log.w(TAG, "Widget resume failed: ${error.javaClass.simpleName}")
            } finally {
                player.release()
                done()
                scope.cancel()
            }
        }
    }

    /** True when playback was asked to start. */
    private suspend fun resume(player: PlexPlayer): Boolean {
        val inputs = withContext(Dispatchers.IO) { WidgetUpdates.inputs(app) }
        val connection = inputs.connection ?: return false
        val item = inputs.model(null, WidgetPrivacy()) as? WidgetState.Item ?: return false
        val progress = inputs.history.firstOrNull { it.server == item.server && it.album.id == item.albumId && it.mode == item.mode && it.belongsToScope(inputs.scope) } ?: return false
        val local = inputs.downloads.firstOrNull { it.record.server == connection.serverUrl && it.record.album.id == item.albumId }
        val store = WidgetUpdates.store(app)
        val playback = AlbumPlayback(PlexClientGateways(PlexCache(File(app.cacheDir, PlexCache.DIRECTORY)), store::clientIdentifier), player, store)
        return playback.resume(connection, progress, local, inputs.offlineOnly) == PlayOutcome.Started
    }

    private companion object {
        const val TAG = "WidgetTransport"
        /** Inside the broadcast's time limit, including a track-list fetch for a title that is not downloaded. */
        const val TIMEOUT_MS = 8_000L
        const val SETTLE_MS = 2_000L
    }
}
