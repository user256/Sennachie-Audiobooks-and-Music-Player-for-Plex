package org.johnfegan.plextouch.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.net.InetAddress
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicReference

class PlexPlaylistTest {
    @get:Rule val temporary = TemporaryFolder()
    private val track = PlexTrack("123", "Song", "Artist", "Album", 1000, "/file.mp3", "mp3")
    private val playlist = """{"MediaContainer":{"Metadata":[{"ratingKey":"7","playlistType":"audio","title":"Road & rain","leafCount":2,"smart":false}]}}"""
    private val items = """{"MediaContainer":{"Metadata":[{"ratingKey":"123","playlistItemID":81,"title":"Song","Media":[{"container":"mp3","Part":[{"key":"/file.mp3"}]}]},{"ratingKey":"123","playlistItemID":82,"title":"Song","Media":[{"container":"mp3","Part":[{"key":"/file.mp3"}]}]}]}}"""

    @Test fun createAppendRenameMoveAndRemoveUseCorrectMethodsAndEntryIds() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val first = input.readLine().split(' ')
                    val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    assertTrue(headers.any { it.equals("X-Plex-Token: secret", ignoreCase = true) })
                    val method = first[0]
                    val path = first[1].substringBefore('?')
                    calls += "$method ${URLDecoder.decode(first[1], "UTF-8") }"
                    val body = when {
                        path == "/identity" -> """{"MediaContainer":{"machineIdentifier":"machine"}}"""
                        method == "GET" && path.endsWith("/items") -> items
                        path == "/playlists" -> playlist
                        else -> ""
                    }.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (error: Throwable) { if (!server.isClosed) serverError.set(error) }
        }
        try {
            val connection = PlexConnection("http://127.0.0.1:${server.localPort}", "secret")
            val client = PlexClient(connection, cache = PlexCache(temporary.newFolder()))
            assertEquals("7", client.createPlaylist("Road & rain", listOf(track, track.copy(id = "456"))).id)
            val entries = client.albumTracks("playlist:7")
            assertEquals(listOf("81", "82"), entries.map { it.playlistItemId })
            val before = calls.size
            client.albumTracks("playlist:7")
            assertEquals(before, calls.size)
            client.addToPlaylist("7", listOf(track))
            client.albumTracks("playlist:7")
            client.renamePlaylist("7", "Evening / music")
            client.movePlaylistItem("7", "82", "81")
            client.movePlaylistItem("7", "82", null)
            client.removePlaylistItem("7", "82")
            client.deletePlaylist("7")
            assertTrue(calls.any { it.startsWith("POST /playlists?type=audio&smart=0&title=Road & rain&uri=server://machine/") && it.endsWith("/123,456") })
            assertTrue(calls.any { it.startsWith("PUT /playlists/7/items?uri=server://machine/") })
            assertTrue(calls.contains("PUT /playlists/7?title=Evening / music"))
            assertTrue(calls.contains("PUT /playlists/7/items/82/move?after=81"))
            assertTrue(calls.contains("PUT /playlists/7/items/82/move"))
            assertTrue(calls.contains("DELETE /playlists/7/items/82"))
            assertTrue(calls.contains("DELETE /playlists/7"))
            assertEquals(2, calls.count { it == "GET /playlists/7/items" })
            assertFalse(calls.any { it.contains("secret") })
            assertNull(serverError.get())
        } finally { server.close(); worker.join(1000) }
    }
}
