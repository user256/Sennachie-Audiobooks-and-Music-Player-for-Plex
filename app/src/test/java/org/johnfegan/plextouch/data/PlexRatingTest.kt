package org.johnfegan.plextouch.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PlexRatingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun personalRatingsParseAndHeartUsesPlexEndpointAndInvalidatesCachedAlbums() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val error = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val first = input.readLine()
                    val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    assertTrue(headers.any { it.equals("X-Plex-Token: test-token", true) })
                    calls += first.substringBefore(" HTTP/")
                    val body = if (first.startsWith("GET")) """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Five stars","parentTitle":"Artist","userRating":10},{"ratingKey":"2","title":"Four stars","userRating":8},{"ratingKey":"3","title":"Critic score only","rating":10}]}}""" else ""
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (failure: Throwable) { if (!server.isClosed) error.set(failure) }
        }
        try {
            val api = PlexClient(PlexConnection("http://127.0.0.1:${server.localPort}", "test-token"), cache = PlexCache(temporary.newFolder()))
            assertEquals(listOf("1"), api.albums("2").filter { it.isFavourite }.map { it.id })
            assertTrue(api.cachedAlbumList("2")!!.fresh)
            api.albums("2")
            assertEquals(1, calls.size)
            api.setAlbumFavourite("2", "1", true)
            assertNull(api.cachedAlbums("2"))
            api.albums("2")
            api.setAlbumFavourite("2", "1", false)
            assertTrue(calls.contains("PUT /:/rate?key=1&identifier=com.plexapp.plugins.library&rating=10"))
            assertTrue(calls.contains("PUT /:/rate?key=1&identifier=com.plexapp.plugins.library&rating=-1"))
            assertEquals(2, calls.count { it.startsWith("GET") })
            assertNull(error.get())
        } finally { server.close(); worker.join(1000) }
    }
}
