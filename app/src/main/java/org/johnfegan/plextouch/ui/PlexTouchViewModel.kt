package org.johnfegan.plextouch.ui

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.johnfegan.plextouch.app.AlbumDownloads
import org.johnfegan.plextouch.app.AlbumPlayback
import org.johnfegan.plextouch.app.AppServices
import org.johnfegan.plextouch.app.PlaybackSpeeds
import org.johnfegan.plextouch.app.SpeedProfile
import org.johnfegan.plextouch.app.CompareResult
import org.johnfegan.plextouch.app.ConnectToServer
import org.johnfegan.plextouch.app.ConnectedSession
import org.johnfegan.plextouch.app.EditAlbumMetadata
import org.johnfegan.plextouch.app.ExchangeResult
import org.johnfegan.plextouch.app.FavouriteAlbum
import org.johnfegan.plextouch.app.FavouriteResult
import org.johnfegan.plextouch.app.HistoryChange
import org.johnfegan.plextouch.app.ListeningHistory
import org.johnfegan.plextouch.app.ListeningHistoryView
import org.johnfegan.plextouch.app.LoadLibrary
import org.johnfegan.plextouch.app.MutationResult
import org.johnfegan.plextouch.app.PlayOutcome
import org.johnfegan.plextouch.app.PlaylistMutations
import org.johnfegan.plextouch.app.PrewarmTracks
import org.johnfegan.plextouch.app.prewarmCandidates
import org.johnfegan.plextouch.app.ProgressComparison
import org.johnfegan.plextouch.app.ProgressContext
import org.johnfegan.plextouch.app.ProgressExchange
import org.johnfegan.plextouch.app.ToggleOffline
import org.johnfegan.plextouch.app.ConnectionDiagnostics
import org.johnfegan.plextouch.app.DiagnoseConnections
import org.johnfegan.plextouch.app.RouteResult
import org.johnfegan.plextouch.app.SwitchHomeUser
import org.johnfegan.plextouch.app.SwitchResult
import org.johnfegan.plextouch.data.PlexHomeUser
import org.johnfegan.plextouch.app.TracksLoaded
import org.johnfegan.plextouch.app.LoadCollections
import org.johnfegan.plextouch.app.RunStep
import org.johnfegan.plextouch.app.ShelfLoad
import org.johnfegan.plextouch.app.collectionRunStep
import org.johnfegan.plextouch.app.startCollectionRun
import org.johnfegan.plextouch.app.unfinishedBooks
import org.johnfegan.plextouch.data.ReadingListAdd
import org.johnfegan.plextouch.data.ReadingListEntry
import org.johnfegan.plextouch.data.ReadingLists
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.ListeningProgress
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexChapterProgress
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexGateway
import org.johnfegan.plextouch.data.PlexPlaylist
import org.johnfegan.plextouch.data.PlexSection
import org.johnfegan.plextouch.data.SetupPlan
import org.johnfegan.plextouch.data.SetupStep
import org.johnfegan.plextouch.data.AudioEffectsSettings
import org.johnfegan.plextouch.data.PlexServer
import org.johnfegan.plextouch.data.PlexSignIn
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.data.ProgressSyncConflict
import org.johnfegan.plextouch.data.TimelineSyncScheduler
import org.johnfegan.plextouch.data.belongsToScope
import org.johnfegan.plextouch.data.listenAgain
import org.johnfegan.plextouch.data.shelfAlbums
import org.johnfegan.plextouch.data.setupStep
import org.johnfegan.plextouch.player.PlaybackController
import org.johnfegan.plextouch.player.PlaybackPosition
import org.johnfegan.plextouch.player.PlaybackState
import org.johnfegan.plextouch.player.RewindPolicy
import android.os.SystemClock
import org.johnfegan.plextouch.player.progressSaved
import org.johnfegan.plextouch.sonos.SonosSpeaker
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.widget.AppShortcut
import org.johnfegan.plextouch.widget.WidgetUpdates
import org.johnfegan.plextouch.widget.shortcutPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class PersonalShelf { NONE, FAVOURITES, LISTEN_AGAIN, /* ticket 131 */ COLLECTIONS, SERIES, READING_LIST }
@Immutable
data class MetadataEditor(val album: PlexAlbum)

/** Everything the screens draw except the playback position, which ticks twice a second and has its own flow. */
@Immutable
data class PlexTouchUiState(
    val connection: PlexConnection? = null,
    val accountScope: String? = null,
    val serverUrl: String = "",
    val token: String = "",
    val plexAuthUrl: String? = null,
    val plexLinkCode: String? = null,
    val signingIn: Boolean = false,
    val discoveredServers: List<PlexServer> = emptyList(),
    /** Present only while a first-run wizard still needs a server or library choice. */
    val setupPlan: SetupPlan? = null,
    val musicLibraryId: String? = null,
    val audiobookLibraryId: String? = null,
    val showSettings: Boolean = false,
    val sections: List<PlexSection> = emptyList(),
    val mode: LibraryMode = LibraryMode.AUDIOBOOK,
    val selectedLibraryId: String? = null,
    val albums: List<PlexAlbum> = emptyList(),
    val selectedAlbum: PlexAlbum? = null,
    val tracks: List<PlexTrack> = emptyList(),
    val speakers: List<SonosSpeaker> = emptyList(),
    val selectedSpeaker: SonosSpeaker? = null,
    val loading: Boolean = false,
    /** One persistent message per feature (see [Feature]); transient successes travel on [PlexTouchViewModel.notices] instead. */
    val messages: Map<Feature, UiText> = emptyMap(),
    val tab: LibraryTab = LibraryTab.HOME,
    val query: String = "",
    val sort: AlbumSort = AlbumSort.RECENT,
    val history: List<ListeningProgress> = emptyList(),
    val playback: PlaybackState = PlaybackState(),
    val showPlayer: Boolean = false,
    val showDevices: Boolean = false,
    val queue: List<PlexTrack> = emptyList(),
    val discovering: Boolean = false,
    val downloads: List<DownloadStatus> = emptyList(),
    val offlineOnly: Boolean = false,
    val downloadBusy: Boolean = false,
    val speakerAddress: String = "",
    val connectingSpeaker: Boolean = false,
    val playlists: List<PlexPlaylist> = emptyList(),
    val musicCollection: MusicCollectionTab = MusicCollectionTab.ALBUMS,
    val selectedArtist: String? = null,
    val selectedPlaylist: PlexPlaylist? = null,
    val playlistAddTracks: List<PlexTrack> = emptyList(),
    val playlistBusy: Boolean = false,
    val ratingBusy: Boolean = false,
    val personalShelf: PersonalShelf = PersonalShelf.NONE,
    val householdDirectoryConfigured: Boolean = false,
    val progressComparison: ProgressComparison? = null,
    val progressBusy: Boolean = false,
    val progressNotice: UiText? = null,
    val progressSyncConflict: ProgressSyncConflict? = null,
    val metadataEditor: MetadataEditor? = null,
    val metadataBusy: Boolean = false,
    /** Music's grouped artists and A–Z positions, prepared off the main thread whenever the catalogue changes. */
    val artistIndex: ArtistIndex? = null,
    /** The default audiobook speed and the loaded book's own speed, if it has one (ticket 133). */
    val speedProfile: SpeedProfile = SpeedProfile(),
    /** Smart rewind after an audiobook pause, in seconds; 0 is Off (ticket 134). */
    val smartRewindSeconds: Int = 0,
    /** Phone sound processing (ticket 135); the service owns the effects, this is the saved choice. */
    val audioEffects: AudioEffectsSettings = AudioEffectsSettings(),
    /** Plex Home users and an in-progress switch (ticket 136). */
    val home: PlexHomeState = PlexHomeState(),
    /** The saved server's advertised routes and their last test results, in memory only (ticket 136). */
    val diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
    /** Ticket 138: the history screen's data, loaded when it opens; null while loading or closed. */
    val listeningHistory: ListeningHistoryView? = null,
    val historyNotice: UiText? = null,
    /** Audiobook collections, series and the reading list (ticket 131); read it through [currentShelves]. */
    val shelves: BookShelvesState = BookShelvesState(),
) {
    val showPlaylists: Boolean get() = musicCollection == MusicCollectionTab.PLAYLISTS
    val artworkConnection: PlexConnection? get() = connection.takeUnless { offlineOnly }
    val favouriteShelf: List<PlexAlbum> get() = favouriteAlbums(albums)
    val listenAgainShelf: List<PlexAlbum> get() = shelfAlbums(listenAgain(history, connection?.serverUrl, accountScope).map { it.album }, albums, offlineOnly)
    fun isFavourite(album: PlexAlbum): Boolean = (albums.firstOrNull { it.id == album.id } ?: album).isFavourite
    /** Sign-in and connection status; it stays on the setup screen until the next step replaces it. */
    val setupMessage: UiText? get() = messages[Feature.SETUP]
    val setupStep: SetupStep? get() = setupStep(setupPlan, connection != null, discoveredServers.isNotEmpty(), musicLibraryId, audiobookLibraryId)
    val libraryError: UiText? get() = messages[Feature.LIBRARY]
    val albumError: UiText? get() = messages[Feature.ALBUM]
    val playlistError: UiText? get() = messages[Feature.PLAYLIST]
    val metadataError: UiText? get() = messages[Feature.METADATA]
    val downloadError: UiText? get() = messages[Feature.DOWNLOAD]
    val speakerError: UiText? get() = messages[Feature.SPEAKER]
}

