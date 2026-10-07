package org.johnfegan.plextouch.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.uiText

class PlexProgressApiTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun uncachedReadPostAndVerificationUseDocumentedTimelineContract() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>()
        val offset = AtomicLong(40_000)
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val first = input.readLine()
                    val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    assertTrue(headers.any { it.equals("X-Plex-Token: fixture-token", true) })
                    assertTrue(headers.any { it.equals("X-Plex-Client-Identifier: unique-phone", true) })
                    assertFalse(first.contains("fixture-token"))
                    calls += first.substringBefore(" HTTP/")
                    if (first.startsWith("POST")) offset.set(20_000)
                    val body = if (first.startsWith("POST")) "" else """{"MediaContainer":{"Metadata":[{"ratingKey":"11","title":"One","duration":100000,"viewOffset":${offset.get()},"viewCount":2,"lastViewedAt":1700000000},{"ratingKey":"12","title":"Two","duration":90000}]}}"""
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (error: Throwable) { if (!server.isClosed) failure.set(error) }
        }
        try {
            val connection = PlexConnection("http://127.0.0.1:${server.localPort}", "fixture-token")
            val cache = PlexCache(temporary.newFolder())
            cache.write(connection, "/library/metadata/10/children?includeChapters=1", """{"MediaContainer":{"Metadata":[]}}""")
            val api = PlexClient(connection, timeoutMillis = 2000, cache = cache, clientIdentifier = "unique-phone")
            val before = api.audiobookProgress("10")
            assertEquals(40_000L, before.first().positionMs)
            assertEquals(2, before.first().playCount)
            assertEquals(0L, before.last().positionMs)
            assertEquals(0, before.last().playCount)
            AudiobookProgressExchange({ api.audiobookProgress("10") }, api::sendAudiobookCheckpoint)
                .export(before, PhoneCheckpoint("11", 20_000, 100_000, 1_700_000_123_000)) { true }
            assertEquals(3, calls.count { it.startsWith("GET") })
            assertEquals("POST /:/timeline?ratingKey=11&key=%2Flibrary%2Fmetadata%2F11&state=stopped&time=20000&duration=100000&offline=1&updated=1700000123", calls.single { it.startsWith("POST") })
            assertNull(failure.get())
        } finally { server.close(); worker.join(1000) }
    }

    @Test fun rejectInvalidIdentifierBeforeNetworking() = runBlocking {
        val api = PlexClient(PlexConnection("http://127.0.0.1:1", "fixture"))
        assertTrue(runCatching { api.audiobookProgress("playlist:10") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { api.sendAudiobookCheckpoint(PhoneCheckpoint("11&x=1", 20, 100, 1000)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun deniedTimelineIsReportedAndNeverRetried() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val seen = AtomicReference<String?>()
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                seen.set(input.readLine())
                while (!input.readLine().isNullOrEmpty()) { /* Consume headers. */ }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                }
            }
        }
        try {
            val api = PlexClient(PlexConnection("http://127.0.0.1:${server.localPort}", "fixture"), timeoutMillis = 1000)
            val result = runCatching { api.sendAudiobookCheckpoint(PhoneCheckpoint("11", 20, 100, 1000)) }
            val denied = result.exceptionOrNull() as PlexHttpException
            assertEquals(uiText(R.string.http_failed, uiText(R.string.operation_plex), 403), denied.text)
            assertEquals(403, denied.status)
            assertFalse(denied.retryable)
            assertTrue(denied.authentication)
            assertTrue(seen.get().orEmpty().startsWith("POST /:/timeline?"))
        } finally { server.close(); worker.join(1000) }
    }

    @Test fun liveTimelineUsesSessionHeaderAndReservesOfflineMarkerForDeferredEvents() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val sessionHeaders = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            try {
                repeat(2) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        calls += input.readLine().substringBefore(" HTTP/")
                        generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.forEach { header ->
                            if (header.startsWith("X-Plex-Session-Identifier:", true)) sessionHeaders += header
                        }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                        }
                    }
                }
            } catch (_: java.net.SocketException) { /* Closed during cleanup. */ }
        }
        try {
            val api = PlexClient(PlexConnection("http://127.0.0.1:${server.localPort}", "fixture"), timeoutMillis = 1000, clientIdentifier = "unique-phone")
            val base = TimelineEvent("event", "a".repeat(64), "server", "10", "11", 0, 30_000, 100_000,
                PlexTimelineState.PLAYING, 1_700_000_123_000, "9d5dbf86-3e29-4717-b4a8-17bd85b2b482", false)
            api.reportAudiobookTimeline(base)
            api.reportAudiobookTimeline(base.copy(offline = true))
            assertEquals("POST /:/timeline?ratingKey=11&key=%2Flibrary%2Fmetadata%2F11&state=playing&time=30000&duration=100000", calls[0])
            assertEquals("POST /:/timeline?ratingKey=11&key=%2Flibrary%2Fmetadata%2F11&state=playing&time=30000&duration=100000&offline=1&updated=1700000123", calls[1])
            assertEquals(2, sessionHeaders.size)
            assertTrue(sessionHeaders.all { it.equals("X-Plex-Session-Identifier: 9d5dbf86-3e29-4717-b4a8-17bd85b2b482", true) })
        } finally { server.close(); worker.join(1000) }
    }
}
