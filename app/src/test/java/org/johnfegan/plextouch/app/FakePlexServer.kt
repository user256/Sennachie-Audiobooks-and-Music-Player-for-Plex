package org.johnfegan.plextouch.app

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.johnfegan.plextouch.data.PlexConnection

/**
 * A loopback HTTP server for driving the real [org.johnfegan.plextouch.data.PlexClient] and its disk cache. `respond` maps
 * a method and path (query included) to a status and JSON body; every request is recorded in [calls] as "METHOD path".
 */
internal class FakePlexServer(private val respond: (method: String, path: String) -> Pair<Int, String>) : Closeable {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val failure = AtomicReference<Throwable?>()
    val connection = PlexConnection("http://127.0.0.1:${socket.localPort}", "secret")
    private val worker = thread(isDaemon = true) {
        try {
            while (!socket.isClosed) socket.accept().use { client ->
                val input = client.getInputStream().bufferedReader()
                val (method, path) = input.readLine().split(' ').let { it[0] to it[1] }
                generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                calls += "$method $path"
                val (status, text) = respond(method, path)
                val body = text.toByteArray()
                client.getOutputStream().apply {
                    write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            }
        } catch (error: Throwable) { if (!socket.isClosed) failure.set(error) }
    }

    fun gets(path: String) = calls.count { it == "GET $path" }

    override fun close() {
        socket.close(); worker.join(1_000)
        failure.get()?.let { throw AssertionError("fake Plex server failed", it) }
    }

    companion object {
        fun items(vararg ids: String, playlist: Boolean = false) = ids.joinToString(",", """{"MediaContainer":{"Metadata":[""", "]}}") { id ->
            val entry = if (playlist) ""","playlistItemID":${id}0""" else ""
            """{"ratingKey":"$id","title":"Track $id"$entry,"duration":1000,"Media":[{"container":"mp3","Part":[{"key":"/library/parts/$id/file.mp3"}]}]}"""
        }
    }
}
