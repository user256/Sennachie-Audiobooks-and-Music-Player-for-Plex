package org.johnfegan.plextouch.data

import androidx.compose.runtime.Immutable
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText

/** Where an advertised address leads (ticket 136). Declaration order is the preference order. */
enum class RouteKind { LOCAL, REMOTE, RELAY }

/** One address Plex advertises for a server. It carries no token, so it is safe to show, keep and log. */
@Immutable
data class PlexRoute(val uri: String, val kind: RouteKind, val secure: Boolean) {
    /** Scheme, host and port only: anything else in an advertised URI (user info, query) is never shown. */
    val address: String get() = displayAddress(uri)

    companion object {
        /** A route for a URI without Plex's flags (a manual address): treated as remote, secure only over https. */
        fun of(uri: String, local: Boolean = false, relay: Boolean = false) = PlexRoute(
            uri.trim().trimEnd('/'),
            when { relay -> RouteKind.RELAY; local -> RouteKind.LOCAL; else -> RouteKind.REMOTE },
            uri.trim().startsWith("https://", ignoreCase = true),
        )
    }
}

/** Local before remote before relay; within each, https before http. Stable, so Plex's own order breaks ties. */
fun rankRoutes(routes: List<PlexRoute>): List<PlexRoute> =
    routes.distinctBy { it.uri }.sortedWith(compareBy<PlexRoute> { it.kind.ordinal }.thenBy { !it.secure })

fun sameAddress(a: String, b: String): Boolean = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

fun displayAddress(uri: String): String = runCatching {
    val parsed = URI(uri.trim())
    val host = parsed.host ?: return@runCatching null
    "${parsed.scheme}://$host${if (parsed.port >= 0) ":${parsed.port}" else ""}"
}.getOrNull() ?: uri.substringBefore('?').substringAfter('@')

/** Measures one route; returns the round trip in milliseconds or throws. */
fun interface RouteProbe {
    suspend fun latencyMs(route: PlexRoute): Long
}

/**
 * `GET <route>/identity` with a short timeout. Plex answers it without authentication, so no token is ever sent on a
 * diagnostic request, and nothing here is logged.
 */
class IdentityProbe(private val timeoutMs: Int = 4_000) : RouteProbe {
    override suspend fun latencyMs(route: PlexRoute): Long = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val connection = (URL("${route.uri.trimEnd('/')}/identity").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw PlexHttpException(status, R.string.operation_route_test)
            (System.nanoTime() - started) / 1_000_000
        } finally {
            connection.disconnect()
        }
    }
}

/** A route test's failure for the screen: a fixed reason per failure class, never the exception's own message. */
fun routeFailure(error: Throwable): UiText = when (error) {
    is SocketTimeoutException -> uiText(R.string.route_failed_timeout)
    is UnknownHostException -> uiText(R.string.route_failed_unknown_host)
    is SSLException -> uiText(R.string.route_failed_tls)
    is ConnectException, is NoRouteToHostException -> uiText(R.string.route_failed_refused)
    is PlexHttpException -> uiText(R.string.route_failed_http, error.status)
    else -> uiText(R.string.route_failed_other)
}
