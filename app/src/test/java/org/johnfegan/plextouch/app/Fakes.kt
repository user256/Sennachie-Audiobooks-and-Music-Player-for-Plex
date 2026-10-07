package org.johnfegan.plextouch.app

import org.johnfegan.plextouch.data.AccountSwitchWriter
import org.johnfegan.plextouch.data.BookSpeedStore
import org.johnfegan.plextouch.data.TimelineStateStore
import org.johnfegan.plextouch.data.CachedAlbumList
import org.johnfegan.plextouch.data.CachedTrackList
import org.johnfegan.plextouch.data.DownloadAlbum
import org.johnfegan.plextouch.data.DownloadFile
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.DownloadTrack
import org.johnfegan.plextouch.data.DownloadsGateway
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningDay
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.clearListeningLog
import org.johnfegan.plextouch.data.clearListeningProgress
import org.johnfegan.plextouch.data.PendingPlexPin
import org.johnfegan.plextouch.data.PhoneCheckpoint
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexChapterProgress
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateway
import org.johnfegan.plextouch.data.PlexGateways
import org.johnfegan.plextouch.data.PlexPlaylist
import org.johnfegan.plextouch.data.PlexSection
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.data.ProgressSyncConflict
import org.johnfegan.plextouch.data.SessionStore
import org.johnfegan.plextouch.data.AudioEffectsSettings
import org.johnfegan.plextouch.data.progressScope
import org.johnfegan.plextouch.player.PlaybackController
import org.johnfegan.plextouch.sonos.SonosSpeaker

/** Hand-written fakes: each records what the orchestration asked of it and answers from plain fields. */
internal val connection = PlexConnection("https://plex.lan:32400", "server-token")
internal val section = PlexSection("1", "Audiobooks")

internal fun album(id: String, title: String = "Album $id") = PlexAlbum(id, title, "Artist", 2020, 2)
internal fun track(id: String, local: Boolean = false, durationMs: Long = 60_000) =
    PlexTrack(id, "Track $id", "Artist", "Album", durationMs, "/library/parts/$id/file.mp3", "mp3", localUri = if (local) "file:///offline/$id.mp3" else null)

internal fun progress(album: PlexAlbum, trackIndex: Int, positionMs: Long, finished: Boolean = false, trackId: String? = null, scope: String? = null, mode: LibraryMode = LibraryMode.AUDIOBOOK) =
    ListeningProgress(album, mode, connection.serverUrl, trackIndex, positionMs, positionMs, 120_000, 1_000, finished = finished, trackId = trackId, accountScope = scope)

internal fun download(album: PlexAlbum, tracks: List<PlexTrack>, ready: Boolean = true) = DownloadStatus(
    DownloadAlbum(connection.serverUrl, "1", LibraryMode.AUDIOBOOK, album, tracks.map { DownloadTrack(it, DownloadFile("x/${it.id}")) }),
    tracks, null, if (ready) tracks.size else 0, 0, 0, 0, 0,
)

internal open class FakeGateway : PlexGateway {
    var sections: List<PlexSection> = listOf(section)
    var cachedSections: List<PlexSection>? = null
    var albumList: suspend (String) -> List<PlexAlbum> = { emptyList() }
    var cachedAlbums: (String) -> CachedAlbumList? = { null }
    var trackList: (String) -> List<PlexTrack> = { emptyList() }
    var cachedTracks: (String) -> CachedTrackList? = { null }
    var rate: (String, String, Boolean) -> Unit = { _, _, _ -> }
    var progress: suspend (String) -> List<PlexChapterProgress> = { emptyList() }
    val sent = mutableListOf<PhoneCheckpoint>()
    var playlistList: List<PlexPlaylist> = emptyList()
    val calls = mutableListOf<String>()

