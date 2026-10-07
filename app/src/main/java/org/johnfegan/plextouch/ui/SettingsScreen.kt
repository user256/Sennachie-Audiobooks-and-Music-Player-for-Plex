package org.johnfegan.plextouch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.johnfegan.plextouch.BuildConfig
import org.johnfegan.plextouch.SetupScreen
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.player.RewindPolicy
import androidx.compose.ui.res.stringResource
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.player.effectsNotice

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    var connection by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    if (connection) { SetupScreen(state, vm, manageConnection = true); return }
    if (storage) { StorageScreen(state.downloads, { storage = false }) { storage = false; vm.openSettings(false); vm.chooseTab(LibraryTab.DOWNLOADS) }; return }
    if (history) {
        HistoryScreen(state.listeningHistory, state.historyNotice, state.playback.album?.id, vm::loadListeningHistory, { history = false }, vm::resetBookProgress, vm::clearListeningHistory, vm::dismissHistoryNotice)
        return
    }
    if (sound) SoundSheet(state.audioEffects, effectsNotice(state.playback.unsupportedEffects), vm::updateAudioEffects) { sound = false }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = { Text(stringResource(R.string.sign_out_body)) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.keep_signed_in)) } },
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title), Modifier.asHeading()) }, navigationIcon = { IconButton(onClick = { vm.openSettings(false) }) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.action_back)) } }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item {
                Eyebrow(stringResource(R.string.settings_eyebrow))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_library_prefs), Modifier.asHeading(), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_library_intro), color = PlexMuted)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(LibraryMode.MUSIC, LibraryMode.AUDIOBOOK).forEach { mode -> FilterChip(selected = state.mode == mode, onClick = { vm.chooseMode(mode) }, label = { Text(stringResource(mode.label)) }) }
                }
                Spacer(Modifier.height(12.dp))
                Surface(color = PlexPanel, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        // Each library is one radio row ("Audiobooks, selected, radio button"), not a radio and a button.
                        Column(Modifier.selectableGroup()) {
                            state.sections.forEach { section ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = section.id == state.selectedLibraryId, role = Role.RadioButton) { vm.chooseLibrary(section) },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = section.id == state.selectedLibraryId, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                                    Text(section.title, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        if (state.sections.isEmpty()) TextButton(onClick = vm::refresh) { Text(stringResource(R.string.reload_libraries)) }
                    }
                }
            }
            item {
                Eyebrow(stringResource(R.string.settings_connection), heading = true)
                Spacer(Modifier.height(8.dp))
                ListItem(headlineContent = { Text(stringResource(R.string.plex_account_server)) }, supportingContent = { Text(stringResource(R.string.signin_saved)) }, leadingContent = { Icon(Icons.Rounded.Dns, null, tint = PlexHighlight) }, colors = ListItemDefaults.colors(containerColor = PlexPanel))
                TextButton(onClick = { connection = true }) { Text(stringResource(R.string.manage_connection), color = PlexHighlight) }
                TextButton(onClick = { confirmSignOut = true }) { Text(stringResource(R.string.sign_out_forget), color = MaterialTheme.colorScheme.error) }
            }
            // Ticket 136: Plex Home switching and the server's connection diagnostics.
            if (state.connection != null) {
                item { PlexHomeSection(state.home, vm) }
                item { ConnectionDiagnosticsSection(state.diagnostics, vm) }
            }
            item {
                Eyebrow(stringResource(R.string.settings_storage), heading = true)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.settings_storage_body), color = PlexMuted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { storage = true }) {
                    Icon(Icons.Rounded.Storage, null, tint = PlexHighlight); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_storage_open), color = PlexHighlight)
                }
            }
            item {
                Eyebrow(stringResource(R.string.settings_listening), heading = true)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.settings_default_speed), Modifier.asHeading(), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_default_speed_body), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEED_CHOICES.forEach { speed ->
                        val spoken = spokenSpeed(speed).asString()
                        FilterChip(selected = state.speedProfile.defaultSpeed == speed, onClick = { vm.defaultAudiobookSpeed(speed) }, label = { Text(speedLabel(speed), Modifier.semantics { contentDescription = spoken }) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.settings_smart_rewind), Modifier.asHeading(), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_smart_rewind_body), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RewindPolicy.CHOICES_SECONDS.forEach { seconds ->
                        FilterChip(selected = state.smartRewindSeconds == seconds, onClick = { vm.smartRewind(seconds) },
                            label = {
                                if (seconds == 0) Text(stringResource(R.string.smart_rewind_off))
                                else Text(stringResource(R.string.smart_rewind_seconds, seconds), Modifier.spokenAs(uiPlural(R.plurals.a11y_seconds, seconds).asString()))
                            })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.settings_listening_body), color = PlexMuted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { sound = true }) {
                    Icon(Icons.Rounded.Tune, null, tint = PlexHighlight); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (state.audioEffects.enabled) R.string.settings_sound_on else R.string.settings_sound_off), color = PlexHighlight)
                }
                TextButton(onClick = { history = true }) {
                    Icon(Icons.Rounded.History, null, tint = PlexHighlight); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_history), color = PlexHighlight)
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.settings_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            }
            state.libraryError?.let { error -> item { InlineNotice(error.asString(), { vm.dismissMessage(Feature.LIBRARY) }) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceSheet(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    LaunchedEffect(Unit) { vm.discoverSonos() }
    ModalBottomSheet(onDismissRequest = { vm.openDevices(false) }, containerColor = PlexPanel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.where_to_listen), Modifier.asHeading(), style = MaterialTheme.typography.headlineMedium)
            ListItem(headlineContent = { Text(stringResource(R.string.this_phone), color = PlexHighlight) }, supportingContent = { Text(stringResource(R.string.phone_controls)) }, leadingContent = { Icon(Icons.Rounded.Smartphone, null, tint = PlexHighlight) }, colors = ListItemDefaults.colors(containerColor = PlexPanel))
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sonos_speakers), Modifier.weight(1f).asHeading(), style = MaterialTheme.typography.titleMedium)
                if (state.discovering) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = PlexHighlight)
                else IconButton(onClick = { vm.discoverSonos() }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh_speakers)) }
            }
            Text(stringResource(R.string.sonos_intro), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            state.speakers.forEach { speaker ->
                OutlinedButton(onClick = { vm.chooseSpeaker(speaker); vm.pushToSonos() }, modifier = Modifier.fillMaxWidth(), enabled = state.playback.track != null || state.tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.Speaker, null); Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(R.string.play_on, speaker.name))
                        speaker.model?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PlexMuted) }
                    }
                }
            }
            if (!state.discovering && state.speakers.isEmpty()) Text(stringResource(R.string.no_speakers), color = PlexMuted, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = state.speakerAddress, onValueChange = vm::updateSpeakerAddress, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.speaker_ip)) }, placeholder = { Text(stringResource(R.string.speaker_ip_placeholder)) })
            Button(onClick = { vm.addSpeaker() }, enabled = state.speakerAddress.isNotBlank() && !state.connectingSpeaker) { Text(stringResource(if (state.connectingSpeaker) R.string.checking_speaker else R.string.add_speaker)) }
            Text(stringResource(R.string.speaker_note), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            state.speakerError?.let { Text(it.asString(), Modifier.politeLiveRegion(), style = MaterialTheme.typography.bodySmall, color = PlexMuted) }
        }
    }
}
