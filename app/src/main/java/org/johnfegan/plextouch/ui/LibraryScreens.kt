package org.johnfegan.plextouch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.johnfegan.plextouch.data.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import org.johnfegan.plextouch.R
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LibraryShell(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val back = backAction(state)
    BackHandler(enabled = back != null) {
        when (backAction(state)) {
            BackAction.CLOSE_PLAYER -> vm.openPlayer(false)
            BackAction.CLOSE_SETTINGS -> vm.openSettings(false)
            BackAction.CLOSE_ALBUM -> vm.closeAlbum()
            BackAction.CLEAR_ARTIST -> vm.chooseArtist(null)
            BackAction.CLOSE_BOOK_GROUP -> vm.closeBookGroup()
            BackAction.HOME_TAB -> vm.chooseTab(LibraryTab.HOME)
            null -> Unit
        }
    }
    // Transient successes arrive once each; the host is overlaid on the settings and player layers so a notice sent there is not lost.
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it.asString(context)) } }
    when {
        state.showSettings -> Box(Modifier.fillMaxSize()) { SettingsScreen(state, vm); SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) }
        state.showPlayer && state.playback.album != null -> Box(Modifier.fillMaxSize()) { PlayerScreen(state, vm); SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) }
        else -> Scaffold(
            topBar = { if (state.selectedAlbum == null) LibraryHeader(state, vm) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column(Modifier.background(PlexBackground)) {
                    if (showsMiniPlayer(state)) MiniPlayer(state, vm)
                    else lastPlayed(state.history, state.connection?.serverUrl, state.mode, state.accountScope)?.let { LastPlayedBar(it, state, vm) }
                    // The bar's height is fixed by Material; at very large font sizes only the selected tab shows its label
                    // (the others keep it as their TalkBack label), so the labels are never clipped.
                    val allLabels = LocalDensity.current.fontScale < LARGE_FONT_SCALE
                    NavigationBar(containerColor = PlexBackground, tonalElevation = 0.dp) {
                        listOf(Triple(LibraryTab.HOME, R.string.tab_home, Icons.Rounded.Home), Triple(LibraryTab.SEARCH, R.string.tab_search, Icons.Rounded.Search), Triple(LibraryTab.LIBRARY, R.string.tab_library, Icons.Rounded.LibraryMusic), Triple(LibraryTab.DOWNLOADS, R.string.tab_downloads, Icons.Rounded.DownloadForOffline)).forEach { (tab, labelId, icon) ->
                            val label = stringResource(labelId)
                            NavigationBarItem(selected = state.tab == tab, onClick = { vm.chooseTab(tab) },
                                icon = { Icon(icon, label, Modifier.size(24.dp)) }, label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }, alwaysShowLabel = allLabels,
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = PlexHighlight, selectedTextColor = Color.White, indicatorColor = PlexBackground, unselectedIconColor = PlexMuted, unselectedTextColor = PlexMuted))
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    state.selectedAlbum != null -> AlbumScreen(state, vm)
                    state.tab == LibraryTab.DOWNLOADS -> DownloadsScreen(state, vm)
                    state.selectedLibraryId == null -> EmptyLibrary(state, vm)
                    state.tab == LibraryTab.HOME -> HomeScreen(state, vm)
                    state.tab == LibraryTab.SEARCH -> SearchScreen(state, vm)
                    state.mode == LibraryMode.MUSIC -> MusicCollection(state, vm) { CollectionScreen(state, vm) }
                    else -> Column {
                        AudiobookShelfChips(state, vm)
                        Box(Modifier.weight(1f)) { if (state.personalShelf in BOOK_SHELVES) BookShelfScreen(state, vm) else CollectionScreen(state, vm) }
                    }
                }
                if (state.loading && state.albums.isEmpty() && state.selectedAlbum == null && state.personalShelf !in BOOK_SHELVES) CircularProgressIndicator(Modifier.align(Alignment.Center).size(30.dp), color = PlexHighlight, strokeWidth = 2.dp)
            }
        }
    }
    if (state.selectedLibraryId != null) PreloadHomeArtwork(state)
    if (state.showDevices) DeviceSheet(state, vm)
    if (state.playlistAddTracks.isNotEmpty()) AddToPlaylistDialog(state, vm)
    if (state.progressComparison != null) ProgressComparisonDialog(state, vm)
    if (state.metadataEditor != null) MetadataEditorDialog(state, vm)
}