    override suspend fun libraries() = sections.also { calls += "libraries" }
    override suspend fun cachedLibraries() = cachedSections.also { calls += "cachedLibraries" }
    override suspend fun albums(libraryId: String) = albumList(libraryId).also { calls += "albums" }
    override suspend fun cachedAlbumList(libraryId: String) = cachedAlbums(libraryId).also { calls += "cachedAlbumList" }
    /** Ids passed to [albumTracks], in order: each one is a Plex request in the real client. */
    val trackRequests = mutableListOf<String>()
    override suspend fun albumTracks(albumId: String): List<PlexTrack> { calls += "albumTracks"; trackRequests += albumId; return trackList(albumId) }
    override suspend fun cachedTrackList(albumId: String) = cachedTracks(albumId).also { calls += "cachedTrackList" }
    override suspend fun setAlbumFavourite(libraryId: String, albumId: String, favourite: Boolean) { calls += "rate:$albumId:$favourite"; rate(libraryId, albumId, favourite) }
    override suspend fun editAlbumMetadata(libraryId: String, albumId: String, title: String, artist: String, year: Int?) = unsupported()
    override suspend fun audiobookProgress(albumId: String) = progress(albumId).also { calls += "progress" }
    override suspend fun sendAudiobookCheckpoint(checkpoint: PhoneCheckpoint) { sent += checkpoint }
    override suspend fun playlists() = playlistList.also { calls += "playlists" }
    override suspend fun cachedPlaylists(): List<PlexPlaylist>? = null
    override suspend fun createPlaylist(title: String, tracks: List<PlexTrack>) = unsupported()
    override suspend fun addToPlaylist(id: String, tracks: List<PlexTrack>) = unsupported()
    override suspend fun renamePlaylist(id: String, title: String) = unsupported()
    override suspend fun deletePlaylist(id: String) = unsupported()
    override suspend fun removePlaylistItem(id: String, itemId: String) = unsupported()
    override suspend fun movePlaylistItem(id: String, itemId: String, after: String?) = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("not exercised by this test")
}

internal class FakeGateways(val gateway: FakeGateway = FakeGateway()) : PlexGateways {
    var probe: suspend (PlexConnection) -> List<PlexSection> = { gateway.libraries() }
    val opened = mutableListOf<Pair<PlexConnection, Boolean>>()
    override fun open(connection: PlexConnection, force: Boolean): PlexGateway = gateway.also { opened += connection to force }
    override suspend fun probeLibraries(connection: PlexConnection) = probe(connection)
}

internal class FakePlayer : PlaybackController {
    data class Play(val album: PlexAlbum, val tracks: List<PlexTrack>, val mode: LibraryMode, val scope: String, val index: Int, val positionMs: Long, val infinite: Boolean, val shuffled: Boolean, val speed: Float)
    val plays = mutableListOf<Play>()
    val commands = mutableListOf<String>()
    var queued: List<PlexTrack> = emptyList()

    override fun play(album: PlexAlbum, tracks: List<PlexTrack>, connection: PlexConnection, mode: LibraryMode, accountScope: String, index: Int, positionMs: Long, infinite: Boolean, shuffled: Boolean, speed: Float) {
        plays += Play(album, tracks, mode, accountScope, index, positionMs, infinite, shuffled, speed)
        queued = tracks
    }
    override fun toggle() { commands += "toggle" }
    override fun pause() { commands += "pause" }
    override fun stop() { commands += "stop"; queued = emptyList() }
    override fun next() { commands += "next" }
    override fun previous() { commands += "previous" }
    override fun seek(positionMs: Long) { commands += "seek:$positionMs" }
    override fun skip(deltaMs: Long) { commands += "skip:$deltaMs" }
    override fun speed(value: Float) { commands += "speed:$value" }
    override fun repeat() { commands += "repeat" }
    override fun shuffle() { commands += "shuffle" }
    override fun chapter(index: Int, positionMs: Long) { commands += "chapter:$index:$positionMs" }
    override fun queue() = queued
    override fun sleep(minutes: Int) { commands += "sleep:$minutes" }
    override fun release() { commands += "release" }
    override fun effects(settings: AudioEffectsSettings) { commands += "effects:${settings.enabled}" }
}

internal class FakeStore : SessionStore {
    var saved: PlexConnection? = null
    var historyList: List<ListeningProgress> = emptyList()
    var offline = false
    var account: String? = null
    var pin: PendingPlexPin? = null
    val speeds = mutableMapOf<LibraryMode, Float>()
    val migrated = mutableListOf<PlexConnection>()
    val resolved = mutableListOf<PlexChapterProgress>()
    val removed = mutableListOf<String>()
    private val libraries = mutableMapOf<LibraryMode, String>()

