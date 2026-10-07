package org.johnfegan.plextouch.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineDrainerTest {
    private fun event(id: String = "event", scope: String = "a".repeat(64), server: String = "https://plex.test") =
        TimelineEvent(id, scope, server, "10", "11", 0, 30_000, 100_000, PlexTimelineState.PLAYING, 1, "session-123", false)
    private val remote = PlexChapterProgress("11", "One", 100_000, 30_000, 0, 1)

    @Test fun drainsDeliveredEventsInOrderAndStopsAtTheLimit() = runBlocking {
        val queue = ArrayDeque(listOf(event("one"), event("two")))
        val acknowledged = mutableListOf<String>()
        val result = TimelineDrainer("a".repeat(64), "https://plex.test", { queue.removeFirstOrNull() },
            { item, _ -> acknowledged += item.id }, { _, _, _, _ -> error("unexpected block") }, { _, _ -> error("unexpected retry") },
            { TimelineDelivery.Delivered(remote) }).drain()
        assertEquals(TimelineDrainResult.Delivered, result)
        assertEquals(listOf("one", "two"), acknowledged)
    }

    @Test fun blocksAChangedAuthorityBeforeAnyNetworkDelivery() = runBlocking {
        val blocked = mutableListOf<TimelineBlock>()
        var delivered = false
        val result = TimelineDrainer("a".repeat(64), "https://plex.test", { event(scope = "b".repeat(64)) },
            { _, _ -> error("unexpected acknowledgement") }, { _, reason, _, _ -> blocked += reason }, { _, _ -> error("unexpected retry") },
            { delivered = true; TimelineDelivery.Delivered(remote) }).drain()
        assertEquals(TimelineDrainResult.Blocked, result)
        assertEquals(listOf(TimelineBlock.AUTHORITY_CHANGED), blocked)
        assertTrue(!delivered)
    }

    @Test fun transientFailureKeepsTheExactEventForBackoff() = runBlocking {
        val source = event("retry")
        val retried = mutableListOf<String>()
        val result = TimelineDrainer("a".repeat(64), "https://plex.test", { source },
            { _, _ -> error("unexpected acknowledgement") }, { _, _, _, _ -> error("unexpected block") }, { item, _ -> retried += item.id },
            { TimelineDelivery.Retry }).drain()
        assertEquals(TimelineDrainResult.Retry(source), result)
        assertEquals(listOf("retry"), retried)
    }
}