@Composable
private fun LibraryHeader(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(PlexBlue), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_sennachie_mark), stringResource(R.string.app_name), Modifier.fillMaxSize().padding(5.dp), tint = Color.White)
        }
        // Scrolls sideways at large font sizes, keeping Settings on screen.
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(LibraryMode.MUSIC, LibraryMode.AUDIOBOOK).forEach { mode ->
                FilterChip(selected = state.mode == mode, onClick = { vm.chooseMode(mode) }, label = { Text(stringResource(mode.label), fontWeight = FontWeight.SemiBold, fontSize = 13.sp) },
                    shape = CircleShape, border = null,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PlexBlue, selectedLabelColor = Color(0xFF000D15), containerColor = PlexPanel, labelColor = PlexMuted))
            }
        }
        IconButton(onClick = { vm.openSettings(true) }) { Icon(Icons.Rounded.Settings, stringResource(R.string.action_settings), tint = PlexMuted) }
    }
}

@Composable
private fun HomeScreen(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val book = state.mode == LibraryMode.AUDIOBOOK
    val recent = remember(state.albums) { presentAlbums(state.albums, "", AlbumSort.RECENT) }
    val progress = remember(state.history, state.connection, state.mode, state.accountScope) { progressByAlbum(state.history, state.connection?.serverUrl, state.mode, state.accountScope) }
    val history = remember(state.history, state.albums, state.connection, state.mode, state.accountScope) {
        homeHistory(state.history, state.albums, state.connection?.serverUrl, state.mode, state.accountScope)
    }
    val continuing = if (book) history.firstOrNull { !it.finished } else history.firstOrNull()
    val featured = continuing?.album ?: recent.firstOrNull()
    LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        state.libraryError?.let { error -> item { InlineNotice(error.asString(), { vm.dismissMessage(Feature.LIBRARY) }, vm::refresh) } }
        if (featured != null) item {
            FeatureCard(featured, continuing, state, onClick = { vm.chooseAlbum(featured) })
        }
        item {
            val saved = if (book) state.listenAgainShelf else state.favouriteShelf
            SectionHeading(stringResource(if (book) R.string.shelf_listen_again else R.string.shelf_favourite_albums), if (saved.isNotEmpty()) stringResource(R.string.action_see_all) else null) {
                vm.openShelf(if (book) PersonalShelf.LISTEN_AGAIN else PersonalShelf.FAVOURITES)
            }
            Spacer(Modifier.height(14.dp))
            if (saved.isNotEmpty()) AlbumRail(saved.take(HOME_SAVED_SHELF), progress, state, vm)
            else Text(stringResource(if (state.offlineOnly) R.string.home_empty_offline else if (book) R.string.home_empty_listen_again else R.string.home_empty_favourites),
                Modifier.padding(horizontal = 20.dp), color = PlexMuted, style = MaterialTheme.typography.bodyMedium)
        }
        if (!book && history.size > 1) item {
            SectionHeading(stringResource(R.string.home_recently_played))
            Spacer(Modifier.height(14.dp))
            AlbumRail(history.map { it.album }.take(HOME_PLAYED_SHELF), progress, state, vm)
        }
        if (recent.isNotEmpty()) item {
            SectionHeading(stringResource(if (book) R.string.home_new_books else R.string.home_recently_added), stringResource(R.string.action_see_all)) { vm.chooseTab(LibraryTab.LIBRARY) }
            Spacer(Modifier.height(14.dp))
            AlbumRail(recent.take(HOME_RECENT_SHELF), progress, state, vm)
        }
        if (recent.size > 4) item {
            SectionHeading(stringResource(if (book) R.string.home_next_listen else R.string.home_rediscover))
            Spacer(Modifier.height(10.dp))
            recent.drop(3).take(4).forEach { album -> AlbumListRow(album, state, { vm.chooseAlbum(album) }) }
        }
        if (recent.isEmpty() && !state.loading) item { EmptyMessage(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body), stringResource(R.string.action_refresh), vm::refresh) }
    }
}

