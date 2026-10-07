package org.johnfegan.plextouch.sonos

import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexTrack

/** Speaker discovery and hand-off as the orchestration uses them; [SonosClient] is the network implementation. */
interface SpeakerGateway {
    suspend fun discover(directoryToken: String = "", preferDirectory: Boolean = false): SpeakerDiscovery
    suspend fun connect(address: String): SonosSpeaker
    suspend fun play(speaker: SonosSpeaker, track: PlexTrack, connection: PlexConnection)
}
