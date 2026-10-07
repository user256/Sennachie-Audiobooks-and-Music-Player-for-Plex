package org.johnfegan.plextouch.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

sealed interface TimelineDrainResult {
    data object Empty : TimelineDrainResult
    data object Delivered : TimelineDrainResult
    data object Blocked : TimelineDrainResult
    data class Retry(val event: TimelineEvent) : TimelineDrainResult
}

/** Shared by the player service and background worker so conflict rules cannot drift. */
class TimelineDrainer(
    private val activeScope: String,
    private val activeServer: String,
    private val next: (Long) -> TimelineEvent?,
    private val acknowledge: (TimelineEvent, PlexChapterProgress) -> Unit,
    private val block: (TimelineEvent, TimelineBlock, PlexChapterProgress?, Long) -> Unit,
    private val retry: (TimelineEvent, Long) -> Unit,
    private val deliver: suspend (TimelineEvent) -> TimelineDelivery,
) {
    suspend fun drain(limit: Int = MAX_EVENTS_PER_RUN): TimelineDrainResult {
        var delivered = false
        repeat(limit) {
            val now = System.currentTimeMillis()
            val event = next(now) ?: return if (delivered) TimelineDrainResult.Delivered else TimelineDrainResult.Empty
            if (event.scope != activeScope || event.server != activeServer) {
                block(event, TimelineBlock.AUTHORITY_CHANGED, null, now)
                return TimelineDrainResult.Blocked
            }
            when (val outcome = deliver(event)) {
                is TimelineDelivery.Delivered -> { acknowledge(event, outcome.remote); delivered = true }
                is TimelineDelivery.Conflict -> { block(event, outcome.reason, outcome.remote, now); return TimelineDrainResult.Blocked }
                TimelineDelivery.AuthenticationFailure -> { block(event, TimelineBlock.AUTHENTICATION, null, now); return TimelineDrainResult.Blocked }
                TimelineDelivery.Retry -> { retry(event, now); return TimelineDrainResult.Retry(event) }
            }
        }
        return TimelineDrainResult.Delivered
    }

    companion object { const val MAX_EVENTS_PER_RUN = 4 }
}

/** Connected-only, unique work. It carries no token or progress data as WorkManager input. */
object TimelineSyncScheduler {
    private const val UNIQUE_NAME = "plex_audiobook_timeline_sync"

    fun schedule(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
    }

    /** A service retry needs a durable replacement in case Android stops the process before its short in-process delay. */
    fun scheduleRetry(context: Context, delayMillis: Long) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request(delayMillis))
    }

    /** Sign-out: nothing is left to send and no token remains to send it with. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
    }

    private fun request(delayMillis: Long = 0) = OneTimeWorkRequestBuilder<TimelineSyncWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        .build()
}

/** WorkManager and the media service share this process; one sender prevents duplicate races. */
object TimelineSyncGate {
    private val mutex = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

class TimelineSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return TimelineSyncGate.exclusive {
            val store = PlexStore(applicationContext)
            if (store.offlineOnly()) return@exclusive Result.success()
            val connection = store.connection() ?: return@exclusive Result.success()
            val scope = store.progressScope(connection)
            store.protectTimelineScopes(scope, System.currentTimeMillis())
            val client = PlexClient(connection, clientIdentifier = store.clientIdentifier())
            val result = TimelineDrainer(
                scope, connection.serverUrl, store::nextTimeline, store::acknowledgeTimeline,
                store::blockTimeline, store::retryTimeline,
                AudiobookTimelineSync(client::audiobookProgress, client::reportAudiobookTimeline)::deliver,
            ).drain()
            when (result) {
                is TimelineDrainResult.Retry -> Result.retry()
                TimelineDrainResult.Delivered -> if (store.nextTimeline(System.currentTimeMillis()) != null) Result.retry() else Result.success()
                TimelineDrainResult.Empty, TimelineDrainResult.Blocked -> Result.success()
            }
        }
    }
}
