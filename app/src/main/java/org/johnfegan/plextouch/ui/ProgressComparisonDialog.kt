package org.johnfegan.plextouch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.johnfegan.plextouch.app.ProgressComparison
import org.johnfegan.plextouch.data.*
import androidx.compose.ui.res.stringResource
import org.johnfegan.plextouch.R

@Composable
internal fun ProgressComparisonDialog(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val comparison = state.progressComparison ?: return
    var confirmSend by remember(comparison) { mutableStateOf(false) }
    var selected by remember(comparison) { mutableStateOf<PlexChapterProgress?>(null) }
    val canAct = !state.progressBusy && !state.playback.playing && !state.playback.buffering && !state.offlineOnly
    // Plex keeps one viewOffset per file; for a chaptered single file the marker name is display-only beside that offset.
    val file = state.tracks.takeIf { state.selectedAlbum?.id == comparison.album.id && embeddedChapters(it).isNotEmpty() }?.single()
    val markerSuffix = stringResource(R.string.marker_suffix)
    val markerNames = file?.chapters.orEmpty().associateWith { chapterName(it).asString() }
    fun markerName(positionMs: Long) = file?.let { displayedChapter(it, 1, positionMs) }?.let { markerSuffix.format(markerNames[it] ?: it.title) }.orEmpty()
    AlertDialog(
        onDismissRequest = vm::closeProgressComparison,
        containerColor = PlexPanel,
        title = { Text(stringResource(if (confirmSend) R.string.send_phone_title else R.string.plex_progress)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(comparison.album.title, style = MaterialTheme.typography.titleMedium)
                comparison.phone?.let { phone ->
                    val index = comparison.chapters.indexOfFirst { it.id == phone.trackId }
                    Text(if (file != null) stringResource(R.string.this_phone_file, playbackTime(phone.positionMs), markerName(phone.positionMs)) else stringResource(R.string.this_phone_chapter, index + 1, playbackTime(phone.positionMs), markerName(phone.positionMs)), color = PlexHighlight)
                } ?: Text(comparison.phoneNotice?.asString().orEmpty(), color = PlexMuted)
                if (confirmSend) {
                    val remote = comparison.chapters.firstOrNull { it.id == comparison.phone?.trackId }
                    Text(stringResource(R.string.replace_position, playbackTime(remote?.positionMs ?: 0)))
                } else {
                    Text(stringResource(if (file != null) R.string.choose_file_position else R.string.choose_chapter_position), color = PlexMuted)
                    if (file != null) Text(stringResource(R.string.markers_note), style = MaterialTheme.typography.bodySmall, color = PlexMuted)
                    comparison.chapters.forEachIndexed { index, chapter ->
                        if (chapter.resumable) {
                            OutlinedButton(onClick = { selected = chapter }, enabled = canAct, modifier = Modifier.fillMaxWidth()) {
                                val option = if (file != null) stringResource(R.string.comparison_plex_option, playbackTime(chapter.positionMs), markerName(chapter.positionMs), chapter.title)
                                    else stringResource(R.string.comparison_chapter_option, index + 1, playbackTime(chapter.positionMs), markerName(chapter.positionMs), chapter.title)
                                Text(if (selected == chapter) stringResource(R.string.selected_option, option) else option)
                            }
                        }
                    }
                    if (comparison.chapters.none { it.resumable }) Text(stringResource(R.string.no_resumable))
                    Text(stringResource(R.string.store_progress_note), style = MaterialTheme.typography.bodySmall, color = PlexMuted)
                    if (comparison.phone != null) TextButton(onClick = { confirmSend = true }, enabled = canAct) { Text(stringResource(R.string.send_to_plex)) }
                }
                if (state.progressBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PlexBlue)
                state.progressNotice?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                if (state.playback.playing || state.playback.buffering) Text(stringResource(R.string.pause_before_exchange))
            }
        },
        confirmButton = {
            if (confirmSend) TextButton(onClick = { vm.sendPhoneProgress() }, enabled = canAct) { Text(stringResource(R.string.send_position)) }
            else TextButton(onClick = { selected?.let(vm::resumePlexProgress) }, enabled = canAct && selected != null) { Text(stringResource(R.string.resume_selected)) }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmSend) confirmSend = false else vm.closeProgressComparison() }, enabled = !state.progressBusy) { Text(stringResource(if (confirmSend) R.string.action_back else R.string.action_close)) }
        },
    )
}
