package org.johnfegan.plextouch.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.uiText
import org.johnfegan.plextouch.ui.UserFacing

class PlexServerConnectionTest {
    private val virtual = PlexConnection("https://virtual.example:32400", "test")
    private val lan = PlexConnection("https://lan.example:32400", "test")

    @Test fun unreachableFirstInterfaceFallsBackToWorkingAddress() = runBlocking {
        val attempted = mutableListOf<PlexConnection>()
        val result = firstReachableConnection(listOf(virtual, lan)) {
            attempted += it
            if (it == virtual) throw IOException("No route")
            listOf("Audiobooks", "Music")
        }
        // Probes run concurrently, so only the set of attempts is guaranteed, not their order.
        assertEquals(setOf(virtual, lan), attempted.toSet())
        assertEquals(lan, result.first)
        assertEquals(listOf("Audiobooks", "Music"), result.second)
    }

    @Test fun cancellationPropagatesAndStopsRemainingProbes() = runBlocking {
        var lanFinished = false
        var lanCancelled = false
        try {
            firstReachableConnection(listOf(virtual, lan)) {
                if (it == virtual) throw CancellationException()
                try {
                    delay(500)
                    lanFinished = true
                } catch (cancelled: CancellationException) {
                    lanCancelled = true
                    throw cancelled
                }
                listOf("Music")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue("remaining probe should be cancelled", lanCancelled)
        assertFalse("cancellation must not wait for other addresses", lanFinished)
    }

    @Test fun slowFirstCandidateDoesNotDelayFastSecondAndLoserIsCancelled() = runBlocking {
        var virtualCancelled = false
        val started = System.nanoTime()
        val result = firstReachableConnection(listOf(virtual, lan)) {
            if (it == virtual) {
                try { delay(500) } catch (cancelled: CancellationException) { virtualCancelled = true; throw cancelled }
                throw IOException("Timed out")
            }
            delay(50)
            listOf("Music")
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(lan, result.first)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 400)
        assertTrue("slow probe should be cancelled once a winner exists", virtualCancelled)
    }

    @Test fun allFailuresReportTheServerAsUnreachable() = runBlocking {
        try {
            firstReachableConnection(listOf(virtual, lan)) { throw IOException("No route to ${it.serverUrl}") }
            fail("Must not succeed")
        } catch (error: IllegalStateException) {
            assertEquals(uiText(R.string.server_unreachable), (error as UserFacing).text)
        }
    }
}
