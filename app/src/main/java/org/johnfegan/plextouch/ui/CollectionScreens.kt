package org.johnfegan.plextouch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.app.ShelfLoad
import org.johnfegan.plextouch.app.unfinishedBooks
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum

/* Ticket 131: the audiobook Library's Collections, Series and Reading list shelves. */

/** The shelves reached from the audiobook Library's chip row; [LibraryShell] routes these to [BookShelfScreen]. */
val BOOK_SHELVES: Set<PersonalShelf> = setOf(PersonalShelf.COLLECTIONS, PersonalShelf.SERIES, PersonalShelf.READING_LIST)

/** "All books / Listen again" plus the ticket 131 shelves, scrolling sideways on a narrow phone. */
@Composable
internal fun AudiobookShelfChips(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(state.personalShelf == PersonalShelf.NONE, { vm.openShelf(PersonalShelf.NONE) }, label = { Text(stringResource(R.string.shelf_all_books)) })
        FilterChip(state.personalShelf == PersonalShelf.LISTEN_AGAIN, { vm.openShelf(PersonalShelf.LISTEN_AGAIN) }, label = { Text(stringResource(R.string.shelf_listen_again)) })
        listOf(PersonalShelf.COLLECTIONS to R.string.shelf_collections, PersonalShelf.SERIES to R.string.shelf_series, PersonalShelf.READING_LIST to R.string.shelf_reading_list).forEach { (shelf, label) ->
            FilterChip(state.personalShelf == shelf, { vm.openBookShelf(shelf) }, label = { Text(stringResource(label)) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookShelfScreen(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val shelves = state.currentShelves
    val selected = shelves.selected
    PullToRefreshBox(isRefreshing = shelves.refreshing || (state.loading && selected == null && state.personalShelf != PersonalShelf.COLLECTIONS), onRefresh = vm::refreshBookShelf, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            state.libraryError?.let { error -> InlineNotice(error.asString(), { vm.dismissMessage(Feature.LIBRARY) }, vm::refreshBookShelf) }
            when {
                selected != null -> BookGroupScreen(state, shelves, selected, vm)
                state.personalShelf == PersonalShelf.READING_LIST -> ReadingListScreen(state, shelves, vm)
                else -> BookGroupsScreen(state, shelves, vm)
            }
        }
    }
}

@Composable
private fun BookGroupsScreen(state: PlexTouchUiState, shelves: BookShelvesState, vm: PlexTouchViewModel) {
    val series = state.personalShelf == PersonalShelf.SERIES
    val unknown = stringResource(R.string.unknown_author)
    val groups = remember(series, shelves.collections, state.albums, unknown) {
        if (series) seriesGroups(state.albums, unknown) else shelves.collections.map(::collectionGroup)
    }
    var grid by rememberSaveable { mutableStateOf(true) }
    val loaded = series || shelves.collectionsLoad != null
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (series) R.string.shelf_series else R.string.shelf_collections), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f).asHeading())
            IconButton(onClick = vm::refreshBookShelf) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh_library), tint = PlexMuted) }
            IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, stringResource(if (grid) R.string.show_list else R.string.show_grid), tint = PlexMuted) }
        }
        val empty: @Composable () -> Unit = {
            if (groups.isEmpty() && loaded && !state.loading) EmptyMessage(
                stringResource(if (series) R.string.series_empty_title else R.string.collections_empty_title),
                stringResource(when { series -> R.string.series_empty_body; shelves.collectionsLoad == ShelfLoad.NOTHING_SAVED -> R.string.collections_offline_empty; else -> R.string.collections_empty_body }),
            )
        }
        if (grid) LazyVerticalGrid(columns = GridCells.Adaptive(144.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(groups, key = { it.key }) { group -> GroupTile(group, state) { vm.openBookGroup(group) } }
            item(span = { GridItemSpan(maxLineSpan) }) { empty() }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(groups, key = { it.key }) { group -> GroupRow(group, state) { vm.openBookGroup(group) } }
            item { empty() }
        }
    }
}

@Composable
private fun GroupTile(group: BookGroup, state: PlexTouchUiState, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = stringResource(R.string.a11y_open), onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Artwork(group.cover, state.artworkConnection, Modifier.fillMaxWidth().aspectRatio(1f), book = true)
        Text(group.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
        Text(groupSubtitle(group), color = PlexMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupRow(group: BookGroup, state: PlexTouchUiState, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.a11y_open), onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(group.cover, state.artworkConnection, Modifier.size(58.dp), book = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(group.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(groupSubtitle(group), color = PlexMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = PlexMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun groupSubtitle(group: BookGroup): String {
    val count = pluralStringResource(R.plurals.collection_books, group.count, group.count)
    return if (group.smart) listOf(count, stringResource(R.string.collection_smart)).joinToString(stringResource(R.string.list_separator)) else count
}

@Composable
private fun BookGroupScreen(state: PlexTouchUiState, shelves: BookShelvesState, group: BookGroup, vm: PlexTouchViewModel) {
    val unknown = stringResource(R.string.unknown_author)
    val source = remember(group, shelves.books, state.albums, unknown) {
        if (group.kind == BookGroupKind.SERIES) seriesBooks(state.albums, group, unknown) else shelves.books
    }
    val server = state.connection?.serverUrl
    val rows = remember(source, state.history, state.downloads, server, state.accountScope, state.offlineOnly) {
        presentShelfBooks(source, state.history, state.downloads, server, state.accountScope, state.offlineOnly)
    }
    val unfinished = remember(source, state.history, state.downloads, server, state.accountScope, state.offlineOnly) {
        server?.let { unfinishedBooks(source, state.history, state.downloads, it, state.accountScope, state.offlineOnly).size } ?: 0
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::closeBookGroup) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.collection_back)) }
                    Eyebrow(stringResource(if (group.kind == BookGroupKind.SERIES) R.string.collection_series_by else if (group.smart) R.string.collection_smart else R.string.shelf_collections), PlexHighlight)
                }
                Text(group.title, Modifier.asHeading(), style = MaterialTheme.typography.headlineMedium)
                Text(pluralStringResource(R.plurals.collection_books, rows.size, rows.size), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
                if (state.mode == LibraryMode.AUDIOBOOK && rows.isNotEmpty()) Button(onClick = vm::playUnfinished, enabled = unfinished > 0 && state.playback.ready, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CircleShape) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text(if (unfinished > 0) pluralStringResource(R.plurals.collection_play_unfinished, unfinished, unfinished) else stringResource(R.string.collection_none_unfinished), fontWeight = FontWeight.Bold)
                }
            }
        }
        items(rows, key = { it.album.id }) { book -> ShelfBookRow(book, state, { vm.chooseAlbum(book.album) }) }
        if (rows.isEmpty() && (shelves.booksLoad != null || group.kind == BookGroupKind.SERIES) && !state.loading) item {
            val gone = shelves.booksLoad == ShelfLoad.GONE
            EmptyMessage(stringResource(if (gone) R.string.collection_gone_title else R.string.collection_empty_title), stringResource(if (gone) R.string.collection_gone_body else R.string.collection_empty_body))
        }
    }
}

