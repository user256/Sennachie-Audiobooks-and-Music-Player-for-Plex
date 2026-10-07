package org.johnfegan.plextouch.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.johnfegan.plextouch.app.PlaybackSpeeds
import org.johnfegan.plextouch.data.OfflineDownloadManager
import org.johnfegan.plextouch.data.PlexStore
import org.johnfegan.plextouch.data.TimelineSyncScheduler
import org.johnfegan.plextouch.player.PlaybackPosition
import org.johnfegan.plextouch.player.PlaybackState
import org.johnfegan.plextouch.player.PlexPlayer
import org.johnfegan.plextouch.wear.shared.Checkpoint
import org.johnfegan.plextouch.wear.shared.CheckpointReceipt
import org.johnfegan.plextouch.wear.shared.Command
import org.johnfegan.plextouch.wear.shared.CommandOutcome
import org.johnfegan.plextouch.wear.shared.CommandResult
import org.johnfegan.plextouch.wear.shared.Decoded
import org.johnfegan.plextouch.wear.shared.FileDigest
import org.johnfegan.plextouch.wear.shared.ReceiptOutcome
import org.johnfegan.plextouch.wear.shared.RequestFile
import org.johnfegan.plextouch.wear.shared.RequestState
import org.johnfegan.plextouch.wear.shared.RequestTransfer
import org.johnfegan.plextouch.wear.shared.TransferManifest
import org.johnfegan.plextouch.wear.shared.TransferRefusal
import org.johnfegan.plextouch.wear.shared.TransferRefused
import org.johnfegan.plextouch.wear.shared.TransferRemoved
import org.johnfegan.plextouch.wear.shared.WearAction
import org.johnfegan.plextouch.wear.shared.WearCodec
import org.johnfegan.plextouch.wear.shared.WearMessage
import org.johnfegan.plextouch.wear.shared.WearPaths
import org.johnfegan.plextouch.widget.WidgetActionReceiver
import org.johnfegan.plextouch.widget.WidgetUpdates

/**
 * The phone end of the watch companion (ticket 141). Play services starts it for each message on [WearPaths.MESSAGE]
 * from the watch app (same package and signature); callbacks arrive on a background thread, so the short blocking work
 * here (a media-controller round trip, hashing one book) never touches the main thread.
 *
 * Control goes through the same media session as the app, notification and lock screen: a [PlexPlayer] controller is
 * connected, the command applied with [runWatchCommand] and the controller released. Play/pause with nothing loaded is
 * the widget's resume action, so the watch resumes exactly what the widget would. The phone stays the only Plex client:
 * no message carries a token, and watch checkpoints join the timeline outbox instead of reaching Plex directly.
 */
class PhoneWearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.MESSAGE) return
        WearUpdates.watchSeen()
        when (val decoded = WearCodec.decode(event.data)) {
            is Decoded.Message -> handle(event.sourceNodeId, decoded.message)
            // Never the payload: a message is logged by its kind only.
            else -> Log.i(TAG, "Ignored a watch message: ${decoded.javaClass.simpleName}")
        }
    }

    private fun handle(node: String, message: WearMessage) {
        try {
            when (message) {
                is Command -> reply(node, CommandResult(message.action, command(message)))
                is RequestState -> WearUpdates.publishNow(this, message.heldAlbumId)
                is RequestTransfer -> reply(node, transfer(message.albumId))
                is RequestFile -> sendFile(node, message)
                is Checkpoint -> reply(node, CheckpointReceipt(message.id, checkpoint(message)))
                is TransferRemoved -> WatchTransferStore(this).remove(message.transferId)
                else -> Unit
            }
        } catch (error: Exception) {
            Log.w(TAG, "Watch request failed: ${error.javaClass.simpleName}")
        }
    }

    private fun reply(node: String, message: WearMessage) {
        runCatching { Tasks.await(Wearable.getMessageClient(this).sendMessage(node, WearPaths.MESSAGE, WearCodec.encode(message)), TIMEOUT_S, TimeUnit.SECONDS) }
            .onFailure { Log.i(TAG, "Watch reply not sent: ${it.javaClass.simpleName}") }
    }

    private fun command(command: Command): CommandOutcome {
        val live = WearUpdates.live
        val resume = command.action == WearAction.TOGGLE || (command.action == WearAction.PLAY && live?.playing != true)
        if (resume && live == null) {
            sendBroadcast(Intent(this, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_TOGGLE))
            return CommandOutcome.DONE
        }
        // Nothing loaded: answer without binding (and so starting) the playback service.
        if (live == null) return CommandOutcome.NOTHING_LOADED
        val store = PlexStore(this)
        return runBlocking {
            withContext(Dispatchers.Main) {
                val connected = CompletableDeferred<PlaybackState>()
                var position = PlaybackPosition()
                val player = PlexPlayer(applicationContext, { connected.complete(it) }, { position = it })
                try {
                    val state = withTimeoutOrNull(TIMEOUT_S * 1_000) { connected.await() } ?: return@withContext CommandOutcome.FAILED
                    runWatchCommand(command, state, position.positionMs, player) { speed -> PlaybackSpeeds(store, player).choose(speed, state) }
                } finally { player.release() }
            }
        }
    }

    /**
     * Checks the request against [planWatchTransfer], hashes every file from disk (the watch re-hashes what it receives),
     * records the transfer and answers with the manifest. Files follow only when the watch asks for them.
     */
    private fun transfer(albumId: String): WearMessage {
        val store = PlexStore(this)
        val connection = store.connection()
        val scope = connection?.let(store::progressScope)
        val server = connection?.serverUrl
        val readingList = if (scope != null && server != null) store.readingList(scope, server) else emptyList()
        val plan = planWatchTransfer(albumId, server, scope, readingList, store.history(), downloads())
        if (plan !is TransferPlan.Ready) return TransferRefused(albumId, (plan as TransferPlan.Refused).reason)
        val files = plan.files.map { planned ->
            val file = localFile(planned.localUri)?.takeIf { it.length() == planned.entry.bytes } ?: return TransferRefused(albumId, TransferRefusal.NOT_DOWNLOADED)
            planned.entry.copy(sha256 = file.inputStream().use(FileDigest::sha256))
        }
        val album = plan.album
        val record = WatchTransferRecord(UUID.randomUUID().toString(), scope!!, server!!, album, files, System.currentTimeMillis())
        WatchTransferStore(this).put(record)
        return TransferManifest(record.transferId, albumId, album.title, album.artist, files, plan.resumeIndex, plan.resumePositionMs, record.createdAt)
    }

    /** Opens one channel for the requested file and hands it to Play services, which streams it to the watch. */
    private fun sendFile(node: String, request: RequestFile) {
        val record = WatchTransferStore(this).find(request.transferId)
        val entry = record?.files?.getOrNull(request.index)
        val file = entry?.let { wanted ->
            downloads().firstOrNull { it.ready && it.record.server == record.server && it.record.album.id == record.albumId }
                ?.tracks?.firstOrNull { it.id == wanted.trackId }?.localUri?.let { localFile(it) }
        }?.takeIf { it.length() == entry.bytes }
        if (record == null || file == null) {
            reply(node, TransferRefused(record?.albumId.orEmpty(), TransferRefusal.NOT_DOWNLOADED))
            return
        }
        val channels = Wearable.getChannelClient(this)
        val channel = Tasks.await(channels.openChannel(node, WearPaths.filePath(request.transferId, request.index)), TIMEOUT_S, TimeUnit.SECONDS)
        // Play services streams the file; the watch verifies it and asks for the next one, so nothing here waits for it.
        channels.sendFile(channel, Uri.fromFile(file)).addOnFailureListener { channels.close(channel) }
    }

    private fun checkpoint(checkpoint: Checkpoint): ReceiptOutcome {
        val store = PlexStore(this)
        val connection = store.connection()
        val scope = connection?.let(store::progressScope)
        val record = WatchTransferStore(this).find(checkpoint.transferId)
        val live = WearUpdates.live
        val baseline = record?.let { store.timelineBaseline(it.scope, it.server, it.albumId, checkpoint.trackId) }
        val sync = watchCheckpointSync(checkpoint, record, scope, connection?.serverUrl, store.history(), store.offlineOnly(), baseline,
            live?.takeIf { it.server == record?.server && it.scope == record.scope }?.album?.id)
        sync.event?.let { store.enqueueTimeline(it); TimelineSyncScheduler.schedule(this) }
        sync.progress?.let { store.saveProgress(it); WidgetUpdates.request(this) }
        return sync.outcome
    }

    private fun downloads() = runBlocking { OfflineDownloadManager(this@PhoneWearListenerService).snapshots() }

    private companion object {
        const val TAG = "PhoneWearListener"
        const val TIMEOUT_S = 10L

        /** Only files inside the app's own offline folder are ever sent. */
        fun Context.localFile(uri: String): File? {
            val root = OfflineDownloadManager.offlineRoot(this) ?: return null
            val file = uri.toUri().path?.let { File(it).canonicalFile } ?: return null
            return file.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }
        }
    }
}
