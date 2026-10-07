package org.johnfegan.plextouch.app

import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.data.PlexSection
import org.johnfegan.plextouch.data.PlexServer
import org.johnfegan.plextouch.data.SessionStore
import org.johnfegan.plextouch.data.firstReachableConnection
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.InvalidStateException
import org.johnfegan.plextouch.ui.uiText

/** What a saved connection leaves for the screens: the trimmed connection, its account scope, history and (when probed) libraries. */
data class ConnectedSession(
    val connection: PlexConnection,
    val accountScope: String,
    val history: List<ListeningProgress>,
    val sections: List<PlexSection> = emptyList(),
)

/** Chooses a server's first reachable address, saves it, upgrades saved progress to the account scope and hands back its libraries. */
class ConnectToServer(private val gateways: PlexGateways, private val store: SessionStore) {
    suspend fun connect(server: PlexServer): ConnectedSession {
        val (connection, sections) = firstReachableConnection(server.connections) { gateways.probeLibraries(it) }
        return save(connection.serverUrl, connection.token, sections)
    }

    /** A manually entered address and token; the caller loads the libraries afterwards. */
    fun saveManual(serverUrl: String, token: String): ConnectedSession = save(serverUrl, token, emptyList())

    /** The session restored at launch from a saved connection, if there is one. */
    fun restore(): ConnectedSession? = store.connection()?.let(::session)

    private fun save(url: String, token: String, sections: List<PlexSection>): ConnectedSession {
        store.saveConnection(url, token)
        return session(store.connection() ?: throw InvalidStateException(uiText(R.string.connection_not_saved)), sections)
    }

    private fun session(saved: PlexConnection, sections: List<PlexSection> = emptyList()): ConnectedSession {
        store.migrateSavedProgressScope(saved)
        return ConnectedSession(saved, store.progressScope(saved), store.history(), sections)
    }
}
