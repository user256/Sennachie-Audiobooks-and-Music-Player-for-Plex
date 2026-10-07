package org.johnfegan.plextouch.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.johnfegan.plextouch.wear.shared.RequestFile
import org.johnfegan.plextouch.wear.shared.RequestState
import org.johnfegan.plextouch.wear.shared.WearCodec
import org.johnfegan.plextouch.wear.shared.WearMessage
import org.johnfegan.plextouch.wear.shared.WearPaths

/**
 * Messages to the phone app over the Wear Data Layer (ticket 141). The phone is found by the capability its app declares,
 * so a phone without the app reads as [PhoneReach.NOT_INSTALLED] and a paired phone out of range as
 * [PhoneReach.UNREACHABLE]; every send updates [WatchRepository.reach]. Nothing sent carries a credential.
 */
object PhoneLink {
    private const val TAG = "PhoneLink"
    private const val TIMEOUT_S = 10L

    suspend fun refresh(context: Context): PhoneReach = withContext(Dispatchers.IO) {
        val reach = runCatching {
            val client = Wearable.getCapabilityClient(context)
            val reachable = Tasks.await(client.getCapability(WearPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE), TIMEOUT_S, TimeUnit.SECONDS).nodes
            when {
                reachable.isNotEmpty() -> PhoneReach.CONNECTED
                Tasks.await(client.getCapability(WearPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_ALL), TIMEOUT_S, TimeUnit.SECONDS).nodes.isNotEmpty() -> PhoneReach.UNREACHABLE
                // No phone has ever advertised the app; if no phone is connected at all, it may simply be out of range.
                Tasks.await(Wearable.getNodeClient(context).connectedNodes, TIMEOUT_S, TimeUnit.SECONDS).isEmpty() -> PhoneReach.UNREACHABLE
                else -> PhoneReach.NOT_INSTALLED
            }
        }.getOrDefault(PhoneReach.UNREACHABLE)
        WatchRepository.reach(reach)
        reach
    }

    private fun phone(context: Context): Node? = runCatching {
        val nodes = Tasks.await(Wearable.getCapabilityClient(context).getCapability(WearPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE), TIMEOUT_S, TimeUnit.SECONDS).nodes
        nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }.getOrNull()

    /** True when the phone's Play services accepted the message; delivery to the app is confirmed by its reply. */
    suspend fun send(context: Context, message: WearMessage): Boolean = withContext(Dispatchers.IO) {
        val node = phone(context)
        if (node == null) {
            WatchRepository.reach(PhoneReach.UNREACHABLE)
            return@withContext false
        }
        val sent = runCatching {
            Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, WearPaths.MESSAGE, WearCodec.encode(message)), TIMEOUT_S, TimeUnit.SECONDS)
        }.onFailure { Log.i(TAG, "Not sent: ${it.javaClass.simpleName}") }.isSuccess
        WatchRepository.reach(if (sent) PhoneReach.CONNECTED else PhoneReach.UNREACHABLE)
        sent
    }

    /** Asks the phone for fresh state, naming the book this watch holds so the phone includes its own position in it. */
    suspend fun requestState(context: Context): Boolean {
        WatchRepository.load(context)
        return send(context, RequestState(WatchRepository.book.value.manifest?.albumId))
    }

    /** Sends every queued checkpoint; each is dropped only when the phone's receipt arrives. */
    suspend fun flushPending(context: Context) {
        WatchRepository.load(context)
        for (checkpoint in WatchRepository.book.value.pending) {
            if (!send(context, checkpoint)) return
        }
    }

    /** Asks for the next file the book is waiting for, if any. */
    suspend fun requestNextFile(context: Context) {
        val book = WatchRepository.book.value
        val manifest = book.manifest ?: return
        val index = book.receiving ?: return
        send(context, RequestFile(manifest.transferId, index))
    }
}
