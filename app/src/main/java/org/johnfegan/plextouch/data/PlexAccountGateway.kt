package org.johnfegan.plextouch.data

/** plex.tv account calls: device-link sign-in and server discovery. [PlexAuthClient] is the real one. */
interface PlexAccountGateway {
    suspend fun createPin(): PendingPlexPin
    suspend fun pollPin(pin: PendingPlexPin): String?
    suspend fun servers(accountToken: String): List<PlexServer>
    fun linkUrl(): String
}