@Composable
private fun FeatureCard(album: PlexAlbum, progress: ListeningProgress?, state: PlexTouchUiState, onClick: () -> Unit) {
    val book = state.mode == LibraryMode.AUDIOBOOK
    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF094563), Color(0xFF152534)))).clickable(onClickLabel = stringResource(R.string.a11y_open), onClick = onClick).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Eyebrow(stringResource(if (progress != null) R.string.feature_pick_up else if (book) R.string.feature_bookshelf else R.string.feature_library), color = PlexHighlight)
            Text(album.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(albumArtist(album), color = Color(0xFFBDD1DE), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book && progress != null) {
                LinearProgressIndicator(progress = { progress.fraction }, Modifier.fillMaxWidth().height(3.dp), color = PlexHighlight, trackColor = Color.White.copy(alpha = .15f))
                Text(stringResource(R.string.time_left, listeningTime(progress.durationMs - progress.elapsedMs).asString()), Modifier.spokenAs(stringResource(R.string.time_left, spokenMinutes(progress.durationMs - progress.elapsedMs).asString())), style = MaterialTheme.typography.bodySmall, color = PlexHighlight)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(if (progress != null && book) R.string.action_continue_listening else if (book) R.string.action_open_audiobook else R.string.action_explore_album), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Rounded.ArrowForward, null, Modifier.size(16.dp))
            }
        }
        Artwork(album, state.artworkConnection, Modifier.width(122.dp).aspectRatio(1f), book)
    }
}

@Composable
private fun AlbumRail(albums: List<PlexAlbum>, progress: Map<String, ListeningProgress>, state: PlexTouchUiState, vm: PlexTouchViewModel) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(albums, key = { it.id }) { album -> AlbumTile(album, progress[album.id], state, Modifier.width(144.dp)) { vm.chooseAlbum(album) } }
    }
}

@Composable
private fun AlbumTile(album: PlexAlbum, progress: ListeningProgress?, state: PlexTouchUiState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = stringResource(R.string.a11y_open), onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Artwork(album, state.artworkConnection, Modifier.fillMaxWidth().aspectRatio(1f), state.mode == LibraryMode.AUDIOBOOK)
        Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
        Text(albumArtist(album), color = PlexMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state.mode == LibraryMode.AUDIOBOOK && progress != null) {
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().height(3.dp), color = PlexBlue, trackColor = PlexPanel)
            if (progress.finished) Text(stringResource(R.string.progress_finished), style = MaterialTheme.typography.bodySmall, color = PlexHighlight)
            else Text(stringResource(R.string.time_left, listeningTime(progress.durationMs - progress.elapsedMs).asString()), Modifier.spokenAs(stringResource(R.string.time_left, spokenMinutes(progress.durationMs - progress.elapsedMs).asString())), style = MaterialTheme.typography.bodySmall, color = PlexHighlight)
        }
    }
}

