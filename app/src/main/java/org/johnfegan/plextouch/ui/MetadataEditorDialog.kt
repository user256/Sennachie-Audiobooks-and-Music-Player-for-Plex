package org.johnfegan.plextouch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.johnfegan.plextouch.R

@Composable
internal fun MetadataEditorDialog(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val editor = state.metadataEditor ?: return
    var title by remember(editor) { mutableStateOf(editor.album.title) }
    var artist by remember(editor) { mutableStateOf(editor.album.artist) }
    var year by remember(editor) { mutableStateOf(editor.album.year?.toString().orEmpty()) }
    val validYear = year.isBlank() || (year.toIntOrNull()?.let { it in 1000..9999 } == true)
    AlertDialog(
        onDismissRequest = vm::closeMetadataEditor,
        containerColor = PlexPanel,
        title = { Text(stringResource(R.string.edit_metadata)) },
        text = {
            Column {
                Text(stringResource(R.string.metadata_intro), color = PlexMuted, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.album_title)) }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.album_artist)) }, singleLine = true)
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.release_year)) }, supportingText = { if (!validYear) Text(stringResource(R.string.year_hint)) }, singleLine = true)
                state.metadataError?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
                if (state.metadataBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp), color = PlexBlue)
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveAlbumMetadata(title, artist, year) }, enabled = !state.metadataBusy && title.isNotBlank() && artist.isNotBlank() && validYear) { Text(stringResource(R.string.save_to_plex)) } },
        dismissButton = { TextButton(onClick = vm::closeMetadataEditor, enabled = !state.metadataBusy) { Text(stringResource(R.string.action_cancel)) } },
    )
}
