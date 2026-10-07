package org.johnfegan.plextouch.app

import kotlinx.coroutines.CancellationException
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateway
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.data.PlexPlaylist
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText

sealed interface MutationResult {
    /** The change is in Plex; `playlists` is the refreshed list and `notice` what the change was, if worth saying. */
    data class Done(val playlists: List<PlexPlaylist>, val notice: UiText?) : MutationResult
    data class Failed(val error: UiText) : MutationResult
}

/** Playlist edits run against a cache-bypassing gateway and end with a fresh playlist list, so the screens never show a guess. */
class PlaylistMutations(private val gateways: PlexGateways) {
    suspend fun mutate(connection: PlexConnection, label: UiText, work: suspend (PlexGateway) -> UiText?): MutationResult = try {
        val api = gateways.open(connection, force = true)
        val notice = work(api)
        MutationResult.Done(api.playlists(), notice)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { MutationResult.Failed(uiText(R.string.playlist_mutation_failed, label)) }
}
