package org.johnfegan.plextouch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.johnfegan.plextouch.data.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import org.johnfegan.plextouch.R

@Composable
internal fun DownloadsScreen(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val downloads = state.downloads.filter { it.record.mode == state.mode && it.record.server == state.connection?.serverUrl }
    var removing by remember { mutableStateOf<DownloadStatus?>(null) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text(stringResource(R.string.downloads_title), Modifier.asHeading(), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            val ready = downloads.count { it.ready }
            Text(pluralStringResource(if (state.mode == LibraryMode.MUSIC) R.plurals.downloads_ready_albums else R.plurals.downloads_ready_audiobooks, ready, ready, downloadSize(downloads.sumOf { it.bytes }).asString()), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Surface(color = PlexPanel, shape = RoundedCornerShape(12.dp)) {
                // The whole card is the switch: one TalkBack stop, "Offline mode, on, switch", and a larger touch target.
                Row(Modifier.fillMaxWidth().toggleable(value = state.offlineOnly, role = Role.Switch, onValueChange = vm::setOfflineOnly).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Rounded.OfflineBolt, null, tint = PlexHighlight)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(R.string.offline_mode), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.offline_mode_body), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.offlineOnly, onCheckedChange = null)
                }
            }
        }
        item { Text(stringResource(R.string.downloads_wifi_note), color = PlexMuted, style = MaterialTheme.typography.bodySmall) }
        state.downloadError?.let { error -> item { InlineNotice(error.asString(), { vm.dismissMessage(Feature.DOWNLOAD) }) } }
        items(downloads, key = { it.record.album.id }) { download ->
            Surface(color = PlexPanel, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.a11y_open)) { vm.chooseAlbum(download.album) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Artwork(download.album, state.artworkConnection, Modifier.size(62.dp), state.mode == LibraryMode.AUDIOBOOK)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(download.album.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(download.label.asString(), Modifier.politeLiveRegion(), color = if (download.ready) PlexHighlight else PlexMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { removing = download }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.download_remove_of, download.album.title), tint = PlexMuted) }
                    }
                    if (!download.ready) DownloadBar(download, PlexBackground)
                }
            }
        }
        if (downloads.isEmpty()) item { EmptyMessage(stringResource(R.string.downloads_empty_title), stringResource(R.string.downloads_empty_body)) }
    }
    removing?.let { download -> RemoveDownloadDialog(download, { removing = null }) { vm.removeDownload(download.album.id); removing = null } }
}

@Composable
internal fun AlbumDownloadControl(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val album = state.selectedAlbum ?: return
    val download = state.downloads.firstOrNull { it.record.server == state.connection?.serverUrl && it.record.album.id == album.id }
    var removing by remember(album.id) { mutableStateOf(false) }
    val savingDescription = stringResource(R.string.download_saving_cancel)
    val savedState = downloadState(download).asString()
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { if (download != null && download.failed == 0) removing = true else vm.downloadSelectedAlbum() },
                enabled = !state.downloadBusy && state.tracks.isNotEmpty() && (!state.offlineOnly || download?.ready == true || download?.active == true),
                modifier = Modifier.size(48.dp).semantics { stateDescription = savedState }) {
                when {
                    state.downloadBusy || download?.active == true -> CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = savingDescription }, color = PlexBlue, strokeWidth = 2.dp)
                    else -> Icon(if (download?.ready == true) Icons.Rounded.DownloadForOffline else Icons.Rounded.Downloading,
                        stringResource(when { download?.ready == true -> R.string.download_saved_remove; download != null && download.failed > 0 -> R.string.download_retry; else -> R.string.download_save }),
                        Modifier.size(28.dp), tint = if (download?.ready == true) PlexBlue else PlexMuted)
                }
            }
            Spacer(Modifier.weight(1f))
        }
        if (download != null) Text(download.label.asString(), Modifier.politeLiveRegion(), color = if (download.ready) PlexHighlight else PlexMuted, style = MaterialTheme.typography.bodySmall)
        state.downloadError?.let { Text(it.asString(), Modifier.politeLiveRegion(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (download != null && !download.ready) DownloadBar(download, PlexPanel)
    }
    if (removing && download != null) RemoveDownloadDialog(download, { removing = false }) { vm.removeDownload(album.id); removing = false }
}

@Composable
private fun RemoveDownloadDialog(download: DownloadStatus, dismiss: () -> Unit, remove: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (download.ready) R.string.download_remove_title else R.string.download_cancel_title)) },
        text = { Text(stringResource(R.string.download_remove_body, download.album.title)) },
        confirmButton = { TextButton(onClick = remove) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.download_keep)) } })
}

/** A download's file progress; TalkBack reads "Download progress, saving offline, 3 of 12 files saved". */
@Composable
private fun DownloadBar(download: DownloadStatus, track: androidx.compose.ui.graphics.Color) {
    val fraction = download.completed.toFloat() / download.tracks.size.coerceAtLeast(1)
    val label = stringResource(R.string.a11y_download_progress)
    val state = downloadState(download).asString()
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp).semantics { contentDescription = label; stateDescription = state }, color = PlexBlue, trackColor = track)
}
