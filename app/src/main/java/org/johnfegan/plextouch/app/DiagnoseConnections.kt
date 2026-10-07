package org.johnfegan.plextouch.app

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.PlexAccountGateway
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexRoute
import org.johnfegan.plextouch.data.RouteKind
import org.johnfegan.plextouch.data.RouteProbe
import org.johnfegan.plextouch.data.rankRoutes
import org.johnfegan.plextouch.data.routeFailure
import org.johnfegan.plextouch.data.sameAddress
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText
import org.johnfegan.plextouch.ui.userText

@Immutable
sealed interface RouteResult {
    data object Untested : RouteResult
    data object Testing : RouteResult
    data class Reachable(val latencyMs: Long) : RouteResult
    data class Failed(val reason: UiText) : RouteResult
}

/** One advertised route as the diagnostics list shows it: never a token, only the address and what was measured. */
@Immutable
data class RouteDiagnosis(val route: PlexRoute, val active: Boolean, val result: RouteResult = RouteResult.Untested, val preferred: Boolean = false)

/** The server's routes in preference order. `notice` explains a partial list (no Plex sign-in, discovery failed). */
@Immutable
data class ConnectionDiagnostics(
    val serverName: String? = null,
    val routes: List<RouteDiagnosis> = emptyList(),
    val running: Boolean = false,
    val notice: UiText? = null,
) {
    /** Re-marks the preferred route after a result arrives: the first reachable route in preference order. */
    fun with(route: PlexRoute, result: RouteResult): ConnectionDiagnostics {
        val updated = routes.map { if (it.route == route) it.copy(result = result) else it }
        val preferred = preferredRoute(updated)
        return copy(routes = updated.map { it.copy(preferred = it.route == preferred) })
    }
}

/** First reachable in preference order (local, remote, relay; https first): latency is reported, it does not reorder. */
fun preferredRoute(routes: List<RouteDiagnosis>): PlexRoute? = routes.firstOrNull { it.result is RouteResult.Reachable }?.route

/**
 * Why a route is marked, for the line under it. The app connects to whichever advertised address answers first (ticket
 * 117), so the active route is not always the preferred one; this says which case applies.
 */
fun routeExplanation(diagnosis: RouteDiagnosis): UiText? = when {
    diagnosis.active && diagnosis.result is RouteResult.Failed -> uiText(R.string.route_active_failing)
    diagnosis.active && diagnosis.preferred -> uiText(R.string.route_active_preferred)
    diagnosis.active && diagnosis.result is RouteResult.Reachable -> uiText(R.string.route_active_not_preferred)
    diagnosis.active -> uiText(R.string.route_active_saved)
    diagnosis.preferred -> uiText(R.string.route_preferred_not_active)
    else -> null
}

/**
 * Connection diagnostics for the saved server (ticket 136). It only reads: plex.tv for the advertised routes and each
 * route's unauthenticated `/identity`. It never saves, replaces or clears the saved connection or tokens.
 */
class DiagnoseConnections(private val account: PlexAccountGateway, private val probe: RouteProbe) {
    /** The saved server's advertised routes, ranked; just the saved address (with a notice) when plex.tv cannot say more. */
    suspend fun routes(saved: PlexConnection, accountToken: String?): ConnectionDiagnostics {
        val fallback = listOf(PlexRoute.of(saved.serverUrl))
        if (accountToken == null) return diagnostics(null, fallback, saved, uiText(R.string.diagnostics_manual_only))
        return try {
            val server = account.servers(accountToken).firstOrNull { server -> server.routes.any { sameAddress(it.uri, saved.serverUrl) } }
                ?: return diagnostics(null, fallback, saved, uiText(R.string.diagnostics_not_advertised))
            diagnostics(server.name, server.routes, saved, null)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            diagnostics(null, fallback, saved, uiText(R.string.diagnostics_discovery_failed, error.userText(uiText(R.string.route_failed_other))))
        }
    }

    /** Tests every route at once; each result is reported as it lands. Cancelling the caller cancels every probe. */
    suspend fun test(routes: List<PlexRoute>, report: (PlexRoute, RouteResult) -> Unit) = coroutineScope {
        routes.map { route ->
            async {
                val result = try {
                    RouteResult.Reachable(probe.latencyMs(route))
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { RouteResult.Failed(routeFailure(error)) }
                report(route, result)
            }
        }.awaitAll()
        Unit
    }

    private fun diagnostics(name: String?, routes: List<PlexRoute>, saved: PlexConnection, notice: UiText?): ConnectionDiagnostics {
        val ranked = rankRoutes(routes).let { list ->
            // The saved address is always listed, even if Plex no longer advertises it.
            if (list.any { sameAddress(it.uri, saved.serverUrl) }) list else list + PlexRoute.of(saved.serverUrl)
        }
        return ConnectionDiagnostics(name, ranked.map { RouteDiagnosis(it, active = sameAddress(it.uri, saved.serverUrl)) }, notice = notice)
    }

    companion object {
        /** For the label: which string names each kind. */
        fun kindLabel(kind: RouteKind): Int = when (kind) {
            RouteKind.LOCAL -> R.string.route_kind_local
            RouteKind.REMOTE -> R.string.route_kind_remote
            RouteKind.RELAY -> R.string.route_kind_relay
        }
    }
}