    override fun pendingPin() = pin
    override fun savePendingPin(pin: PendingPlexPin) { this.pin = pin }
    override fun clearPendingPin() { pin = null }
    override fun accountToken() = account
    override fun saveAccountToken(token: String) { account = token; pin = null }
    override fun history() = historyList
    override fun progressScope(connection: PlexConnection) = connection.progressScope(account ?: connection.token)
    override fun migrateSavedProgressScope(connection: PlexConnection) { migrated += connection }
    override fun removeProgress(server: String, albumId: String) { removed += albumId }
    override fun resolveTimeline(scope: String, server: String, albumId: String, remote: PlexChapterProgress) { resolved += remote }
    override fun progressConflicts(scope: String, server: String): List<ProgressSyncConflict> = emptyList()
    override fun playbackSpeed(mode: LibraryMode) = speeds[mode] ?: 1f
    override fun savePlaybackSpeed(mode: LibraryMode, value: Float) { speeds[mode] = value }
    /** The real override rules over a plain map standing in for the preference file. */
    val prefs = mutableMapOf<String, String>()
    private val bookSpeeds = BookSpeedStore({ prefs["book_speeds"] }, { prefs["book_speeds"] = it })
    override fun bookSpeed(scope: String, server: String, albumId: String) = bookSpeeds.speed(scope, server, albumId)
    override fun saveBookSpeed(scope: String, server: String, albumId: String, speed: Float?) = bookSpeeds.save(scope, server, albumId, speed)
    override fun consumeRecoveryNotice() = false
    override fun connection() = saved
    override fun saveConnection(url: String, token: String) { saved = PlexConnection(url.trim().trimEnd('/'), token.trim()) }
    override fun signOut() { saved = null; account = null; pin = null }
    /** The timeline outbox over [prefs], so a switch runs the real quarantine through [AccountSwitchWriter]. */
    val timeline = TimelineStateStore({ prefs["timeline"] }, { prefs["timeline"] = it })
    var failSwitchWrite = false
    private val switcher = AccountSwitchWriter(Any(), timeline, { token, url, server ->
        if (!failSwitchWrite) { account = token; saved = PlexConnection(url, server) }
        !failSwitchWrite
    }, now = { 50 })
    override fun switchAccount(accountToken: String, serverUrl: String, serverToken: String) { switcher.switch(accountToken, serverUrl, serverToken) }
    override fun library(mode: LibraryMode) = libraries[mode]
    override fun saveLibrary(mode: LibraryMode, id: String) { libraries[mode] = id }
    override fun mode() = LibraryMode.AUDIOBOOK
    override fun saveMode(mode: LibraryMode) {}
    override fun offlineOnly() = offline
    var rewindSeconds = 0
    override fun smartRewindSeconds() = rewindSeconds
    override fun saveSmartRewindSeconds(seconds: Int) { rewindSeconds = seconds }
    override fun saveOfflineOnly(value: Boolean) { offline = value }
    override fun speakers(): List<SonosSpeaker> = emptyList()
    override fun saveSpeakers(speakers: List<SonosSpeaker>) {}
    override fun householdDirectoryToken() = ""
    override fun saveHouseholdDirectoryToken(token: String) {}
    override fun clientIdentifier() = "test-device"
    var effects = AudioEffectsSettings()
    override fun audioEffects() = effects
    override fun saveAudioEffects(settings: AudioEffectsSettings) { effects = settings }
    var log: List<ListeningDay> = emptyList()
    override fun listeningLog() = log
    override fun resetBookProgress(server: String, scope: String?, albumId: String, at: Long) {
        historyList = org.johnfegan.plextouch.data.resetBookProgress(historyList, server, scope, albumId, at)
    }
    override fun clearListeningHistory(server: String, scope: String?) {
        historyList = clearListeningProgress(historyList, server, scope)
        log = clearListeningLog(log, server, scope)
    }
}

internal class FakeDownloads : DownloadsGateway {
    var snapshotList: List<DownloadStatus> = emptyList()
    val metadata = mutableListOf<List<PlexAlbum>>()
    val downloaded = mutableListOf<String>()
    val removed = mutableListOf<String>()
    var failure: Exception? = null

    override suspend fun snapshots() = snapshotList
    override suspend fun updateAlbumMetadata(server: String, libraryId: String, albums: List<PlexAlbum>) { metadata += albums }
    override suspend fun download(connection: PlexConnection, libraryId: String, mode: LibraryMode, album: PlexAlbum, tracks: List<PlexTrack>, accountScope: String?) { failure?.let { throw it }; downloaded += album.id }
    override suspend fun remove(server: String, albumId: String, accountScope: String?) { failure?.let { throw it }; removed += albumId }
    override suspend fun adoptLegacy(server: String, accountScope: String) {}
}
