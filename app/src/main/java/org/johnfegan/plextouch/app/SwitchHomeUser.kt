package org.johnfegan.plextouch.app

import kotlinx.coroutines.CancellationException
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.PlexAccountGateway
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.data.PlexHome
import org.johnfegan.plextouch.data.PlexHomeGateway
import org.johnfegan.plextouch.data.PlexHomeUser
import org.johnfegan.plextouch.data.PlexHttpException
import org.johnfegan.plextouch.data.PlexServer
import org.johnfegan.plextouch.data.SessionStore
import org.johnfegan.plextouch.data.firstReachableConnection
import org.johnfegan.plextouch.data.sameAddress
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.checkText
import org.johnfegan.plextouch.ui.uiText
import org.johnfegan.plextouch.ui.userText

/**
 * A switch that Plex has accepted and whose server route answered, not yet saved. It holds the switched user's own token
 * (the only token Plex returned), so it never prints it.
 */
class PreparedSwitch internal constructor(val user: PlexHomeUser, internal val accountToken: String, val connection: PlexConnection) {
    override fun toString(): String = "PreparedSwitch(${user.uuid})"
}

sealed interface SwitchResult {
    class Ready(val switch: PreparedSwitch) : SwitchResult
    /** Nothing was saved: the previous user, server and token are still the saved sign-in. */
    data class Failed(val error: UiText, val wrongPin: Boolean = false) : SwitchResult
}

/**
 * Plex Home switching through Plex's supported flow (ticket 136). [prepare] does all the network work (switch, server
 * discovery as the new user, the route probe) and writes nothing; [commit] then saves the user's token and route together
 * through [SessionStore.switchAccount], which quarantines the previous user's queued timeline events. The caller stops
 * playback and cancels its jobs between the two, so nothing of the old user runs on into the new scope.
 */
class SwitchHomeUser(
    private val home: PlexHomeGateway,
    private val account: PlexAccountGateway,
    private val gateways: PlexGateways,
    private val store: SessionStore,
) {
    suspend fun home(): PlexHome {
        val token = store.accountToken()
        checkText(token != null) { uiText(R.string.home_needs_plex_sign_in) }
        return home.home(token)
    }

    /** `pin` is passed once to Plex for a protected user and not kept; a non-protected user never sends one. */
    suspend fun prepare(user: PlexHomeUser, pin: String?): SwitchResult = try {
        val accountToken = store.accountToken() ?: return SwitchResult.Failed(uiText(R.string.home_needs_plex_sign_in))
        val current = store.connection() ?: return SwitchResult.Failed(uiText(R.string.home_needs_server))
        val code = pin?.trim()?.takeIf { user.protected }
        if (user.protected && (code == null || !PIN.matches(code))) return SwitchResult.Failed(uiText(R.string.home_pin_invalid), wrongPin = true)
        val userToken = try {
            home.switchUser(accountToken, user.uuid, code)
        } catch (refused: PlexHttpException) {
            if (user.protected && refused.status in WRONG_PIN) return SwitchResult.Failed(uiText(R.string.home_pin_wrong), wrongPin = true)
            throw refused
        }
        val server = matchingServer(account.servers(userToken), current)
            ?: return SwitchResult.Failed(uiText(R.string.home_switch_no_server, user.title))
        val (connection, _) = firstReachableConnection(server.connections) { gateways.probeLibraries(it) }
        SwitchResult.Ready(PreparedSwitch(user, userToken, connection))
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: Exception) {
        SwitchResult.Failed(uiText(R.string.home_switch_failed, error.userText(uiText(R.string.home_switch_failed_fallback))))
    }

    /** Saves the prepared switch; the returned session carries the new account scope and the (scoped) history. */
    fun commit(prepared: PreparedSwitch): ConnectedSession {
        store.switchAccount(prepared.accountToken, prepared.connection.serverUrl, prepared.connection.token)
        val saved = store.connection()
        checkText(saved != null) { uiText(R.string.connection_not_saved) }
        return ConnectedSession(saved, store.progressScope(saved), store.history())
    }

    companion object {
        /** Plex Home PINs are four digits. */
        private val PIN = Regex("[0-9]{4}")
        /** How plex.tv refuses a protected user's PIN (assumed; see the ticket's API notes). */
        private val WRONG_PIN = setOf(401, 403, 422)

        /**
         * The new user's copy of the server in use: one advertising the saved address, else the only server they can see.
         * Several unrelated servers mean the choice is the person's, so the switch stops rather than guess.
         */
        fun matchingServer(servers: List<PlexServer>, current: PlexConnection): PlexServer? =
            servers.firstOrNull { server -> server.connections.any { sameAddress(it.serverUrl, current.serverUrl) } }
                ?: servers.singleOrNull()
    }
}
