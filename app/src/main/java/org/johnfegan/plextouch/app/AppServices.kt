package org.johnfegan.plextouch.app

import android.app.Application
import android.net.wifi.WifiManager
import org.johnfegan.plextouch.data.DownloadsGateway
import org.johnfegan.plextouch.data.OfflineDownloadManager
import org.johnfegan.plextouch.data.PlexAccountGateway
import org.johnfegan.plextouch.data.PlexAuthClient
import org.johnfegan.plextouch.data.PlexCache
import org.johnfegan.plextouch.data.PlexClientGateways
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.data.PlexStore
import org.johnfegan.plextouch.data.MemoryReadingListStore
import org.johnfegan.plextouch.data.ReadingListStore
import org.johnfegan.plextouch.data.SessionStore
import org.johnfegan.plextouch.player.PlaybackController
import org.johnfegan.plextouch.player.PlaybackPosition
import org.johnfegan.plextouch.player.PlaybackState
import org.johnfegan.plextouch.player.PlexPlayer
import org.johnfegan.plextouch.sonos.SonosClient
import org.johnfegan.plextouch.sonos.SpeakerGateway
import java.io.File
import org.johnfegan.plextouch.data.IdentityProbe
import org.johnfegan.plextouch.data.PlexHomeGateway
import org.johnfegan.plextouch.data.RouteProbe

/** Everything the ViewModel reaches the outside world through. The app passes the Android implementations; tests pass fakes. */
class AppServices(
    val store: SessionStore,
    val gateways: PlexGateways,
    val downloads: DownloadsGateway,
    val account: PlexAccountGateway,
    val speakers: SpeakerGateway,
    /** Builds the player with the ViewModel's state and position callbacks. */
    val player: (changed: (PlaybackState) -> Unit, moved: (PlaybackPosition) -> Unit) -> PlaybackController,
    /** Plex Home listing and switching (ticket 136). */
    val home: PlexHomeGateway,
    /** Connection diagnostics' per-route test (ticket 136). */
    val routeProbe: RouteProbe,
    /** Ticket 131: the local reading lists; [PlexStore] keeps them, other stores fall back to memory. */
    val readingList: ReadingListStore = store as? ReadingListStore ?: MemoryReadingListStore(),
) {
    companion object {
        fun android(application: Application): AppServices {
            val store = PlexStore(application)
            val plexTv = PlexAuthClient(store.clientIdentifier())
            return AppServices(
                store = store,
                gateways = PlexClientGateways(PlexCache(File(application.cacheDir, PlexCache.DIRECTORY)), store::clientIdentifier),
                downloads = OfflineDownloadManager(application),
                account = plexTv,
                speakers = SonosClient(application.getSystemService(WifiManager::class.java)),
                player = { changed, moved -> PlexPlayer(application, changed, moved) },
                home = plexTv,
                routeProbe = IdentityProbe(),
            )
        }
    }
}
