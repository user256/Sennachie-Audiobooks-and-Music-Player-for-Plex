package org.johnfegan.plextouch.watch

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.wear.shared.CheckpointReceipt
import org.johnfegan.plextouch.wear.shared.CommandResult
import org.johnfegan.plextouch.wear.shared.Decoded
import org.johnfegan.plextouch.wear.shared.TransferManifest
import org.johnfegan.plextouch.wear.shared.TransferRefused
import org.johnfegan.plextouch.wear.shared.WatchBooks
import org.johnfegan.plextouch.wear.shared.WearCodec
import org.johnfegan.plextouch.wear.shared.WearPaths

/**
 * The watch end of the Data Layer (ticket 141): the phone's published state, its replies, and the book's files.
 *
 * Files are accepted only for the manifest the listener asked for and only at the index the watch requested; each
 * arrives as a `.part` file, is checked against the manifest's size and SHA-256 on the watch itself, and only then
 * renamed into place. A failed or interrupted file is deleted, never half-kept.
 */
class WatchListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearPaths.STATE }.forEach { event ->
            event.dataItem.data?.let { WatchRepository.phoneState(this, it) }
        }
        WatchRepository.reach(PhoneReach.CONNECTED)
        requestComplicationUpdate(this)
        // The phone is in reach: a good moment to deliver queued positions.
        runBlocking { PhoneLink.flushPending(this@WatchListenerService) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.MESSAGE) return
        WatchRepository.reach(PhoneReach.CONNECTED)
        when (val decoded = WearCodec.decode(event.data)) {
            is Decoded.Message -> when (val message = decoded.message) {
                is TransferManifest -> manifest(message)
                is TransferRefused -> WatchRepository.update(this) { WatchBooks.refused(it, message.albumId, message.reason) }
                is CheckpointReceipt -> WatchRepository.update(this) { WatchBooks.receipt(it, message.id, message.outcome) }
                is CommandResult -> WatchRepository.commandResult(message)
                else -> Unit
            }
            is Decoded.Unsupported -> WatchRepository.unsupported()
            else -> Log.i(TAG, "Ignored a phone message: ${decoded.javaClass.simpleName}")
        }
    }

    private fun manifest(manifest: TransferManifest) {
        var accepted = false
        WatchRepository.update(this) { book -> WatchBooks.accept(book, manifest, WatchFiles.freeBytes(this)).also { accepted = it.second }.first }
        if (!accepted) return
        WatchFiles.clear(this, keep = manifest.transferId)
        runBlocking { PhoneLink.requestNextFile(this@WatchListenerService) }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val (transferId, index) = WearPaths.parseFilePath(channel.path) ?: return close(channel)
        WatchRepository.load(this)
        if (!WatchBooks.expects(WatchRepository.book.value, transferId, index)) return close(channel)
        val part = WatchFiles.part(this, transferId, index)
        part.parentFile?.mkdirs()
        part.delete()
        Wearable.getChannelClient(this).receiveFile(channel, Uri.fromFile(part), false)
    }

    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        val (transferId, index) = WearPaths.parseFilePath(channel.path) ?: return
        val book = WatchRepository.book.value
        val manifest = book.manifest ?: return
        if (!WatchBooks.expects(book, transferId, index)) return
        val part = WatchFiles.part(this, transferId, index)
        val target = WatchFiles.file(this, manifest, index)
        val ok = closeReason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL && WatchFiles.verify(part, manifest.files[index]) && part.renameTo(target)
        if (!ok) part.delete()
        WatchRepository.update(this) { if (ok) WatchBooks.verified(it, transferId, index) else WatchBooks.failed(it, transferId, index) }
        if (ok) runBlocking { PhoneLink.requestNextFile(this@WatchListenerService) }
    }

    private fun close(channel: ChannelClient.Channel) {
        runCatching { Tasks.await(Wearable.getChannelClient(this).close(channel), 5, TimeUnit.SECONDS) }
    }

    companion object {
        private const val TAG = "WatchListener"

        fun requestComplicationUpdate(context: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, NowPlayingComplicationService::class.java)).requestUpdateAll()
            }
        }
    }
}
