package org.johnfegan.plextouch.data

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TimelineSyncTest {
    private val scope = "a".repeat(64)
    private fun chapter(position: Long = 30_000, playCount: Int = 0, viewed: Long = 0, id: String = "11") =
        PlexChapterProgress(id, "Chapter", 100_000, position, playCount, viewed)
    private fun event(id: String = "event-0001", position: Long = 30_000, origin: Long = 0, baseline: RemoteChapterBaseline? = null, scope: String = this.scope) =
        TimelineEvent(id, scope, "https://server", "10", "11", origin, position, 100_000, PlexTimelineState.PLAYING, 1_700_000_000_000, "session-0001", false, baseline)

    @Test fun queueIsTokenFreeBoundedAndRoundTripsAcrossRestart() {
        val state = TimelineOutbox.enqueue(TimelineSyncState(), event())
        val json = Gson().toJson(state)
        assertFalse(json.contains("test-token"))
        assertEquals(event(), Gson().fromJson(json, TimelineSyncState::class.java).events.single())
        var many = TimelineSyncState()
        repeat(45) { index -> many = TimelineOutbox.enqueue(many, event("event-${index.toString().padStart(4, '0')}", 30_000L + index, 0, null, (index + 1).toString().padStart(64, '0'))) }
        assertEquals(TimelineOutbox.MAX_EVENTS, many.events.size)
    }

    @Test fun coalesceRetainsLatestRewindRatherThanLargestPosition() {
        val first = event(position = 70_000, origin = 0)
        val rewound = event("event-0002", position = 31_000, origin = 0)
        val state = TimelineOutbox.enqueue(TimelineOutbox.enqueue(TimelineSyncState(), first), rewound)
        assertEquals(listOf(rewound), state.events)
    }

    @Test fun acknowledgedBaselineUpdatesAQueuedNewerPosition() {
        val first = event("event-0001", 30_000)
        val second = event("event-0002", 40_000)
        var state = TimelineOutbox.enqueue(TimelineSyncState(), first)
        state = TimelineOutbox.enqueue(state, second)
        // Model an in-flight first event still being acknowledged after newer progress was queued.
        state = state.copy(events = listOf(first, second))
        val result = TimelineOutbox.acknowledged(state, first, chapter(30_000, viewed = 1))
        assertEquals(listOf(second.copy(baseline = RemoteChapterBaseline.from(chapter(30_000, viewed = 1)))), result.events)
        assertEquals(RemoteChapterBaseline.from(chapter(30_000, viewed = 1)), TimelineOutbox.baseline(result, scope, "https://server", "10", "11"))
    }

    @Test fun retryUsesBoundedBackoffAndAuthenticationIsBlocked() {
        val queued = TimelineOutbox.enqueue(TimelineSyncState(), event())
        val retried = TimelineOutbox.retry(queued, event(), 1_000)
        assertEquals(1, retried.events.single().attempts)
        assertEquals(6_000, retried.events.single().nextAttemptAt)
        assertNull(TimelineOutbox.next(retried, 5_999))
        val blocked = TimelineOutbox.block(retried, retried.events.single(), TimelineBlock.AUTHENTICATION, null, 6_000)
        assertNull(TimelineOutbox.next(blocked, Long.MAX_VALUE))
        assertEquals(TimelineBlock.AUTHENTICATION, blocked.conflicts.single().reason)
    }

    @Test fun accountChangeQuarantinesWithoutSendingAcrossAccounts() {
        val old = TimelineOutbox.enqueue(TimelineSyncState(), event(scope = "b".repeat(64)))
        val protected = TimelineOutbox.accountChanged(old, scope, 10)
        assertEquals(TimelineBlock.AUTHORITY_CHANGED, protected.events.single().blocked)
        assertEquals(TimelineBlock.AUTHORITY_CHANGED, protected.conflicts.single().reason)
    }

    @Test fun noBaselineOnlySendsFreshServerPositionAndNeverPlayedChapter() = runBlocking {
        var sends = 0
        val sync = AudiobookTimelineSync({ listOf(chapter(0, 0)) }, { sends++ })
        assertEquals(TimelineDelivery.Retry, sync.deliver(event(position = 30_000, origin = 0))) // server ignores fixture POST
        assertEquals(1, sends)
        assertTrue(AudiobookTimelineSync({ listOf(chapter(0, 1)) }, { sends++ }).deliver(event(position = 30_000, origin = 0)) is TimelineDelivery.Conflict)
    }

    @Test fun changedRemoteBaselineAndMissingChapterAreConflicts() = runBlocking {
        val baseline = RemoteChapterBaseline.from(chapter(30_000, viewed = 2))
        val changed = AudiobookTimelineSync({ listOf(chapter(31_000, viewed = 2)) }, { error("must not send") }).deliver(event(position = 40_000, baseline = baseline))
        assertTrue(changed is TimelineDelivery.Conflict)
        val missing = AudiobookTimelineSync({ emptyList() }, { error("must not send") }).deliver(event())
        assertEquals(TimelineBlock.MISSING_CHAPTER, (missing as TimelineDelivery.Conflict).reason)
    }

    @Test fun sameDesiredPositionIsIdempotentAfterTimeout() = runBlocking {
        var sends = 0
        val result = AudiobookTimelineSync({ listOf(chapter(30_000, viewed = 7)) }, { sends++ }).deliver(event(position = 30_000, origin = 0))
        assertTrue(result is TimelineDelivery.Delivered)
        assertEquals(0, sends)
    }

    @Test fun sendRequiresReadBackBeforeAcknowledgement() = runBlocking {
        var remote = chapter(0)
        val result = AudiobookTimelineSync({ listOf(remote) }, { remote = chapter(30_000, viewed = 1) }).deliver(event(position = 30_000, origin = 0))
        assertEquals(30_000, (result as TimelineDelivery.Delivered).remote.positionMs)
        val ignored = AudiobookTimelineSync({ listOf(chapter(0)) }, { }).deliver(event(position = 30_000, origin = 0))
        assertEquals(TimelineDelivery.Retry, ignored)
    }

    @Test fun forbiddenRequestStopsRetries() = runBlocking {
        val result = AudiobookTimelineSync({ listOf(chapter(0)) }, { throw PlexHttpException(403) }).deliver(event())
        assertEquals(TimelineDelivery.AuthenticationFailure, result)
        assertEquals(TimelineDelivery.AuthenticationFailure, AudiobookTimelineSync({ throw PlexHttpException(401) }, { }).deliver(event()))
        // Only the status decides: a message that merely mentions 403 is not an authentication failure.
        assertEquals(TimelineDelivery.Retry, AudiobookTimelineSync({ listOf(chapter(0)) }, { throw IllegalStateException("Plex returned HTTP 403") }).deliver(event()))
        assertEquals(TimelineDelivery.Retry, AudiobookTimelineSync({ listOf(chapter(0)) }, { throw PlexHttpException(503) }).deliver(event()))
    }

    @Test fun explicitConflictResolutionDropsBlockedEventAndSetsRemoteBaseline() {
        val blocked = TimelineOutbox.block(TimelineOutbox.enqueue(TimelineSyncState(), event()), event(), TimelineBlock.CONFLICT, chapter(50_000), 1)
        val resolved = TimelineOutbox.resolve(blocked, scope, "https://server", "10", chapter(50_000, viewed = 5))
        assertTrue(resolved.events.isEmpty())
        assertTrue(resolved.conflicts.isEmpty())
        assertEquals(RemoteChapterBaseline.from(chapter(50_000, viewed = 5)), TimelineOutbox.baseline(resolved, scope, "https://server", "10", "11"))
    }
}
