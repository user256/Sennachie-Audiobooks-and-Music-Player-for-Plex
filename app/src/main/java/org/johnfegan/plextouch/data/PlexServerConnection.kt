package org.johnfegan.plextouch.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.InvalidStateException
import org.johnfegan.plextouch.ui.uiText

/**
 * Plex advertises all server interfaces, including unreachable virtual adapters, so every
 * candidate is probed at once and the first success wins; the remaining probes are cancelled.
 *
 * Preference is by completion order, not list order: if two addresses both answer, the earlier
 * candidate wins only when it finishes first. That is accepted so a dead interface's timeout never
 * delays a live one. Cancellation propagates to the caller; if every candidate fails the error
 * describes the server as a whole rather than any single address.
 */
suspend fun <T> firstReachableConnection(
    connections: List<PlexConnection>,
    probe: suspend (PlexConnection) -> T,
): Pair<PlexConnection, T> = coroutineScope {
    val probes: List<Deferred<Result<Pair<PlexConnection, T>>>> = connections.map { connection ->
        async {
            try {
                Result.success(connection to probe(connection))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                // Keep the account login and let the other Plex-advertised routes finish.
                Result.failure(failed)
            }
        }
    }
    val pending = probes.toMutableList()
    while (pending.isNotEmpty()) {
        val finished = select<Deferred<Result<Pair<PlexConnection, T>>>> { pending.forEach { probe -> probe.onJoin { probe } } }
        pending -= finished
        val winner = finished.await().getOrNull() ?: continue
        pending.forEach { it.cancel() }
        return@coroutineScope winner
    }
    throw InvalidStateException(uiText(R.string.server_unreachable))
}
