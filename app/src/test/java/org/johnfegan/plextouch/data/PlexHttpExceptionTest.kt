package org.johnfegan.plextouch.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.uiText

class PlexHttpExceptionTest {
    private fun failWith(status: Int, reason: String): PlexHttpException {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { /* Consume the request. */ }
                socket.getOutputStream().apply { write("HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            }
        }
        try {
            val api = PlexClient(PlexConnection("http://127.0.0.1:${server.localPort}", "fixture"), timeoutMillis = 1000)
            val error = runBlocking { runCatching { api.libraries() }.exceptionOrNull() }
            assertTrue("expected PlexHttpException, got $error", error is PlexHttpException)
            return error as PlexHttpException
        } finally { server.close(); worker.join(1000) }
    }

    @Test fun forbiddenIsTypedFinalAndKeepsTheMessageFormat() {
        val error = failWith(403, "Forbidden")
        assertEquals(403, error.status)
        assertFalse(error.retryable)
        assertTrue(error.authentication)
        assertEquals(uiText(R.string.http_failed, uiText(R.string.operation_plex), 403), error.text)
        assertTrue(error is IOException)
    }

    @Test fun serverErrorsAndRateLimitingAreRetryableButNotAuthentication() {
        val error = failWith(503, "Service Unavailable")
        assertEquals(503, error.status)
        assertTrue(error.retryable)
        assertFalse(error.authentication)
        assertTrue(PlexHttpException(429).retryable)
        assertFalse(PlexHttpException(404).retryable)
        assertTrue(PlexHttpException(401, R.string.operation_sign_in).authentication)
        assertEquals(uiText(R.string.http_failed, uiText(R.string.operation_sign_in), 401), PlexHttpException(401, R.string.operation_sign_in).text)
    }
}
