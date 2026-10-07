package org.johnfegan.plextouch.player

import org.johnfegan.plextouch.data.PlexConnection
import java.net.URI

/**
 * Resolves the `X-Plex-Token` header for a phone playback request at the moment the request is made.
 *
 * The connection is read on every call, so a re-login (new token or server) takes effect without
 * restarting the playback service. The header is only added for requests to the stored server's host:
 * a local `file://` download has no host and gets nothing, and a stream from any other host never
 * receives this server's token.
 */
class PlexTokenHeaders(private val connection: () -> PlexConnection?) {
    fun headersFor(host: String?): Map<String, String> {
        if (host.isNullOrBlank()) return emptyMap()
        val current = connection() ?: return emptyMap()
        val serverHost = runCatching { URI(current.serverUrl.trim()).host }.getOrNull() ?: return emptyMap()
        if (!serverHost.equals(host, ignoreCase = true) || current.token.isBlank()) return emptyMap()
        return mapOf(HEADER to current.token)
    }

    companion object { const val HEADER = "X-Plex-Token" }
}