/**
 * One instance per activity. Screens read it only through [uiState], [position] and [notices] (snapshot state via
 * `collectAsStateWithLifecycle`) and call its methods, so Compose can treat the reference as stable and skip composables
 * whose other inputs are unchanged. The orchestration lives in `org.johnfegan.plextouch.app`; this class holds the state,
 * wires the use cases to the Android services in [AppServices] and maps their results into it.
 */
@Stable
class PlexTouchViewModel @JvmOverloads constructor(
    application: Application,
    services: AppServices = AppServices.android(application),
) : AndroidViewModel(application) {
    private val store = services.store
    private val gateways = services.gateways
    private val offline = services.downloads
    private val account = services.account
    private val sonos = services.speakers
    private val signIn = PlexSignIn(store, account::createPin, account::pollPin)
    private var authJob: Job? = null
    private var libraryJob: Job? = null
    private var albumJob: Job? = null
    private var playlistJob: Job? = null
    private var catalogueRefresh: Job? = null
    private var prewarmJob: Job? = null
    private val warmAlbums = mutableMapOf<String, List<PlexAlbum>>()
    private val artistIndexes = ArtistIndexCache()
    private val uiFlow = MutableStateFlow(PlexTouchUiState(mode = store.mode(), history = store.history(), offlineOnly = store.offlineOnly(), speakers = store.speakers(), setupPlan = store.setupPlan(), musicLibraryId = store.library(LibraryMode.MUSIC), audiobookLibraryId = store.library(LibraryMode.AUDIOBOOK), householdDirectoryConfigured = store.householdDirectoryToken().isNotBlank(), speedProfile = SpeedProfile(store.playbackSpeed(LibraryMode.AUDIOBOOK)), smartRewindSeconds = store.smartRewindSeconds(), audioEffects = store.audioEffects()))
    val uiState: StateFlow<PlexTouchUiState> = uiFlow.asStateFlow()
    var state: PlexTouchUiState
        get() = uiFlow.value
        private set(value) { uiFlow.value = value }
    private inline fun update(transform: (PlexTouchUiState) -> PlexTouchUiState) = uiFlow.update(transform)
    private val jobs = FeatureJobs(viewModelScope, uiFlow)
    private val noticeChannel = Channel<UiText>(Channel.BUFFERED)
    /**
     * Transient successes ("Saved to <playlist>"), each shown once as a snackbar by `LibraryShell`. A channel rather than a
     * replay-less shared flow, so a notice sent before the shell is composed (such as "Connected to …") is still delivered.
     */
    val notices: Flow<UiText> = noticeChannel.receiveAsFlow()
    private fun notify(message: UiText) { noticeChannel.trySend(message) }
    private fun show(feature: Feature, message: UiText?) = update { it.withMessage(feature, message) }
    private val playbackPosition = MutableStateFlow(PlaybackPosition())
    /** The 500 ms player tick. Only the player and mini-player collect it, so the rest of the app never recomposes for it. */
    val position: StateFlow<PlaybackPosition> = playbackPosition.asStateFlow()
    private val player: PlaybackController = services.player(::playbackChanged) { playbackPosition.value = it }
    private val connect = ConnectToServer(gateways, store)
    private val library = LoadLibrary(gateways, offline)
    private val albumPlayback = AlbumPlayback(gateways, player, store)
    private val offlineMode = ToggleOffline(store, player)
    private val speeds = PlaybackSpeeds(store, player)
    private val favourite = FavouriteAlbum(gateways, offline)
    private val metadata = EditAlbumMetadata(gateways, offline)
    private val albumDownloads = AlbumDownloads(offline)
    private val playlistMutations = PlaylistMutations(gateways)
    private val prewarm = PrewarmTracks(gateways)
    private val homeUsers = SwitchHomeUser(services.home, account, gateways, store)
    private val diagnose = DiagnoseConnections(account, services.routeProbe)
    private var homeJob: Job? = null
    private var diagnosticsJob: Job? = null
    private var sectionsJob: Job? = null
    // Ticket 131: collections, series and the reading list.
    private val loadCollections = LoadCollections(gateways)
    private val readingLists = services.readingList
    private var shelfJob: Job? = null
    private var runJob: Job? = null
    private val progress = ProgressExchange(gateways, store, player) {
        ProgressContext(state.connection, state.mode, state.selectedAlbum?.id, state.accountScope, state.offlineOnly, state.playback.playing, state.playback.buffering, state.playback.ready)
    }
    private val listeningHistory = ListeningHistory(store)
    private val catalogueSearch = CatalogueSearch(viewModelScope)
    /** Search's results, filtered off the main thread from the in-memory catalogue; only the Search screen collects them. */
    val searchResults: StateFlow<SearchResults> = catalogueSearch.results
    private var historyJob: Job? = null
    private var playingTimer: Job? = null
    private var downloadPoll: Job? = null
    private val downloadFinished = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshDownloads() }
    }

    init {
        // A restored or keystore-damaged phone lands on the setup screen with the reason, not a crash loop.
        if (store.consumeRecoveryNotice()) show(Feature.SETUP, uiText(R.string.secure_storage_reset))
        connect.restore()?.let { session ->
            apply(session)
            TimelineSyncScheduler.schedule(application)
            if (!state.offlineOnly) loadSections()
        }
        state = state.copy(selectedLibraryId = store.library(state.mode))
        // Only resume a deliberately started wizard. A fresh screen must first ask what the person wants to set up.
        if (state.connection == null && state.setupPlan != null && !state.offlineOnly) {
            if (store.accountToken() != null || store.pendingPin() != null) beginPlexSignIn()
        }
        // The system sends ACTION_DOWNLOAD_COMPLETE, hence an exported receiver; byte progress is polled only while a transfer is live.
        ContextCompat.registerReceiver(application, downloadFinished, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        refreshDownloads(adopt = true)
        refreshSyncConflict()
        // Search follows the query and the catalogue while its tab is open; distinctUntilChanged keeps unrelated state quiet.
        viewModelScope.launch {
            uiFlow.filter { it.tab == LibraryTab.SEARCH }.map(::searchInput).distinctUntilChanged().collect(catalogueSearch::submit)
        }
        keepArtistIndexWarm()
        keepWidgetCurrent()
    }

    /**
     * Ticket 139: the home-screen widget follows the account, server, offline mode and finished downloads. This watches the
     * state the app already holds (no poll) and asks for one throttled refresh whenever any of them changes.
     */
    private fun keepWidgetCurrent() = viewModelScope.launch {
        uiFlow.map { WidgetInputsKey(it.connection?.serverUrl, it.accountScope, it.offlineOnly, it.downloads.filter { d -> d.ready }.map { d -> d.record.server to d.record.album.id }) }
            .distinctUntilChanged()
            .collect { WidgetUpdates.request(getApplication()) }
    }

    /**
     * A launcher shortcut (ticket 139). Signed out it only opens the app. "Resume audiobook" waits for the player to connect,
     * then resumes the last-played audiobook through the same path as the resume bar; with none, the audiobook home shows.
     */
    fun openShortcut(shortcut: AppShortcut) {
        val plan = shortcutPlan(shortcut, state.connection != null) ?: return
        plan.mode?.let(::chooseMode)
        plan.tab?.let(::chooseTab)
        if (!plan.resume) return
        viewModelScope.launch {
            withTimeoutOrNull(PLAYER_CONNECT_MS) { uiFlow.first { it.playback.ready } } ?: return@launch
            val last = lastPlayed(state.history, state.connection?.serverUrl, LibraryMode.AUDIOBOOK, state.accountScope) ?: return@launch
            if (state.playback.album?.id == last.album.id) {
                if (!state.playback.playing) player.toggle()
                state = state.copy(showPlayer = true, queue = player.queue())
            } else resumeLastPlayed(last)
        }
    }

    /**
     * Groups music catalogues into artists as they arrive (cached, then fresh, then with downloads merged), on a background
     * dispatcher, so Artists and its A–Z rail open from a ready model. The cache returns the earlier grouping for an
     * unchanged catalogue, including one shown again after a mode or library switch; a newer list cancels older work.
     */
    private fun keepArtistIndexWarm() = viewModelScope.launch {
        uiFlow.map { ArtistIndexScope.of(it) to it.albums }
            .distinctUntilChanged { old, new -> old.first == new.first && old.second === new.second }
            .collectLatest { (scope, albums) ->
                if (scope.mode != LibraryMode.MUSIC) return@collectLatest
                val index = withContext(Dispatchers.Default) {
                    artistIndexes.index(scope, albums, getApplication<Application>().getString(R.string.unknown_artist))
                }
                update { current -> if (ArtistIndexScope.of(current) == scope && current.artistIndex !== index) current.copy(artistIndex = index) else current }
            }
    }

    private fun apply(session: ConnectedSession) = update {
        it.copy(connection = session.connection, accountScope = session.accountScope, history = session.history, serverUrl = session.connection.serverUrl, token = session.connection.token)
    }

    private fun playbackChanged(playback: PlaybackState) {
        val previous = state.playback
        if (playback == previous) return
        update { it.copy(playback = playback) }
        RewindPolicy.notice(previous, playback, SystemClock.elapsedRealtime())?.let { seconds -> notify(uiPlural(R.plurals.smart_rewound, seconds)) }
        if (playback.album?.id != previous.album?.id || playback.accountScope != previous.accountScope || playback.server != previous.server || playback.speed != previous.speed) {
            val profile = speeds.profile(playback)
            if (profile != state.speedProfile) update { it.copy(speedProfile = profile) }
        }
        if (progressSaved(previous, playback)) {
            // The service persists progress on its own writer thread; give that write a moment to land before re-reading it.
            reloadHistory(HISTORY_SETTLE_MS)
            refreshSyncConflict()
        }
        if (playback.playing != previous.playing) {
            playingTimer?.cancel()
            // While playing, the service checkpoints every ~5 s and the timeline drains in the background; pick those up slowly.
            if (playback.playing) playingTimer = viewModelScope.launch {
                while (true) { delay(PLAYING_REFRESH_MS); reloadHistory(); refreshSyncConflict() }
            }
        }
    }

    /** Re-reads saved listening progress off the main thread; the playback service, not this ViewModel, writes it. */
    private fun reloadHistory(afterMs: Long = 0) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            if (afterMs > 0) delay(afterMs)
            val history = withContext(Dispatchers.IO) { store.history() }
            if (history != state.history) update { it.copy(history = history) }
            advanceCollectionRun()
        }
    }

    /** Conflicts are raised by the timeline drain (service or worker), so this follows playback changes and comparisons rather than a poll. */
    private fun refreshSyncConflict() {
        val connection = state.connection ?: run { if (state.progressSyncConflict != null) update { it.copy(progressSyncConflict = null) }; return }
        viewModelScope.launch {
            val conflict = try {
                withContext(Dispatchers.IO) { store.progressConflicts(store.progressScope(connection), connection.serverUrl).maxByOrNull { it.createdAt } }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { return@launch }
            if (state.connection == connection && conflict != state.progressSyncConflict) update { it.copy(progressSyncConflict = conflict) }
        }
    }

    fun updateUrl(value: String) { state = state.copy(serverUrl = value) }
    fun updateToken(value: String) { state = state.copy(token = value) }
    /** Starts a resumable first-run path; the plan is cleared only after every requested library is picked. */
    fun chooseSetupPlan(plan: SetupPlan) {
        store.saveSetupPlan(plan)
        // A new setup can target another server, so never silently accept an old library id as this wizard's answer.
        state = state.withMessage(Feature.SETUP, null).copy(
            setupPlan = plan, plexLinkCode = null, discoveredServers = emptyList(),
            musicLibraryId = if (plan.includesMusic) null else state.musicLibraryId,
            audiobookLibraryId = if (plan.includesAudiobooks) null else state.audiobookLibraryId,
        )
    }
    fun openSettings(show: Boolean) {
        if (!show) diagnosticsJob?.cancel()
        state = state.withMessage(Feature.LIBRARY, null).copy(showSettings = show)
    }
    fun chooseTab(tab: LibraryTab) {
        closeAlbum()
        state = state.copy(tab = tab, query = "", personalShelf = PersonalShelf.NONE, selectedArtist = null)
        if (tab == LibraryTab.SEARCH) refreshCatalogueInBackground()
    }

    /**
     * Opening Search checks the catalogue in the background: a fresh cache answers without the network, a stale one is fetched
     * while Search keeps filtering what is already in memory. Offline-only mode never asks, and a failure leaves the shown
     * catalogue alone rather than raising a library error over a search. It never cancels a load already running.
     */
    private fun refreshCatalogueInBackground() {
        if (!shouldRefreshCatalogue(state, libraryJob?.isActive == true || catalogueRefresh?.isActive == true)) return
        val connection = state.connection ?: return
        val libraryId = state.selectedLibraryId ?: return
        catalogueRefresh = viewModelScope.launch {
            try {
                library.albums(connection, libraryId) { albums ->
                    rememberLibrary(connection, libraryId, albums)
                    showLibraryIfCurrent(connection, libraryId, albums)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { }
        }
    }

    fun openShelf(shelf: PersonalShelf) {
        closeAlbum()
        state = state.copy(tab = LibraryTab.LIBRARY, personalShelf = shelf, musicCollection = MusicCollectionTab.ALBUMS, selectedArtist = null)
    }
    fun showArtists() {
        closeAlbum()
        state = state.copy(musicCollection = MusicCollectionTab.ARTISTS, personalShelf = PersonalShelf.NONE, selectedArtist = null)
    }
    fun chooseArtist(key: String?) { state = state.copy(selectedArtist = key) }

    private fun browsing(connection: PlexConnection, library: String, mode: LibraryMode? = null) =
        state.connection == connection && state.selectedLibraryId == library && (mode == null || state.mode == mode)

    fun toggleAlbumFavourite() = viewModelScope.launch {
        val album = state.selectedAlbum ?: return@launch
        val connection = state.connection ?: return@launch
        val library = state.selectedLibraryId ?: return@launch
        if (state.ratingBusy || state.offlineOnly || state.mode != LibraryMode.MUSIC || album.id.startsWith("playlist:")) return@launch
        val wanted = !state.isFavourite(album)
        state = state.withMessage(Feature.ALBUM, null).copy(ratingBusy = true)
        libraryJob?.cancel(); catalogueRefresh?.cancel()
        try {
            val result = favourite.toggle(connection, library, album, wanted) { rated ->
                if (browsing(connection, library, LibraryMode.MUSIC)) update {
                    it.copy(albums = it.albums.map { each -> if (each.id == rated.id) rated else each }, selectedAlbum = if (it.selectedAlbum?.id == rated.id) rated else it.selectedAlbum)
                }
            }
            when (result) {
                is FavouriteResult.Saved -> {
                    notify(result.notice)
                    updateDownloads()
                    if (browsing(connection, library, LibraryMode.MUSIC)) { state = state.copy(albums = result.albums); mergeDownloads() }
                }
                is FavouriteResult.Failed -> show(Feature.ALBUM, result.error)
            }
        } finally { state = state.copy(ratingBusy = false) }
    }
    fun saveHouseholdDirectoryToken(token: String) {
        store.saveHouseholdDirectoryToken(token)
        state = state.copy(householdDirectoryConfigured = token.isNotBlank())
        notify(uiText(if (token.isBlank()) R.string.household_directory_disconnected else R.string.household_directory_saved))
    }
    fun search(query: String) { state = state.copy(query = query) }
    fun sort(sort: AlbumSort) { state = state.copy(sort = sort) }
    fun dismissMessage(feature: Feature) = show(feature, null)
    fun openMetadataEditor() {
        val album = state.selectedAlbum ?: return
        if (state.offlineOnly || album.id.startsWith("playlist:")) return
        state = state.withMessage(Feature.METADATA, null).copy(metadataEditor = MetadataEditor(album))
    }
    fun closeMetadataEditor() { if (!state.metadataBusy) state = state.copy(metadataEditor = null) }
    fun saveAlbumMetadata(title: String, artist: String, year: String) = viewModelScope.launch {
        val editor = state.metadataEditor ?: return@launch
        val connection = state.connection ?: return@launch
        val library = state.selectedLibraryId ?: return@launch
        if (state.metadataBusy || state.offlineOnly) return@launch
        val parsedYear = year.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (parsedYear == null && year.isNotBlank()) { show(Feature.METADATA, uiText(R.string.metadata_year_invalid)); return@launch }
        state = state.withMessage(Feature.METADATA, null).copy(metadataBusy = true)
        try {
            val saved = metadata.save(connection, library, editor.album, title, artist, parsedYear)
            if (browsing(connection, library)) {
                state = state.copy(albums = state.albums.map { if (it.id == saved.id) saved else it }, selectedAlbum = saved, metadataEditor = null)
                mergeDownloads()
                notify(uiText(R.string.metadata_saved))
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { show(Feature.METADATA, error.userText(uiText(R.string.metadata_save_failed)))
        } finally { state = state.copy(metadataBusy = false) }
    }
    fun closeAlbum() { albumJob?.cancel(); state = state.copy(selectedAlbum = null, selectedPlaylist = null, tracks = emptyList(), loading = false) }
    fun openPlayer(show: Boolean) { state = state.copy(showPlayer = show, queue = if (show) player.queue() else state.queue) }
    fun openDevices(show: Boolean) { state = state.copy(showDevices = show) }
    fun updateSpeakerAddress(value: String) { state = state.copy(speakerAddress = value) }
    fun addSpeaker() = viewModelScope.launch {
        if (state.connectingSpeaker) return@launch
        val address = state.speakerAddress
        state = state.withMessage(Feature.SPEAKER, null).copy(connectingSpeaker = true)
        try {
            val speaker = sonos.connect(address)
            val speakers = (state.speakers + speaker).distinctBy { it.host }
            store.saveSpeakers(speakers)
            state = state.copy(speakers = speakers, selectedSpeaker = speaker, speakerAddress = "")
            notify(uiText(R.string.speaker_saved, speaker.name))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: IllegalArgumentException) { show(Feature.SPEAKER, error.userText(uiText(R.string.speaker_unreachable)))
        } catch (error: Exception) {
            android.util.Log.w("PlexSonos", "Speaker connection failed: ${error.javaClass.simpleName}")
            show(Feature.SPEAKER, uiText(if (error is javax.xml.parsers.ParserConfigurationException) R.string.speaker_description_unreadable else R.string.speaker_unreachable))
        } finally { state = state.copy(connectingSpeaker = false) }
    }
    fun togglePlayback() = player.toggle()
    fun seek(position: Long) = player.seek(position)
    fun skip(delta: Long) = player.skip(delta)
    fun previous() = player.previous()
    fun next() = player.next()
    /** The player's speed menu: a book keeps its own speed; music changes its one speed. */
    fun speed(value: Float) { val profile = speeds.choose(value, state.playback); update { it.copy(speedProfile = profile) } }
    /** The player's one-tap reset: the book follows the default audiobook speed again. */
    fun resetBookSpeed() { val profile = speeds.reset(state.playback); update { it.copy(speedProfile = profile) } }
    /** Settings: how far an audiobook rewinds after a long pause; 0 turns smart rewind off. */
    fun smartRewind(seconds: Int) {
        val value = seconds.coerceIn(0, RewindPolicy.MAX_SECONDS)
        store.saveSmartRewindSeconds(value)
        update { it.copy(smartRewindSeconds = value) }
    }
    /** Settings: the speed every audiobook without its own speed plays at. */
    fun defaultAudiobookSpeed(value: Float) { val profile = speeds.saveDefault(value, state.playback); update { it.copy(speedProfile = profile) } }
    fun toggleRepeat() = player.repeat()
    fun toggleShuffle() = player.shuffle()
    fun sleep(minutes: Int) = player.sleep(minutes)
    /** Saved first, so a service started later reads it; a running service applies it at once (off is instant). */
    fun updateAudioEffects(settings: AudioEffectsSettings) {
        state = state.copy(audioEffects = settings)
        store.saveAudioEffects(settings)
        player.effects(settings)
    }
    fun chapter(index: Int, positionMs: Long = 0) = player.chapter(index, positionMs)
    fun refresh() { if (state.showPlaylists && state.mode == LibraryMode.MUSIC) loadPlaylists(true) else if (state.sections.isEmpty()) loadSections(true) else loadAlbums(true) }

    fun comparePlexProgress() = viewModelScope.launch {
        val connection = state.connection ?: return@launch
        val album = state.selectedAlbum ?: return@launch
        if (state.progressBusy) return@launch
        state = state.withMessage(Feature.ALBUM, null).copy(progressBusy = true, progressNotice = null)
        try {
            when (val result = progress.compare(connection, album)) {
                is CompareResult.Ready -> state = state.copy(progressComparison = result.comparison)
                is CompareResult.Refused -> show(Feature.ALBUM, result.error)
                is CompareResult.Failed -> show(Feature.ALBUM, result.error)
                CompareResult.Stale -> Unit
            }
        } finally { state = state.copy(progressBusy = false); refreshSyncConflict() }
    }

    fun closeProgressComparison() {
        if (!state.progressBusy) state = state.copy(progressComparison = null, progressNotice = null)
    }

    fun sendPhoneProgress() = viewModelScope.launch {
        val comparison = state.progressComparison ?: return@launch
        val phone = comparison.phone ?: return@launch
        if (state.progressBusy) return@launch
        state = state.copy(progressBusy = true, progressNotice = null)
        try {
            when (val result = progress.send(comparison, phone)) {
                ExchangeResult.Done -> { state = state.copy(progressComparison = null); notify(uiText(R.string.progress_sent)) }
                is ExchangeResult.Failed -> state = state.copy(progressNotice = result.notice)
            }
        } finally { state = state.copy(progressBusy = false); refreshSyncConflict() }
    }

    fun resumePlexProgress(chapter: PlexChapterProgress) = viewModelScope.launch {
        val comparison = state.progressComparison ?: return@launch
        if (state.progressBusy || chapter !in comparison.chapters || !chapter.resumable) return@launch
        state = state.copy(progressBusy = true, progressNotice = null)
        try {
            when (val result = progress.resume(comparison, chapter, downloadedAlbum(comparison.album.id))) {
                ExchangeResult.Done -> state = state.withMessage(Feature.ALBUM, null).copy(progressComparison = null, showPlayer = true, queue = player.queue())
                is ExchangeResult.Failed -> state = state.copy(progressNotice = result.notice)
            }
        } finally { state = state.copy(progressBusy = false); refreshSyncConflict() }
    }

    /** Forget the Plex account and server; downloads and listening history stay on the phone, read-only until a matching sign-in. */
    fun signOut() {
        authJob?.cancel(); libraryJob?.cancel(); catalogueRefresh?.cancel(); albumJob?.cancel(); playlistJob?.cancel(); prewarmJob?.cancel()
        homeJob?.cancel(); diagnosticsJob?.cancel(); update { it.copy(home = PlexHomeState(), diagnostics = ConnectionDiagnostics()) }
        player.stop()
        store.signOut()
        TimelineSyncScheduler.cancel(getApplication())
        warmAlbums.clear()
        artistIndexes.clear()
        // No PIN or account token remains, so nothing restarts the sign-in until the user taps Link with Plex.
        state = state.copy(
            connection = null, accountScope = null, serverUrl = "", token = "",
            plexAuthUrl = null, plexLinkCode = null, signingIn = false, discoveredServers = emptyList(),
            setupPlan = null,
            sections = emptyList(), albums = emptyList(), selectedAlbum = null, tracks = emptyList(),
            playlists = emptyList(), selectedPlaylist = null, playlistAddTracks = emptyList(),
            showSettings = false, showPlayer = false, showDevices = false, queue = emptyList(),
            progressComparison = null, progressNotice = null, progressSyncConflict = null, metadataEditor = null,
            history = store.history(),
            messages = mapOf(Feature.SETUP to uiText(R.string.signed_out)),
        )
    }

    // Plex Home switching and connection diagnostics (ticket 136).

    fun loadHomeUsers() {
        if (homeJob?.isActive == true) return
        update { it.copy(home = it.home.copy(loading = true, error = null)) }
        homeJob = viewModelScope.launch {
            try {
                val home = homeUsers.home()
                update { it.copy(home = it.home.copy(home = home)) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                update { it.copy(home = it.home.copy(error = uiText(R.string.home_load_failed, error.userText(uiText(R.string.home_switch_failed_fallback))))) }
            } finally { update { it.copy(home = it.home.copy(loading = false)) } }
        }
    }

    /** A protected user opens the PIN dialog; anyone else switches straight away. */
    fun requestHomeSwitch(user: PlexHomeUser) {
        if (state.home.switchingTo != null) return
        if (user.protected) update { it.copy(home = it.home.copy(pinFor = user, error = null)) } else switchHomeUser(user, null)
    }

    fun cancelHomeSwitch() { if (state.home.switchingTo == null) update { it.copy(home = it.home.copy(pinFor = null, error = null)) } }
    fun dismissHomeError() = update { it.copy(home = it.home.copy(error = null)) }
    /** Only for plex.tv avatar requests, as a header (see `homeThumbUrl`). */
    fun homeAvatarToken(): String? = store.accountToken()

    /**
     * Asks Plex to switch (the PIN goes to Plex once and is not kept) and finds the server as the new user. Only when both
     * succeed does it stop playback, cancel every job of the old user and save the new sign-in; any failure leaves the
     * previous user, server and token saved and in use.
     */
    fun switchHomeUser(user: PlexHomeUser, pin: String?) {
        if (state.home.switchingTo != null || state.connection == null) return
        homeJob?.cancel()
        update { it.copy(home = it.home.copy(switchingTo = user.uuid, error = null)) }
        homeJob = viewModelScope.launch {
            try {
                when (val result = homeUsers.prepare(user, pin)) {
                    is SwitchResult.Failed -> update { it.copy(home = it.home.copy(error = result.error, pinFor = it.home.pinFor.takeIf { result.wrongPin })) }
                    is SwitchResult.Ready -> {
                        leaveAccount()
                        enterSwitchedAccount(homeUsers.commit(result.switch), user)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                update { it.copy(home = it.home.copy(pinFor = null, error = uiText(R.string.home_switch_failed, error.userText(uiText(R.string.home_switch_failed_fallback))))) }
            } finally { update { it.copy(home = it.home.copy(switchingTo = null)) } }
        }
    }

    /** Stops everything still working for the current user: playback, loads, prewarm, search refresh, artist index, polls. */
    private fun leaveAccount() {
        authJob?.cancel(); sectionsJob?.cancel(); libraryJob?.cancel(); catalogueRefresh?.cancel(); albumJob?.cancel(); playlistJob?.cancel()
        prewarmJob?.cancel(); downloadPoll?.cancel(); historyJob?.cancel(); playingTimer?.cancel(); diagnosticsJob?.cancel()
        shelfJob?.cancel(); runJob?.cancel()
        player.stop()
        warmAlbums.clear()
        artistIndexes.clear()
    }

    private fun enterSwitchedAccount(session: ConnectedSession, user: PlexHomeUser) {
        apply(session)
        update {
            it.copy(
                sections = emptyList(), albums = emptyList(), selectedAlbum = null, tracks = emptyList(), artistIndex = null,
                playlists = emptyList(), selectedPlaylist = null, playlistAddTracks = emptyList(), downloads = emptyList(),
                personalShelf = PersonalShelf.NONE, selectedArtist = null, query = "",
                showPlayer = false, queue = emptyList(), progressComparison = null, progressNotice = null, progressSyncConflict = null,
                metadataEditor = null, speedProfile = SpeedProfile(store.playbackSpeed(LibraryMode.AUDIOBOOK)),
                diagnostics = ConnectionDiagnostics(), shelves = BookShelvesState(), listeningHistory = null, historyNotice = null,
                home = it.home.copy(home = it.home.home?.copy(currentUuid = user.uuid), pinFor = null, error = null),
            )
        }
        TimelineSyncScheduler.schedule(getApplication())
        refreshSyncConflict()
        refreshDownloads()
        loadSections()
        notify(uiText(R.string.home_switched, user.title))
    }

    /** Tests every advertised route of the saved server in parallel. Read-only: the saved connection is never changed. */
    fun testConnections() {
        val connection = state.connection ?: return
        diagnosticsJob?.cancel()
        diagnosticsJob = viewModelScope.launch {
            update { it.copy(diagnostics = it.diagnostics.copy(running = true)) }
            try {
                val listed = diagnose.routes(connection, store.accountToken())
                if (state.connection != connection) return@launch
                update { it.copy(diagnostics = listed.copy(running = true, routes = listed.routes.map { route -> route.copy(result = RouteResult.Testing) })) }
                diagnose.test(listed.routes.map { it.route }) { route, result ->
                    if (state.connection == connection) update { it.copy(diagnostics = it.diagnostics.with(route, result)) }
                }
            } finally {
                update { current ->
                    current.copy(diagnostics = current.diagnostics.copy(running = false,
                        routes = current.diagnostics.routes.map { if (it.result == RouteResult.Testing) it.copy(result = RouteResult.Untested) else it }))
                }
            }
        }
    }

    fun cancelConnectionTests() { diagnosticsJob?.cancel() }

    fun resumeLastPlayed(progress: ListeningProgress) {
        val connection = state.connection ?: return
        if (progress.server != connection.serverUrl || progress.mode != state.mode || !progress.belongsToScope(state.accountScope)) return
        albumJob?.cancel()
        albumJob = jobs.launch(uiText(R.string.job_resuming, progress.album.title), Feature.LIBRARY) {
            when (val outcome = albumPlayback.resume(connection, progress, downloadedAlbum(progress.album.id), state.offlineOnly)) {
                PlayOutcome.Started -> state = state.copy(showPlayer = true, queue = player.queue())
                is PlayOutcome.Refused -> show(Feature.LIBRARY, outcome.message)
                PlayOutcome.Ignored -> Unit
            }
        }
    }

    fun setOfflineOnly(value: Boolean) {
        libraryJob?.cancel(); catalogueRefresh?.cancel(); albumJob?.cancel(); prewarmJob?.cancel()
        if (offlineMode.apply(value)) state = state.copy(showPlayer = false)
        state = state.withoutMessages(Feature.LIBRARY, Feature.ALBUM).copy(offlineOnly = value, selectedAlbum = null, tracks = emptyList(), albums = emptyList(), tab = LibraryTab.DOWNLOADS)
        mergeDownloads()
        if (!value) refresh()
        if (!value) TimelineSyncScheduler.schedule(getApplication())
    }

    private fun downloadedAlbum(id: String) = state.downloads.firstOrNull { it.record.server == state.connection?.serverUrl && it.record.album.id == id }

    /** `adopt` hands pre-136 unscoped downloads for this server to the account now connected (never after a Home switch). */
    private fun refreshDownloads(adopt: Boolean = false) {
        viewModelScope.launch {
            val connection = state.connection
            val scope = state.accountScope
            if (adopt && connection != null && scope != null) try { offline.adoptLegacy(connection.serverUrl, scope) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            updateDownloads()
        }
    }

    private suspend fun updateDownloads() {
        try {
            // Only this server's and this account's records are visible; other users' files stay on disk, unseen (ticket 136).
            val downloads = offline.snapshots().filter { it.record.belongsTo(state.connection?.serverUrl, state.accountScope) }
            if (downloads != state.downloads) { state = state.copy(downloads = downloads); mergeDownloads() }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { show(Feature.DOWNLOAD, uiText(R.string.downloads_read_failed)) }
        pollDownloadsWhileActive()
    }

    /** DownloadManager only broadcasts completion, so bytes-so-far are polled, and only while a transfer is queued or running. */
    private fun pollDownloadsWhileActive() {
        if (downloadPoll?.isActive == true || state.downloads.none { it.active }) return
        downloadPoll = viewModelScope.launch {
            while (state.downloads.any { it.active }) { delay(DOWNLOAD_POLL_MS); updateDownloads() }
        }
    }

    private fun mergeDownloads() {
        val local = libraryDownloads(state.downloads, state.connection?.serverUrl, state.mode, state.selectedLibraryId)
        val localById = local.associateBy { it.record.album.id }
        val albums = if (state.offlineOnly) local.filter { it.ready }.map { it.album }
        else (state.albums.map { it.copy(localThumb = localById[it.id]?.coverUri) } + local.map { it.album }).distinctBy { it.id }
        val selected = state.selectedAlbum?.let { downloadedAlbum(it.id) }
        state = state.copy(albums = albums,
            selectedAlbum = state.selectedAlbum?.let { current -> (albums.firstOrNull { it.id == current.id } ?: current).copy(localThumb = selected?.coverUri) },
            tracks = if (selected != null) selected.tracks else state.tracks.map { it.copy(localUri = null) })
    }

    fun downloadSelectedAlbum() = viewModelScope.launch {
        val album = state.selectedAlbum ?: return@launch
        val connection = state.connection ?: return@launch
        val library = state.selectedLibraryId ?: return@launch
        if (state.downloadBusy || state.tracks.isEmpty() || album.id.startsWith("playlist:")) return@launch
        if (state.offlineOnly) { show(Feature.ALBUM, uiText(R.string.download_needs_online)); return@launch }
        val tracks = state.tracks
        val mode = state.mode
        state = state.withMessage(Feature.ALBUM, null).copy(downloadBusy = true)
        try {
            albumDownloads.download(connection, library, mode, album, tracks, state.accountScope)?.let { show(Feature.ALBUM, it) }
            updateDownloads()
        } finally { state = state.copy(downloadBusy = false) }
    }

    fun removeDownload(albumId: String) = viewModelScope.launch {
        val connection = state.connection ?: return@launch
        if (state.playback.album?.id == albumId) { player.stop(); state = state.copy(showPlayer = false) }
        val error = albumDownloads.remove(connection.serverUrl, albumId, state.accountScope)
        if (error != null) { show(Feature.DOWNLOAD, error); return@launch }
        updateDownloads()
        notify(uiText(R.string.download_removed))
    }

    fun beginPlexSignIn() {
        if (authJob?.isActive == true) return
        authJob = viewModelScope.launch {
            state = state.withMessage(Feature.SETUP, uiText(R.string.setup_connecting)).copy(loading = true, signingIn = true, plexAuthUrl = null)
            try {
                val token = signIn.signIn { pin, message ->
                    state = state.withMessage(Feature.SETUP, message).copy(loading = false, plexLinkCode = pin?.code)
                }
                loadDiscoveredServers(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val linked = store.accountToken() != null
                val reason = error.userText(uiText(R.string.setup_signin_failed_fallback))
                state = state.withMessage(Feature.SETUP, uiText(if (linked) R.string.setup_servers_failed else R.string.setup_signin_failed, reason))
                    .copy(loading = false, signingIn = false, plexAuthUrl = null, plexLinkCode = store.pendingPin()?.code)
            }
        }
    }

    private suspend fun loadDiscoveredServers(accountToken: String) {
        state = state.withMessage(Feature.SETUP, uiText(R.string.setup_finding_servers)).copy(loading = true, signingIn = true, plexLinkCode = null)
        val servers = account.servers(accountToken)
        state = state.withMessage(Feature.SETUP, uiText(if (servers.isEmpty()) R.string.setup_no_servers else R.string.setup_choose_server))
            .copy(loading = false, signingIn = false, discoveredServers = servers)
    }

    fun authPageOpened() { state = state.copy(plexAuthUrl = null) }

    fun openPlexLink() { state = state.copy(plexAuthUrl = account.linkUrl()) }

    fun browserUnavailable() { state = state.withMessage(Feature.SETUP, uiText(R.string.setup_browser_unavailable)).copy(plexAuthUrl = null) }

    fun chooseServer(server: PlexServer) = jobs.launch(uiText(R.string.job_connecting_to, serverName(server)), Feature.SETUP) {
        val session = connect.connect(server)
        apply(session)
        state = state.withMessage(Feature.SETUP, null).copy(sections = session.sections, discoveredServers = emptyList(), showSettings = false)
        notify(uiText(R.string.connected_to, serverName(server)))
        refreshSyncConflict()
        refreshDownloads(adopt = true)
        if (state.setupPlan == null) loadAlbums()
    }

    fun saveConnection() {
        if (state.serverUrl.isBlank() || state.token.isBlank()) {
            show(Feature.SETUP, uiText(R.string.setup_enter_address_and_token))
            return
        }
        apply(connect.saveManual(state.serverUrl, state.token))
        state = state.withMessage(Feature.SETUP, null).copy(showSettings = false)
        refreshSyncConflict()
        refreshDownloads(adopt = true)
        loadSections()
    }

    fun chooseMode(mode: LibraryMode) {
        if (state.mode == mode) return
        albumJob?.cancel()
        rememberCurrentLibrary()
        store.saveMode(mode)
        val library = store.library(mode)
        state = state.withMessage(Feature.LIBRARY, null).copy(mode = mode, selectedLibraryId = library, selectedAlbum = null, selectedPlaylist = null, tracks = emptyList(), albums = warmLibrary(library), query = "", personalShelf = PersonalShelf.NONE, selectedArtist = null)
        mergeDownloads()
        loadAlbums()
    }

    fun chooseLibrary(section: PlexSection) {
        rememberCurrentLibrary()
        store.saveLibrary(state.mode, section.id)
        state = state.copy(
            selectedLibraryId = section.id,
            musicLibraryId = if (state.mode == LibraryMode.MUSIC) section.id else state.musicLibraryId,
            audiobookLibraryId = if (state.mode == LibraryMode.AUDIOBOOK) section.id else state.audiobookLibraryId,
            selectedAlbum = null, tracks = emptyList(), albums = warmLibrary(section.id), selectedArtist = null,
        )
        mergeDownloads()
        loadAlbums()
    }

    /** Handles just the current wizard library, then opens Music when it was selected (otherwise Audiobooks). */
    fun chooseSetupLibrary(section: PlexSection) {
        val plan = state.setupPlan ?: return
        val mode = when (state.setupStep) {
            SetupStep.MUSIC_LIBRARY -> LibraryMode.MUSIC
            SetupStep.AUDIOBOOK_LIBRARY -> LibraryMode.AUDIOBOOK
            else -> return
        }
        store.saveLibrary(mode, section.id)
        state = state.copy(
            musicLibraryId = if (mode == LibraryMode.MUSIC) section.id else state.musicLibraryId,
            audiobookLibraryId = if (mode == LibraryMode.AUDIOBOOK) section.id else state.audiobookLibraryId,
        )
        if (state.setupStep != null) return

        val destination = plan.destination
        val destinationLibrary = if (destination == LibraryMode.MUSIC) state.musicLibraryId else state.audiobookLibraryId
        store.saveSetupPlan(null)
        store.saveMode(destination)
        state = state.withMessage(Feature.SETUP, null).copy(
            setupPlan = null, mode = destination, selectedLibraryId = destinationLibrary,
            selectedAlbum = null, tracks = emptyList(), albums = warmLibrary(destinationLibrary), selectedArtist = null,
        )
        mergeDownloads()
        loadAlbums()
    }

    fun chooseAlbum(album: PlexAlbum) {
        val connection = state.connection ?: return
        albumJob?.cancel()
        // The book screen shows "Review Plex progress" when a background drain was blocked; check once on opening rather than on a poll.
        if (state.mode == LibraryMode.AUDIOBOOK) refreshSyncConflict()
        val local = downloadedAlbum(album.id)
        if (local != null) {
            state = state.withMessage(Feature.ALBUM, null).copy(selectedAlbum = (state.albums.firstOrNull { it.id == album.id } ?: album).copy(localThumb = local.coverUri), selectedPlaylist = null, tracks = local.tracks)
            return
        }
        if (state.offlineOnly) { show(Feature.LIBRARY, uiText(R.string.album_not_downloaded)); return }
        state = state.copy(selectedAlbum = album, selectedPlaylist = state.playlists.firstOrNull { album.id == "playlist:${it.id}" }, tracks = emptyList())
        albumJob = jobs.launch(uiText(R.string.job_loading_title, album.title), Feature.ALBUM) {
            val loaded = albumPlayback.tracks(connection, album.id) { tracks -> if (state.selectedAlbum?.id == album.id) state = state.copy(tracks = tracks) }
            // A failed refresh of a stale list keeps that list (still playable) and says so, rather than blanking the screen.
            if (loaded == TracksLoaded.STALE_KEPT && state.selectedAlbum?.id == album.id) show(Feature.ALBUM, uiText(R.string.tracks_showing_saved))
        }
    }

    /** `offsetMs` only applies with an explicit `index`; an unindexed play resumes the saved position. */
    fun play(infinite: Boolean = false, index: Int? = null, shuffled: Boolean = false, offsetMs: Long = 0) {
        val connection = state.connection ?: return
        val album = state.selectedAlbum ?: return
        if (state.tracks.isEmpty() || !state.playback.ready) return
        val outcome = albumPlayback.play(connection, album, state.tracks, state.mode, state.playback, state.history, state.accountScope, state.offlineOnly, infinite, index, shuffled, offsetMs)
        if (outcome is PlayOutcome.Refused) { show(Feature.ALBUM, outcome.message); return }
        state = state.withMessage(Feature.ALBUM, null).copy(showPlayer = true, queue = player.queue())
    }

    fun discoverSonos(preferDirectory: Boolean = false) = viewModelScope.launch {
        if (state.discovering) return@launch
        state = state.copy(discovering = true)
        try {
            val result = sonos.discover(store.householdDirectoryToken(), preferDirectory)
            val speakers = (result.speakers + state.speakers).distinctBy { it.host }
            store.saveSpeakers(speakers)
            state = state.withMessage(Feature.SPEAKER, result.notice).copy(speakers = speakers, selectedSpeaker = speakers.firstOrNull())
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { show(Feature.SPEAKER, uiText(R.string.sonos_find_failed))
        } finally { state = state.copy(discovering = false) }
    }

    fun chooseSpeaker(speaker: SonosSpeaker) { state = state.copy(selectedSpeaker = speaker) }

    fun pushToSonos() {
        if (state.offlineOnly) { show(Feature.SPEAKER, uiText(R.string.sonos_offline)); return }
        val speaker = state.selectedSpeaker ?: run {
            show(Feature.SPEAKER, uiText(R.string.sonos_choose_speaker))
            return
        }
        val connection = state.connection ?: return
        val track = state.playback.track ?: state.tracks.firstOrNull() ?: return
        jobs.launch(uiText(R.string.job_sending_to, speaker.name), Feature.SPEAKER) {
            sonos.play(speaker, track, connection)
            player.pause()
            state = state.copy(showDevices = false)
            notify(uiText(R.string.sonos_sent, track.title, speaker.name))
        }
    }

    private fun loadSections(force: Boolean = false) {
        if (state.offlineOnly) return
        val connection = state.connection ?: return
        sectionsJob = jobs.launch(uiText(R.string.job_checking_libraries), Feature.LIBRARY) {
            library.sections(connection, force) { sections ->
                state = state.copy(sections = sections)
                if (state.setupPlan == null) loadAlbums(force)
            }
        }
    }

    private fun loadAlbums(force: Boolean = false) {
        libraryJob?.cancel()
        catalogueRefresh?.cancel()
        prewarmJob?.cancel()
        if (state.offlineOnly) { mergeDownloads(); return }
        val connection = state.connection ?: return
        val libraryId = store.library(state.mode) ?: return
        val mode = state.mode
        libraryJob = jobs.launch(uiText(if (mode == LibraryMode.MUSIC) R.string.job_loading_music else R.string.job_loading_audiobooks), Feature.LIBRARY) {
            if (!force) warmLibrary(libraryId).takeIf { it.isNotEmpty() }?.let { showLibraryIfCurrent(connection, libraryId, it) }
            library.albums(connection, libraryId, force) { albums ->
                rememberLibrary(connection, libraryId, albums)
                showLibraryIfCurrent(connection, libraryId, albums)
            }
            updateDownloads()
            prewarmTracks(connection, libraryId, mode)
        }
    }

    /**
     * Once a catalogue is on screen, warms the track lists of its likeliest next titles (see [prewarmCandidates]) in the
     * background. A later catalogue load, a mode/library/server/account change, sign-out and offline-only mode all cancel it.
     */
    private fun prewarmTracks(connection: PlexConnection, library: String, mode: LibraryMode) {
        prewarmJob?.cancel()
        if (state.offlineOnly || !browsing(connection, library, mode)) return
        val ids = prewarmCandidates(state.history, state.albums, state.downloads, connection.serverUrl, state.accountScope, mode, state.offlineOnly)
        if (ids.isEmpty()) return
        prewarmJob = viewModelScope.launch {
            prewarm.prewarm(connection, ids, state.offlineOnly) { !state.offlineOnly && browsing(connection, library, mode) }
        }
    }

    private fun libraryKey(connection: PlexConnection, library: String) = "${connection.serverUrl}\u0000${connection.token}\u0000$library"
    private fun warmLibrary(library: String?): List<PlexAlbum> = state.connection?.let { connection -> library?.let { warmAlbums[libraryKey(connection, it)] } }.orEmpty()
    private fun rememberLibrary(connection: PlexConnection, library: String, albums: List<PlexAlbum>) { warmAlbums[libraryKey(connection, library)] = albums }
    private fun rememberCurrentLibrary() { state.connection?.let { connection -> state.selectedLibraryId?.let { library -> rememberLibrary(connection, library, state.albums) } } }
    private fun showLibraryIfCurrent(connection: PlexConnection, library: String, albums: List<PlexAlbum>) {
        if (browsing(connection, library)) { state = state.copy(albums = albums); mergeDownloads() }
    }

    fun showPlaylists(value: Boolean) {
        closeAlbum()
        state = state.copy(musicCollection = if (value) MusicCollectionTab.PLAYLISTS else MusicCollectionTab.ALBUMS, personalShelf = PersonalShelf.NONE, selectedArtist = null)
        if (value) loadPlaylists()
    }

    private fun loadPlaylists(force: Boolean = false) {
        val connection = state.connection ?: return
        playlistJob?.cancel()
        playlistJob = jobs.launch(uiText(R.string.job_loading_playlists), Feature.PLAYLIST) {
            library.playlists(connection, force, state.offlineOnly) { playlists -> state = state.copy(playlists = playlists) }
        }
    }

    fun choosePlaylist(playlist: PlexPlaylist) {
        chooseAlbum(playlist.album())
        state = state.copy(selectedPlaylist = playlist)
    }

    fun openAddToPlaylist(tracks: List<PlexTrack>) {
        if (state.mode != LibraryMode.MUSIC || state.offlineOnly) return
        state = state.withMessage(Feature.PLAYLIST, null).copy(playlistAddTracks = tracks)
        loadPlaylists()
    }

    fun closeAddToPlaylist() { if (!state.playlistBusy) state = state.copy(playlistAddTracks = emptyList()) }

    fun saveToPlaylist(playlist: PlexPlaylist?, title: String = "") = playlistMutation(uiText(R.string.job_saving_playlist)) { api ->
        val tracks = state.playlistAddTracks
        if (tracks.isEmpty()) return@playlistMutation null
        if (playlist == null) api.createPlaylist(title, tracks)
        else {
            requireText(!playlist.smart) { uiText(R.string.playlist_smart_managed) }
            api.addToPlaylist(playlist.id, tracks)
            // Plex confirmed the append: an open copy of that playlist is reloaded rather than left a step behind.
            if (state.selectedPlaylist?.id == playlist.id) reloadPlaylistTracks(api, playlist)
        }
        state = state.copy(playlistAddTracks = emptyList())
        uiText(R.string.playlist_saved_to, playlist?.title ?: title.trim())
    }

    fun renamePlaylist(title: String) {
        val selected = state.selectedPlaylist ?: return
        playlistMutation(uiText(R.string.job_renaming_playlist)) { api ->
            api.renamePlaylist(selected.id, title)
            if (state.selectedPlaylist?.id == selected.id) state = state.copy(selectedPlaylist = selected.copy(title = title.trim()), selectedAlbum = selected.copy(title = title.trim()).album())
            null
        }
    }

    fun removePlaylistTrack(track: PlexTrack) {
        val selected = state.selectedPlaylist?.takeUnless { it.smart } ?: return
        val item = track.playlistItemId ?: return
        playlistMutation(uiText(R.string.job_removing_playlist_entry)) { api -> api.removePlaylistItem(selected.id, item); reloadPlaylistTracks(api, selected); null }
    }

    fun deletePlaylist() {
        val selected = state.selectedPlaylist ?: return
        val server = state.connection?.serverUrl ?: return
        playlistMutation(uiText(R.string.job_deleting_playlist)) { api ->
            api.deletePlaylist(selected.id)
            if (state.playback.album?.id == "playlist:${selected.id}") { player.stop(); state = state.copy(showPlayer = false) }
            store.removeProgress(server, "playlist:${selected.id}")
            state = state.copy(history = store.history())
            if (state.selectedPlaylist?.id == selected.id) closeAlbum()
            null
        }
    }

    fun movePlaylistTrack(index: Int, direction: Int) {
        val selected = state.selectedPlaylist?.takeUnless { it.smart } ?: return
        val tracks = state.tracks
        val destination = index + direction
        if (index !in tracks.indices || destination !in tracks.indices) return
        val item = tracks[index].playlistItemId ?: return
        val reordered = tracks.toMutableList().apply { add(destination, removeAt(index)) }
        val after = reordered.getOrNull(destination - 1)?.playlistItemId
        playlistMutation(uiText(R.string.job_reordering_playlist)) { api -> api.movePlaylistItem(selected.id, item, after); reloadPlaylistTracks(api, selected); null }
    }

    private suspend fun reloadPlaylistTracks(api: PlexGateway, selected: PlexPlaylist) {
        val tracks = api.albumTracks("playlist:${selected.id}")
        if (state.selectedPlaylist?.id == selected.id) state = state.copy(tracks = tracks, selectedPlaylist = selected.copy(trackCount = tracks.size))
    }

    /** `work` returns the notice to show once Plex has confirmed the change, or null for a silent edit. */
    private fun playlistMutation(label: UiText, work: suspend (PlexGateway) -> UiText?) = viewModelScope.launch {
        val connection = state.connection ?: return@launch
        if (state.playlistBusy || state.offlineOnly) return@launch
        // A background prewarm must not race the edit and write back a pre-edit playlist copy.
        prewarmJob?.cancel()
        state = state.withMessage(Feature.PLAYLIST, null).copy(playlistBusy = true)
        try {
            when (val result = playlistMutations.mutate(connection, label, work)) {
                is MutationResult.Done -> { state = state.copy(playlists = result.playlists); result.notice?.let(::notify) }
                is MutationResult.Failed -> show(Feature.PLAYLIST, result.error)
            }
        } finally { state = state.copy(playlistBusy = false) }
    }

    // Ticket 138: listening history and stats. Local only: nothing here reaches Plex or the timeline outbox.
    fun loadListeningHistory() {
        val server = state.connection?.serverUrl ?: return
        val scope = state.accountScope
        update { it.copy(listeningHistory = null, historyNotice = null) }
        viewModelScope.launch {
            val view = withContext(Dispatchers.IO) { listeningHistory.load(server, scope) }
            update { if (it.connection?.serverUrl == server && it.accountScope == scope) it.copy(listeningHistory = view) else it }
        }
    }
    fun resetBookProgress(albumId: String) {
        val loaded = state.playback.album?.id
        changeListeningHistory { server, scope -> listeningHistory.reset(server, scope, albumId, loaded) }
    }
    fun clearListeningHistory() = changeListeningHistory { server, scope -> listeningHistory.clear(server, scope) }
    fun dismissHistoryNotice() = update { it.copy(historyNotice = null) }
    private fun changeListeningHistory(change: (String, String?) -> HistoryChange) {
        val server = state.connection?.serverUrl ?: return
        val scope = state.accountScope
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { change(server, scope) to store.history() }
            when (val outcome = result.first) {
                is HistoryChange.Done -> update { it.copy(listeningHistory = outcome.view, history = result.second, historyNotice = null) }
                is HistoryChange.Refused -> update { it.copy(historyNotice = outcome.message) }
            }
        }
    }

    // ---- Ticket 131: audiobook collections, series and the reading list -------------------------------------------------

    /** Shelf data is replaced, not merged, when the server, account or library changes; a collection run survives browsing. */
    private fun updateShelves(transform: (BookShelvesState) -> BookShelvesState) = update { current ->
        val owner = current.shelfOwner
        val base = current.shelves.takeIf { it.owner == owner } ?: BookShelvesState(owner, run = current.shelves.run)
        current.copy(shelves = transform(base))
    }

    private fun unknownAuthor() = getApplication<Application>().getString(R.string.unknown_author)

    fun openBookShelf(shelf: PersonalShelf) {
        openShelf(shelf)
        shelfJob?.cancel()
        updateShelves { it.copy(selected = null, books = emptyList(), booksLoad = null, refreshing = false) }
        when (shelf) {
            PersonalShelf.COLLECTIONS -> loadBookCollections(force = false)
            PersonalShelf.READING_LIST -> loadReadingList()
            else -> Unit
        }
    }

    private fun loadBookCollections(force: Boolean) {
        val connection = state.connection ?: return
        val library = state.selectedLibraryId ?: return
        val owner = state.shelfOwner
        shelfJob?.cancel()
        updateShelves { it.copy(refreshing = force) }
        shelfJob = jobs.launch(uiText(R.string.job_loading_collections), Feature.LIBRARY) {
            try {
                val loaded = loadCollections.collections(connection, library, force, state.offlineOnly) { list ->
                    if (state.shelfOwner == owner) updateShelves { it.copy(collections = list) }
                }
                if (state.shelfOwner == owner) {
                    updateShelves { it.copy(collectionsLoad = loaded) }
                    if (loaded == ShelfLoad.STALE_KEPT) show(Feature.LIBRARY, uiText(R.string.collections_showing_saved))
                }
            } finally { if (state.shelfOwner == owner) updateShelves { it.copy(refreshing = false) } }
        }
    }

    fun openBookGroup(group: BookGroup) {
        shelfJob?.cancel()
        updateShelves { it.copy(selected = group, books = emptyList(), booksLoad = null, refreshing = false) }
        if (group.kind == BookGroupKind.COLLECTION) loadCollectionBooks(group, force = false)
    }

    fun closeBookGroup() {
        shelfJob?.cancel()
        updateShelves { it.copy(selected = null, books = emptyList(), booksLoad = null, refreshing = false) }
    }

    /** A series is drawn from the catalogue in memory; only a Plex collection loads its own children. */
    private fun loadCollectionBooks(group: BookGroup, force: Boolean) {
        val connection = state.connection ?: return
        val library = state.selectedLibraryId ?: return
        val owner = state.shelfOwner
        fun current() = state.shelfOwner == owner && state.shelves.selected?.key == group.key
        shelfJob?.cancel()
        updateShelves { it.copy(refreshing = force) }
        shelfJob = jobs.launch(uiText(R.string.job_loading_title, group.title), Feature.LIBRARY) {
            try {
                val loaded = loadCollections.books(connection, library, group.key, force, state.offlineOnly) { books ->
                    if (current()) updateShelves { it.copy(books = books) }
                }
                if (current()) {
                    updateShelves { it.copy(booksLoad = loaded) }
                    if (loaded == ShelfLoad.STALE_KEPT) show(Feature.LIBRARY, uiText(R.string.collections_showing_saved))
                }
            } finally { if (state.shelfOwner == owner) updateShelves { it.copy(refreshing = false) } }
        }
    }

    /** Pull-to-refresh on the collections, a collection, series (the catalogue) or the reading list. */
    fun refreshBookShelf() {
        val selected = state.currentShelves.selected
        when {
            selected?.kind == BookGroupKind.COLLECTION -> loadCollectionBooks(selected, force = true)
            selected == null && state.personalShelf == PersonalShelf.COLLECTIONS -> loadBookCollections(force = true)
            else -> { if (state.personalShelf == PersonalShelf.READING_LIST) loadReadingList(); refresh() }
        }
    }

    private fun groupBooks(group: BookGroup): List<PlexAlbum> =
        if (group.kind == BookGroupKind.SERIES) seriesBooks(state.albums, group, unknownAuthor()) else state.currentShelves.books

    /**
     * Plays the open collection's unfinished books in its order, one book at a time (see [org.johnfegan.plextouch.app.CollectionRun]).
     * No Plex playlist is created or changed.
     */
    fun playUnfinished() {
        val connection = state.connection ?: return
        val group = state.currentShelves.selected ?: return
        if (state.mode != LibraryMode.AUDIOBOOK || !state.playback.ready) return
        val unfinished = unfinishedBooks(groupBooks(group), state.history, state.downloads, connection.serverUrl, state.accountScope, state.offlineOnly)
        val run = startCollectionRun(group.key, unfinished, connection.serverUrl, state.accountScope)
            ?: run { show(Feature.LIBRARY, uiText(if (state.offlineOnly) R.string.collection_nothing_downloaded else R.string.collection_all_finished)); return }
        state = state.copy(shelves = state.shelves.copy(run = run))
        startRunBook(connection, run.books.first(), open = true)
        notify(uiPlural(R.plurals.collection_run_started, run.books.size, run.books.size, group.title))
    }

    private fun startRunBook(connection: PlexConnection, album: PlexAlbum, open: Boolean) {
        runJob?.cancel()
        runJob = jobs.launch(uiText(R.string.job_loading_title, album.title), Feature.LIBRARY) {
            val local = downloadedAlbum(album.id)
            var tracks = local?.tracks.orEmpty()
            if (local == null && !state.offlineOnly) albumPlayback.tracks(connection, album.id) { tracks = it }
            if (state.connection != connection) return@launch
            val outcome = if (tracks.isEmpty()) PlayOutcome.Ignored
                else albumPlayback.play(connection, local?.album ?: album, tracks, LibraryMode.AUDIOBOOK, state.playback, state.history, state.accountScope, state.offlineOnly)
            when (outcome) {
                PlayOutcome.Started -> state = state.copy(showPlayer = state.showPlayer || open, queue = player.queue())
                is PlayOutcome.Refused -> { state = state.copy(shelves = state.shelves.copy(run = null)); show(Feature.LIBRARY, outcome.message) }
                PlayOutcome.Ignored -> state = state.copy(shelves = state.shelves.copy(run = null))
            }
        }
    }

    /** After each history reload: when the run's book has been saved finished and the player stopped, start the next one. */
    private fun advanceCollectionRun() {
        val run = state.shelves.run ?: return
        if (runJob?.isActive == true) return
        when (val step = collectionRunStep(run, state.playback, state.history, state.connection?.serverUrl, state.accountScope)) {
            is RunStep.Keep -> if (step.run != run) state = state.copy(shelves = state.shelves.copy(run = step.run))
            is RunStep.Next -> {
                state = state.copy(shelves = state.shelves.copy(run = step.run))
                state.connection?.let { startRunBook(it, step.album, open = false) }
            }
            RunStep.End -> state = state.copy(shelves = state.shelves.copy(run = null))
        }
    }

    private fun readingListKey(): Pair<String, String>? = state.connection?.let { connection -> state.accountScope?.let { it to connection.serverUrl } }

    /** Reads this account's reading list for this server off the main thread; screens call it when their owner changes. */
    fun loadReadingList() {
        val (scope, server) = readingListKey() ?: return
        val owner = state.shelfOwner
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { readingLists.readingList(scope, server) }
            if (state.shelfOwner == owner) updateShelves { it.copy(readingList = entries) }
        }
    }

    private fun changeReadingList(change: (List<ReadingListEntry>) -> List<ReadingListEntry>, done: () -> Unit = {}) {
        val (scope, server) = readingListKey() ?: return
        val owner = state.shelfOwner
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { readingLists.updateReadingList(scope, server, change) }
            if (state.shelfOwner == owner) updateShelves { it.copy(readingList = entries) }
            done()
        }
    }

    fun toggleReadingList(album: PlexAlbum) {
        if (album.id.startsWith("playlist:")) return
        var added: ReadingListAdd? = null
        changeReadingList({ current ->
            if (inReadingList(current, album.id)) ReadingLists.remove(current, album.id)
            else ReadingLists.add(current, album, System.currentTimeMillis()).also { added = it.second }.first
        }) {
            when (added) {
                null -> notify(uiText(R.string.reading_list_removed))
                ReadingListAdd.ADDED -> notify(uiText(R.string.reading_list_added))
                ReadingListAdd.FULL -> notify(uiPlural(R.plurals.reading_list_full, ReadingLists.MAX_ITEMS))
                ReadingListAdd.ALREADY_LISTED -> Unit
            }
        }
    }

    fun removeFromReadingList(albumId: String) = changeReadingList({ ReadingLists.remove(it, albumId) })
    fun moveInReadingList(albumId: String, direction: Int) = changeReadingList({ ReadingLists.move(it, albumId, direction) })

    // ---- end ticket 131 ------------------------------------------------------------------------------------------------

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(downloadFinished)
        player.release()
    }

    private companion object {
        /** Long enough for the service's queued store write to land after a play/pause before history is re-read. */
        const val HISTORY_SETTLE_MS = 1_000L
        const val PLAYING_REFRESH_MS = 30_000L
        const val DOWNLOAD_POLL_MS = 2_000L
        /** How long a shortcut's resume waits for the media controller on a cold start before giving up quietly. */
        const val PLAYER_CONNECT_MS = 5_000L
    }
}

/** The parts of [PlexTouchUiState] the widget depends on; an unrelated change does not refresh it. */
private data class WidgetInputsKey(val server: String?, val scope: String?, val offlineOnly: Boolean, val downloaded: List<Pair<String, String>>)
