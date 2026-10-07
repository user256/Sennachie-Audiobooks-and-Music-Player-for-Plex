package org.johnfegan.plextouch.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PlexMetadataTest {
    @Test fun albumEditorUsesLockedPlexFields() = runBlocking {
        val request = AtomicReference<String?>()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                request.set(input.readLine().substringBefore(" HTTP/"))
                while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            }
        }
        try {
            val api = PlexClient(PlexConnection("http://127.0.0.1:${server.localPort}", "token"), timeoutMillis = 1000)
            api.editAlbumMetadata("3", "42", "A New Album", "New Artist", 2024)
            assertEquals("PUT /library/metadata/42?title.value=A+New+Album&title.locked=1&parentTitle.value=New+Artist&parentTitle.locked=1&year.value=2024&year.locked=1", request.get())
            assertTrue(runCatching { api.editAlbumMetadata("3", "42", "", "Artist", null) }.exceptionOrNull() is IllegalArgumentException)
        } finally { server.close(); worker.join(1000) }
    }
}
