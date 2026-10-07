package org.johnfegan.plextouch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Collections
import kotlin.concurrent.thread

class StoreHardeningTest {
    private val scope = "a".repeat(64)
    private fun event(track: Int) =
        TimelineEvent("event-$track", scope, "https://server", "10", track.toString(), 0, 30_000, 100_000, PlexTimelineState.PLAYING, track.toLong(), "session-0001", false)
    private val remote = PlexChapterProgress("11", "Chapter", 100_000, 30_000, 0, 1)

    @Test fun concurrentEnqueueAndAcknowledgeFromTwoStoresLoseNoEvent() {
        var blob: String? = null
        val lock = Any()
        // Two instances over one preference value, as the player service and sync worker are in one process.
        val service = TimelineStateStore({ blob }, { blob = it }, lock)
        val worker = TimelineStateStore({ blob }, { blob = it }, lock)
        val total = 200
        val acknowledged = Collections.synchronizedList(mutableListOf<String>())
        val producer = thread {
            repeat(total) { index ->
                while (service.state().events.size >= TimelineOutbox.MAX_EVENTS / 2) Thread.yield()
                service.update { TimelineOutbox.enqueue(it, event(index + 1)) }
            }
        }
        val consumer = thread {
            while (producer.isAlive || worker.state().events.isNotEmpty()) {
                val next = TimelineOutbox.next(worker.state(), Long.MAX_VALUE) ?: continue
                worker.update { TimelineOutbox.acknowledged(it, next, remote.copy(id = next.trackId)) }
                acknowledged += next.id
            }
        }
        producer.join()
        consumer.join()
        // A lost update shows up as a re-acknowledged (resurrected) event or a never-seen one.
        assertEquals((1..total).map { "event-$it" }, acknowledged.toList())
        assertTrue(service.state().events.isEmpty())
    }

    @Test fun unreadableOutboxReadsAsEmptyRatherThanThrowing() {
        assertEquals(TimelineSyncState(), TimelineStateStore({ "{not json" }, { error("must not save") }).state())
        assertEquals(TimelineSyncState(), TimelineStateStore({ null }, { error("must not save") }).state())
    }

    @Test fun unreadableSecureStoreIsWipedOnceThenRecreated() {
        var attempts = 0
        var wiped: Throwable? = null
        val (store, recovered) = secureStoreRecovery(
            create = { if (++attempts == 1) throw GeneralSecurityException("keyset does not match this keystore") else "fresh" },
            wipe = { wiped = it },
        )
        assertEquals("fresh", store)
        assertTrue(recovered)
        assertEquals(2, attempts)
        assertTrue(wiped is GeneralSecurityException)
    }

    @Test fun healthySecureStoreIsNeverWiped() {
        assertEquals("intact" to false, secureStoreRecovery({ "intact" }, { error("must not wipe") }))
    }

    @Test fun secondFailureToOpenSecureStorePropagates() {
        var wipes = 0
        assertThrows(IllegalStateException::class.java) {
            secureStoreRecovery<String>({ throw IllegalStateException("keystore unavailable") }, { wipes++ })
        }
        assertEquals(1, wipes)
    }
}