@Composable
internal fun AlbumListRow(album: PlexAlbum, state: PlexTouchUiState, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.a11y_open), onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(album, state.artworkConnection, Modifier.size(58.dp), state.mode == LibraryMode.AUDIOBOOK)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(album.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(albumArtist(album), color = PlexMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = PlexMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SearchScreen(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    // Filtered off the main thread from the catalogue already in memory (see CatalogueSearch); typing never waits on Plex.
    val search by vm.searchResults.collectAsStateWithLifecycle()
    val results = search.albums
    Column {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 14.dp).asHeading())
            OutlinedTextField(value = state.query, onValueChange = vm::search, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(if (state.mode == LibraryMode.MUSIC) R.string.search_placeholder_music else R.string.search_placeholder_books)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.search("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.search_clear)) } },
                shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = PlexPanel, focusedContainerColor = PlexPanel, unfocusedBorderColor = Color.Transparent, focusedBorderColor = PlexBlue))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.offlineOnly) Text(stringResource(R.string.search_scope_downloads), color = PlexHighlight, style = MaterialTheme.typography.bodySmall)
                Text(when {
                    state.query.isBlank() -> stringResource(if (state.mode == LibraryMode.MUSIC) R.string.search_browse_music else R.string.search_browse_audiobooks)
                    search.offline -> pluralStringResource(R.plurals.search_results_downloads, results.size, results.size)
                    else -> pluralStringResource(R.plurals.search_results, results.size, results.size)
                }, Modifier.politeLiveRegion(), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp)) {
            items(results, key = { it.id }) { album -> AlbumListRow(album, state) { vm.chooseAlbum(album) } }
            if (search.ready && results.isEmpty() && !state.loading) item {
                EmptyMessage(stringResource(R.string.search_no_matches), stringResource(if (search.offline) R.string.search_no_matches_offline_body else R.string.search_no_matches_body))
            }
        }
    }
}

@Composable
private fun CollectionScreen(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    var grid by rememberSaveable { mutableStateOf(true) }
    var filter by rememberSaveable(state.mode) { mutableStateOf(ProgressFilter.ALL) }
    var sortMenu by remember { mutableStateOf(false) }
    val source = when (state.personalShelf) {
        PersonalShelf.FAVOURITES -> state.favouriteShelf
        PersonalShelf.LISTEN_AGAIN -> state.listenAgainShelf
        else -> state.albums
    }
    val progress = remember(state.history, state.connection, state.mode, state.accountScope) { progressByAlbum(state.history, state.connection?.serverUrl, state.mode, state.accountScope) }
    val albums = remember(source, state.sort, filter, progress, state.personalShelf) {
        (if (state.personalShelf == PersonalShelf.LISTEN_AGAIN && state.sort == AlbumSort.RECENT) source else presentAlbums(source, "", state.sort)).filter { album ->
            val saved = progress[album.id]
            when (filter) { ProgressFilter.IN_PROGRESS -> saved != null && !saved.finished; ProgressFilter.FINISHED -> saved?.finished == true; ProgressFilter.ALL -> true }
        }
    }
    Column {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (state.personalShelf) { PersonalShelf.FAVOURITES -> R.string.shelf_favourite_albums; PersonalShelf.LISTEN_AGAIN -> R.string.shelf_listen_again; else -> R.string.collection_title_library }), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 14.dp).asHeading())
            Text(pluralStringResource(if (state.mode == LibraryMode.MUSIC) R.plurals.collection_albums else R.plurals.collection_audiobooks, source.size, source.size, stringResource(when (state.personalShelf) { PersonalShelf.NONE -> R.string.collection_sub_plex; PersonalShelf.FAVOURITES -> R.string.collection_sub_favourites; else -> R.string.collection_sub_saved })), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            if (state.mode == LibraryMode.AUDIOBOOK) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressFilter.entries.forEach { option -> FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(stringResource(option.label), fontSize = 12.sp) }, shape = CircleShape) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { sortMenu = true }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp), tint = PlexMuted)
                        Spacer(Modifier.width(6.dp)); Text(stringResource(if (state.personalShelf == PersonalShelf.LISTEN_AGAIN && state.sort == AlbumSort.RECENT) R.string.sort_last_listened else state.sort.label), color = PlexMuted, fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        AlbumSort.entries.forEach { sort -> DropdownMenuItem(text = { Text(stringResource(sort.label)) }, onClick = { vm.sort(sort); sortMenu = false }) }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = vm::refresh) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh_library), tint = PlexMuted) }
                IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, stringResource(if (grid) R.string.show_list else R.string.show_grid), tint = PlexMuted) }
            }
        }
        state.libraryError?.let { error -> InlineNotice(error.asString(), { vm.dismissMessage(Feature.LIBRARY) }, vm::refresh) }
        if (grid) LazyVerticalGrid(columns = GridCells.Adaptive(144.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(albums, key = { it.id }) { album -> AlbumTile(album, progress[album.id], state) { vm.chooseAlbum(album) } }
            if (albums.isEmpty() && !state.loading) item(span = { GridItemSpan(maxLineSpan) }) { EmptyMessage(stringResource(R.string.empty_nothing_title), stringResource(R.string.empty_nothing_body)) }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(albums, key = { it.id }) { album -> AlbumListRow(album, state) { vm.chooseAlbum(album) } }
            if (albums.isEmpty() && !state.loading) item { EmptyMessage(stringResource(R.string.empty_nothing_title), stringResource(R.string.empty_nothing_body)) }
        }
    }
}

