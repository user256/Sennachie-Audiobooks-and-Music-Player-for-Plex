package org.johnfegan.plextouch.player

import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PlexTokenHeadersTest {
    private val track = PlexTrack("11", "One", "Narrator", "Book", 1000, "/library/parts/1/file.mp3", "mp3")

    @Test fun tokenIsSentAsAHeaderOnlyToTheStoredServer() {
        var connection: PlexConnection? = PlexConnection("http://plex.local:32400/", "secret-token")
        val headers = PlexTokenHeaders { connection }
        assertEquals(mapOf("X-Plex-Token" to "secret-token"), headers.headersFor("plex.local"))
        assertEquals(mapOf("X-Plex-Token" to "secret-token"), headers.headersFor("PLEX.LOCAL"))
        // Another host never receives this server's token; a file:// download has no host at all.
        assertEquals(emptyMap<String, String>(), headers.headersFor("other.example"))
        assertEquals(emptyMap<String, String>(), headers.headersFor(null))
        assertEquals(emptyMap<String, String>(), headers.headersFor(""))
        // The token is resolved per request: a re-login or sign-out takes effect without a restart.
        connection = PlexConnection("http://plex.local:32400", "rotated")
        assertEquals(mapOf("X-Plex-Token" to "rotated"), headers.headersFor("plex.local"))
        connection = null
        assertEquals(emptyMap<String, String>(), headers.headersFor("plex.local"))
    }

    @Test fun playbackUriCarriesNoToken() {
        val uri = playbackUri(track, "http://plex.local:32400/")
        assertEquals("http://plex.local:32400/library/parts/1/file.mp3", uri)
        assertFalse(uri.contains("X-Plex-Token"))
        assertEquals("plex.local", URI(uri).host)
    }

    @Test fun fixtureServerSeesTheTokenInHeadersAndNeverInTheRequestLine() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val requestLine = AtomicReference<String?>()
        val tokenHeaders = AtomicReference<List<String>>(emptyList())
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                requestLine.set(input.readLine())
                tokenHeaders.set(generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.filter { it.startsWith("X-Plex-Token:", true) }.toList())
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            }
        }
        try {
            val connection = PlexConnection("http://127.0.0.1:${server.localPort}", "secret-token")
            val uri = URL(playbackUri(track, connection.serverUrl))
            val request = (uri.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1000; readTimeout = 1000
                PlexTokenHeaders { connection }.headersFor(uri.host).forEach { (name, value) -> setRequestProperty(name, value) }
            }
            assertEquals(200, request.responseCode)
            request.disconnect()
            assertEquals("GET /library/parts/1/file.mp3 HTTP/1.1", requestLine.get())
            assertTrue(tokenHeaders.get().single().equals("X-Plex-Token: secret-token", true))
        } finally { server.close(); worker.join(1000) }
    }
}
