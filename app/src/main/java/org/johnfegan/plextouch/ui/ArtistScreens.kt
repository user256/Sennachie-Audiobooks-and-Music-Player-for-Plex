package org.johnfegan.plextouch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import org.johnfegan.plextouch.R

@Composable
internal fun ArtistsScreen(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    var query by rememberSaveable(state.connection?.serverUrl, state.selectedLibraryId) { mutableStateOf("") }
    val artistList = rememberLazyListState()
    val albumList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The grouping and A–Z positions come ready from the ViewModel's cache; composition only filters by the search words.
    val index = state.artistIndex?.takeIf { it.isFor(state) }
    val preparing = index == null && state.albums.isNotEmpty()
    val everyone = index?.artists.orEmpty()
    val artists = remember(everyone, query) { filterArtists(everyone, query) }
    val selected = remember(everyone, state.selectedArtist) { everyone.firstOrNull { it.key == state.selectedArtist } }
    val positions = remember(index, artists) { if (index != null && artists === index.artists) index.positions else artistLetterPositions(artists) }
    val artistListOffset = 1 + if (state.libraryError != null) 1 else 0
    // The rail only needs the letter on screen; deriving it means scrolling within one letter never recomposes the rail.
    val activeLetter by remember(positions, artistListOffset) {
        derivedStateOf { val first = artistList.firstVisibleItemIndex - artistListOffset; positions.filterValues { it <= first }.maxByOrNull { it.value }?.key }
    }
    LaunchedEffect(query, state.selectedLibraryId, state.connection?.serverUrl, state.offlineOnly) { artistList.scrollToItem(0) }
    LaunchedEffect(state.selectedArtist) { albumList.scrollToItem(0) }
    Column {
    Box(Modifier.weight(1f)) {
    LazyColumn(state = if (state.selectedArtist == null) artistList else albumList, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(end = if (state.selectedArtist == null) RAIL_WIDTH else 0.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.selectedArtist != null) IconButton(onClick = { vm.chooseArtist(null) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.artists_back)) }
                    Text(if (state.selectedArtist == null) stringResource(R.string.artists_title) else selected?.name ?: stringResource(R.string.artist_fallback), Modifier.weight(1f).asHeading(), style = MaterialTheme.typography.headlineMedium)
                    IconButton(onClick = vm::refresh, enabled = !state.offlineOnly) { Icon(Icons.Rounded.Refresh, stringResource(R.string.artists_refresh), tint = PlexMuted) }
                }
                val albumCount = selected?.albums?.size ?: 0
                Text(if (state.selectedArtist == null) pluralStringResource(R.plurals.artists_summary, artists.size, artists.size, stringResource(if (state.offlineOnly) R.string.artists_sub_downloaded else R.string.collection_sub_plex))
                    else pluralStringResource(if (state.offlineOnly) R.plurals.artist_albums_downloaded else R.plurals.artist_albums, albumCount, albumCount), color = PlexMuted)
                if (state.selectedArtist == null) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(R.string.artists_search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.artists_clear_search)) } })
            }
        }
        state.libraryError?.let { error -> item { InlineNotice(error.asString(), { vm.dismissMessage(Feature.LIBRARY) }, vm::refresh) } }
        if (state.selectedArtist != null) {
            items(selected?.albums.orEmpty(), key = { it.id }) { album -> AlbumListRow(album, state) { vm.chooseAlbum(album) } }
            if (selected == null && !state.loading && !preparing) item { EmptyMessage(stringResource(R.string.artist_no_albums), stringResource(if (state.offlineOnly) R.string.artist_no_downloads else R.string.artist_no_albums_body)) }
        } else {
            items(artists, key = { it.key }) { artist ->
                val cover = artist.albums.firstOrNull { it.localThumb != null || it.thumb != null } ?: artist.albums.first()
                ListItem(
                    headlineContent = { Text(artist.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(pluralStringResource(R.plurals.artist_albums, artist.albums.size, artist.albums.size)) },
                    leadingContent = { Artwork(cover, state.artworkConnection, Modifier.size(60.dp).clip(CircleShape)) },
                    trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = PlexMuted) },
                    modifier = Modifier.clickable(onClickLabel = stringResource(R.string.a11y_open)) { vm.chooseArtist(artist.key) },
                    colors = ListItemDefaults.colors(containerColor = PlexBackground),
                )
            }
            if (artists.isEmpty() && !state.loading && !preparing) item { EmptyMessage(stringResource(if (query.isNotBlank()) R.string.artists_no_match else R.string.artists_none),
                stringResource(if (query.isNotBlank()) R.string.artists_try_another else if (state.offlineOnly) R.string.artists_download_offline else R.string.artists_choose_library)) }
        }
    }
    if (state.selectedArtist == null && artists.isNotEmpty()) Box(Modifier.fillMaxHeight().align(Alignment.CenterEnd)) {
        ArtistLetterRail(positions, activeLetter) { letter ->
            positions[letter]?.let { index -> scope.launch { artistList.scrollToItem(index + artistListOffset) } }
        }
    }
    }
    }
}

/** Ticket 140: wide enough for a 48 dp touch column. */
private val RAIL_WIDTH = 48.dp

/**
 * The A–Z rail. For touch, each letter is a tap target and the whole rail a drag strip. For TalkBack it is one adjustable
 * control ("Jump to letter, M"): swiping up or down moves to the next letter that has artists ([LetterRail.step]) and
 * scrolls there, instead of 27 tiny focus stops.
 */
@Composable
private fun ArtistLetterRail(positions: Map<String, Int>, active: String?, jumpTo: (String) -> Unit) {
    val rail = remember(positions) { LetterRail.of(positions) }
    val letters = rail.letters
    var height by remember { mutableIntStateOf(1) }
    fun jumpAt(y: Float) = letters[(y / (height.toFloat() / letters.size)).toInt().coerceIn(0, letters.lastIndex)].let(jumpTo)
    val label = stringResource(R.string.a11y_letter_rail)
    val symbols = stringResource(R.string.a11y_letter_symbols)
    val current = rail.indexOf(active)
    val currentLetter = letters[current]
    Column(
        Modifier.fillMaxHeight().width(RAIL_WIDTH).padding(top = 12.dp, bottom = 12.dp, end = 3.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(letters, height) { detectVerticalDragGestures(onDragStart = { jumpAt(it.y) }, onVerticalDrag = { change, _ -> jumpAt(change.position.y) }) }
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = if (currentLetter == "#") symbols else currentLetter
                progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), 0f..letters.lastIndex.toFloat(), steps = letters.size - 2)
                setProgress { requested -> rail.step(current, requested)?.let { jumpTo(it); true } ?: false }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val style = MaterialTheme.typography.labelSmall.copy(lineHeight = TextUnit.Unspecified, textAlign = TextAlign.Center)
        letters.forEach { letter ->
            val available = letter in positions
            // Grows with the font size up to what one 27th of the rail can hold, so large text never overlaps letters.
            BasicText(letter, Modifier.weight(1f).fillMaxWidth().clip(CircleShape).clickable(enabled = available) { jumpTo(letter) }.wrapContentHeight(Alignment.CenterVertically),
                style = style.copy(color = when { !available -> PlexMuted.copy(alpha = .25f); letter == active -> PlexHighlight; else -> PlexMuted }),
                maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = style.fontSize))
        }
    }
}