/** A small capitalised label; [heading] when it names a section (Settings), so TalkBack's heading navigation finds it. */
@Composable
internal fun Eyebrow(text: String, color: Color = PlexMuted, heading: Boolean = false) {
    Text(text, if (heading) Modifier.asHeading() else Modifier, color = color, style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun SectionHeading(title: String, action: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).asHeading())
        if (action != null) TextButton(onClick = onClick, contentPadding = PaddingValues(start = 12.dp)) { Text(action, color = PlexMuted, fontSize = 12.sp) }
    }
}

@Composable
internal fun EmptyMessage(title: String, body: String, action: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(36.dp), tint = PlexBlue)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = PlexMuted, style = MaterialTheme.typography.bodyMedium)
        if (action != null) Button(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun EmptyLibrary(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    Column(Modifier.padding(top = 50.dp)) {
        EmptyMessage(stringResource(R.string.empty_library_title), stringResource(if (state.mode == LibraryMode.MUSIC) R.string.empty_library_music else R.string.empty_library_audiobooks), stringResource(R.string.action_choose_library)) { vm.openSettings(true) }
        state.libraryError?.let { error -> InlineNotice(error.asString(), { vm.dismissMessage(Feature.LIBRARY) }, vm::refresh) }
    }
}

@Composable
internal fun InlineNotice(message: String, dismiss: () -> Unit, retry: (() -> Unit)? = null) {
    Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = PlexPanel) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Errors and results arrive while the user is elsewhere on the screen; TalkBack reads each new one.
            Text(message, Modifier.weight(1f).politeLiveRegion(), style = MaterialTheme.typography.bodySmall, color = PlexMuted)
            if (retry != null) IconButton(onClick = retry) { Icon(Icons.Rounded.Refresh, stringResource(R.string.action_retry), Modifier.size(20.dp), tint = PlexHighlight) }
            IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.action_dismiss_message), Modifier.size(18.dp), tint = PlexMuted) }
        }
    }
}

/** The audiobook shelf filter; an enum rather than its label so the saved choice survives a language change. */
private enum class ProgressFilter(@StringRes val label: Int) { ALL(R.string.filter_all), IN_PROGRESS(R.string.filter_in_progress), FINISHED(R.string.filter_finished) }

/** An album's artist line; a playlist has none of its own and reads "Plex playlist". */
@Composable
internal fun albumArtist(album: PlexAlbum): String =
    if (album.artist.isBlank() && album.id.startsWith("playlist:")) stringResource(R.string.plex_playlist_artist) else album.artist

/** Ticket 140: from this font scale (Android's largest settings reach 2.0) dense rows switch to their large-text layout. */
const val LARGE_FONT_SCALE = 1.5f