@Composable
private fun ReadingListScreen(state: PlexTouchUiState, shelves: BookShelvesState, vm: PlexTouchViewModel) {
    val owner = state.shelfOwner
    LaunchedEffect(owner) { vm.loadReadingList() }
    val server = state.connection?.serverUrl
    val rows = remember(shelves.readingList, state.albums, state.history, state.downloads, server, state.accountScope, state.offlineOnly) {
        presentReadingList(shelves.readingList, state.albums, state.history, state.downloads, server, state.accountScope, state.offlineOnly)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.shelf_reading_list), Modifier.asHeading(), style = MaterialTheme.typography.headlineLarge)
                Text(listOf(pluralStringResource(R.plurals.reading_list_count, rows.size, rows.size), stringResource(R.string.reading_list_note)).joinToString(stringResource(R.string.list_separator)), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        itemsIndexed(rows, key = { _, book -> book.album.id }) { index, book ->
            // Ticket 140: the same three actions on the row's TalkBack actions menu as on its buttons.
            val up = RowAction(stringResource(R.string.reading_list_move_up), index > 0) { vm.moveInReadingList(book.album.id, -1) }
            val down = RowAction(stringResource(R.string.reading_list_move_down), index < rows.lastIndex) { vm.moveInReadingList(book.album.id, 1) }
            val remove = RowAction(stringResource(R.string.reading_list_remove_entry)) { vm.removeFromReadingList(book.album.id) }
            ShelfBookRow(book, state, { vm.chooseAlbum(book.album) }, Modifier.rowActions(up, down, remove)) {
                IconButton(onClick = up.run, enabled = up.enabled) { Icon(Icons.Rounded.KeyboardArrowUp, up.label) }
                IconButton(onClick = down.run, enabled = down.enabled) { Icon(Icons.Rounded.KeyboardArrowDown, down.label) }
                IconButton(onClick = remove.run) { Icon(Icons.Rounded.Close, remove.label, tint = PlexMuted) }
            }
        }
        if (rows.isEmpty()) item { EmptyMessage(stringResource(R.string.reading_list_empty_title), stringResource(R.string.reading_list_empty_body)) }
    }
}

/** A book in a collection, series or the reading list: progress or "finished", and a clear mark when it cannot play offline. */
@Composable
private fun ShelfBookRow(book: ShelfBook, state: PlexTouchUiState, onClick: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    // The title, author and progress are one TalkBack stop ("Open"); the buttons after it stay separate stops.
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.a11y_open), onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(book.album, state.artworkConnection, Modifier.size(58.dp), book = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.album.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = if (book.available) MaterialTheme.colorScheme.onSurface else PlexMuted)
            Text(albumArtist(book.album), color = PlexMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            val progress = book.progress
            when {
                !book.available -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.CloudOff, null, Modifier.size(14.dp), tint = PlexMuted)
                    Text(stringResource(R.string.shelf_unavailable_offline), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
                }
                book.finished -> Text(stringResource(R.string.progress_finished), color = PlexHighlight, style = MaterialTheme.typography.bodySmall)
                progress != null -> Text(stringResource(R.string.time_left, listeningTime(progress.durationMs - progress.elapsedMs).asString()), Modifier.spokenAs(stringResource(R.string.time_left, spokenMinutes(progress.durationMs - progress.elapsedMs).asString())), color = PlexHighlight, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
        actions()
    }
}

/** The book screen's reading-list button; the list loads for the account, server and library now on screen. */
@Composable
internal fun ReadingListToggle(state: PlexTouchUiState, vm: PlexTouchViewModel, album: PlexAlbum) {
    val owner = state.shelfOwner
    LaunchedEffect(owner) { vm.loadReadingList() }
    if (owner == null || album.id.startsWith("playlist:")) return
    val listed = inReadingList(state.currentShelves.readingList, album.id)
    // The label itself is the state ("On reading list" / "Add to reading list"), so no separate state description.
    TextButton(onClick = { vm.toggleReadingList(album) }) {
        Icon(if (listed) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd, null, Modifier.size(20.dp), tint = if (listed) PlexHighlight else PlexMuted)
        Spacer(Modifier.width(8.dp)); Text(stringResource(if (listed) R.string.reading_list_remove else R.string.reading_list_add), color = PlexMuted)
    }
}
