package org.johnfegan.plextouch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.johnfegan.plextouch.data.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import org.johnfegan.plextouch.R

@Composable
internal fun MusicCollection(state: PlexTouchUiState, vm: PlexTouchViewModel, albums: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(state.musicCollection == MusicCollectionTab.ALBUMS && state.personalShelf == PersonalShelf.NONE, { vm.showPlaylists(false) }, label = { Text(stringResource(R.string.tab_albums)) })
            FilterChip(state.musicCollection == MusicCollectionTab.ARTISTS, vm::showArtists, label = { Text(stringResource(R.string.tab_artists)) })
            FilterChip(state.personalShelf == PersonalShelf.FAVOURITES, { vm.openShelf(PersonalShelf.FAVOURITES) }, label = { Text(stringResource(R.string.tab_favourites)) })
            FilterChip(state.showPlaylists, { vm.showPlaylists(true) }, label = { Text(stringResource(R.string.tab_playlists)) })
        }
        Box(Modifier.weight(1f)) {
            if (state.musicCollection == MusicCollectionTab.ARTISTS) ArtistsScreen(state, vm)
            else if (!state.showPlaylists) albums() else {
                LaunchedEffect(state.connection?.serverUrl) { vm.showPlaylists(true) }
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.playlists_title), Modifier.weight(1f).asHeading(), style = MaterialTheme.typography.headlineMedium)
                            IconButton(onClick = vm::refresh, enabled = !state.offlineOnly) { Icon(Icons.Rounded.Refresh, stringResource(R.string.playlists_refresh)) }
                        }
                        Text(stringResource(R.string.playlists_intro), color = PlexMuted)
                        TextButton(onClick = { vm.showPlaylists(false) }) { Text(stringResource(R.string.playlists_add_music)) }
                    }
                    state.playlistError?.let { error -> item { InlineNotice(error.asString(), { vm.dismissMessage(Feature.PLAYLIST) }) } }
                    if (state.offlineOnly) item { Text(stringResource(R.string.playlists_offline), color = PlexMuted) }
                    items(state.playlists, key = { it.id }) { playlist ->
                        ListItem(
                            headlineContent = { Text(playlist.title) },
                            supportingContent = { Text(pluralStringResource(if (playlist.smart) R.plurals.playlist_tracks_smart else R.plurals.playlist_tracks, playlist.trackCount, playlist.trackCount)) },
                            leadingContent = { Artwork(playlist.album(), state.artworkConnection, Modifier.size(58.dp), false) },
                            trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                            modifier = Modifier.clickable(onClickLabel = stringResource(R.string.a11y_open)) { vm.choosePlaylist(playlist) },
                            colors = ListItemDefaults.colors(containerColor = PlexPanel),
                        )
                    }
                    if (!state.loading && state.playlists.isEmpty()) item { Text(stringResource(R.string.playlists_empty), color = PlexMuted) }
                }
            }
        }
    }
}

@Composable
internal fun AddToPlaylistDialog(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = vm::closeAddToPlaylist,
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text(pluralStringResource(R.plurals.selected_tracks, state.playlistAddTracks.size, state.playlistAddTracks.size), color = PlexMuted) }
                item { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.new_playlist_name)) }, enabled = !state.playlistBusy) }
                item { Button(onClick = { vm.saveToPlaylist(null, name) }, enabled = name.isNotBlank() && !state.playlistBusy) { Text(stringResource(R.string.create_playlist)) } }
                state.playlistError?.let { error -> item { Text(error.asString(), Modifier.politeLiveRegion(), color = PlexMuted) } }
                items(state.playlists.filterNot { it.smart }, key = { it.id }) { playlist ->
                    OutlinedButton(onClick = { vm.saveToPlaylist(playlist) }, enabled = !state.playlistBusy, modifier = Modifier.fillMaxWidth()) { Text(playlist.title) }
                }
                if (state.playlistBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = vm::closeAddToPlaylist, enabled = !state.playlistBusy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun PlaylistActions(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val playlist = state.selectedPlaylist ?: return
    var rename by remember(playlist.id) { mutableStateOf(false) }
    var delete by remember(playlist.id) { mutableStateOf(false) }
    var name by remember(playlist.id, playlist.title) { mutableStateOf(playlist.title) }
    Column(Modifier.padding(horizontal = 22.dp)) {
        Text(stringResource(if (playlist.smart) R.string.playlist_smart_note else R.string.playlist_edit_note), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { rename = true }, enabled = !state.offlineOnly && !state.playlistBusy) { Text(stringResource(R.string.rename_playlist)) }
        TextButton(onClick = { delete = true }, enabled = !state.offlineOnly && !state.playlistBusy) { Text(stringResource(R.string.delete_playlist)) }
    }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename_playlist)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.playlist_name)) }) },
        confirmButton = { TextButton(onClick = { vm.renamePlaylist(name); rename = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.delete_playlist_title)) },
        text = { Text(stringResource(R.string.delete_playlist_body, playlist.title)) },
        confirmButton = { TextButton(onClick = { vm.deletePlaylist(); delete = false }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.action_cancel)) } })
}

/**
 * A playlist entry with its move and remove buttons. Ticket 140: [row] is given the same three actions as TalkBack custom
 * actions, so they are on the track's own actions menu rather than three small buttons further on.
 */
@Composable
internal fun PlaylistEntryActions(state: PlexTouchUiState, vm: PlexTouchViewModel, track: PlexTrack, index: Int, row: @Composable (Modifier) -> Unit) {
    var remove by remember(track.playlistItemId) { mutableStateOf(false) }
    val editable = state.selectedPlaylist?.smart == false && !state.offlineOnly && !state.playlistBusy && track.playlistItemId != null
    val up = RowAction(stringResource(R.string.move_track_up, "${index + 1}"), editable && index > 0) { vm.movePlaylistTrack(index, -1) }
    val down = RowAction(stringResource(R.string.move_track_down, "${index + 1}"), editable && index < state.tracks.lastIndex) { vm.movePlaylistTrack(index, 1) }
    val delete = RowAction(stringResource(R.string.remove_track, "${index + 1}"), editable) { remove = true }
    row(Modifier.rowActions(up, down, delete))
    Row(Modifier.fillMaxWidth().padding(start = 44.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
        IconButton(onClick = up.run, enabled = up.enabled) { Icon(Icons.Rounded.ArrowUpward, up.label) }
        IconButton(onClick = down.run, enabled = down.enabled) { Icon(Icons.Rounded.ArrowDownward, down.label) }
        IconButton(onClick = delete.run, enabled = delete.enabled) { Icon(Icons.Rounded.RemoveCircleOutline, delete.label) }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text(stringResource(R.string.remove_entry_title)) },
        text = { Text(stringResource(R.string.remove_entry_body, track.title)) },
        confirmButton = { TextButton(onClick = { vm.removePlaylistTrack(track); remove = false }) { Text(stringResource(R.string.action_remove)) } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text(stringResource(R.string.action_cancel)) } })
}
