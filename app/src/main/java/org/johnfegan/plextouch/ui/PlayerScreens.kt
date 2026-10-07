package org.johnfegan.plextouch.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.johnfegan.plextouch.data.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.player.effectsNotice

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlbumScreen(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val album = state.selectedAlbum ?: return
    val book = state.mode == LibraryMode.AUDIOBOOK
    val playlist = album.id.startsWith("playlist:")
    val progress = state.history.firstOrNull { it.album.id == album.id && it.server == state.connection?.serverUrl && it.belongsToScope(state.accountScope) && !it.finished }
    val playingAlbum = state.playback.album?.id == album.id
    val playerPosition = vm.position.collectAsStateWithLifecycle()
    // Only the active-row flag depends on the player offset; deriving the rows means a tick recomposes nothing unless the chapter changes.
    val chapters by remember(state.tracks, playingAlbum, state.playback.trackIndex, book) {
        derivedStateOf { if (book) bookChapters(state.tracks, if (playingAlbum) state.playback.trackIndex else -1, if (playingAlbum) playerPosition.value.positionMs else 0) else emptyList() }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF103E57), PlexBackground))).padding(bottom = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::closeAlbum) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.album_back)) }
                    Spacer(Modifier.weight(1f)); Eyebrow(stringResource(if (book) R.string.eyebrow_audiobook else if (playlist) R.string.eyebrow_playlist else R.string.eyebrow_album), PlexHighlight); Spacer(Modifier.weight(1f))
                    if (!playlist && !state.offlineOnly) IconButton(onClick = vm::openMetadataEditor) { Icon(Icons.Rounded.Edit, stringResource(R.string.edit_metadata)) }
                    IconButton(onClick = { vm.openDevices(true) }) { Icon(Icons.Rounded.SpeakerGroup, stringResource(R.string.choose_device)) }
                }
                Artwork(album, state.artworkConnection, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 24.dp).widthIn(max = 280.dp).fillMaxWidth(.68f).aspectRatio(1f), book, labelled = true)
                Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(album.title, Modifier.asHeading(), style = MaterialTheme.typography.headlineMedium)
                    Text(albumArtist(album), color = if (book) PlexHighlight else Color.White, style = MaterialTheme.typography.titleMedium)
                    val kind = stringResource(if (book) R.string.kind_audiobook else if (playlist) R.string.kind_playlist else R.string.kind_album)
                    val length = state.tracks.takeIf { it.isNotEmpty() }?.let {
                        val count = if (book) chapterCount(it) else it.size
                        pluralStringResource(if (book) R.plurals.chapters_duration else R.plurals.tracks_duration, count, count, listeningTime(it.sumOf { track -> track.durationMs }).asString())
                    }
                    Text(listOfNotNull(kind, album.year?.toString(), length).joinToString(stringResource(R.string.list_separator)), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
                    if (book && progress != null) {
                        Spacer(Modifier.height(4.dp))
                        // One TalkBack stop: "Listening progress, 7h 3m left · Chapter 4" with the bar's percentage.
                        val timeLeft = stringResource(R.string.time_left_chapter, listeningTime(progress.durationMs - progress.elapsedMs).asString(), chapterNumber(state.tracks, progress.trackIndex, progress.positionMs))
                        val label = stringResource(R.string.a11y_listening_progress)
                        val spokenLeft = stringResource(R.string.time_left_chapter, spokenMinutes(progress.durationMs - progress.elapsedMs).asString(), chapterNumber(state.tracks, progress.trackIndex, progress.positionMs))
                        Column(Modifier.clearAndSetSemantics { contentDescription = label; stateDescription = spokenLeft; progressBarRangeInfo = ProgressBarRangeInfo(progress.fraction, 0f..1f) }, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            LinearProgressIndicator(progress = { progress.fraction }, Modifier.fillMaxWidth().height(4.dp), color = PlexBlue, trackColor = PlexPanel)
                            Text(timeLeft, color = PlexMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (book) {
                        Button(onClick = { vm.play() }, enabled = state.tracks.isNotEmpty() && state.playback.ready, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp), shape = CircleShape) {
                            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(stringResource(if (playingAlbum) R.string.action_open_player else if (progress != null) R.string.action_continue_listening else R.string.action_start_listening), fontWeight = FontWeight.Bold)
                        }
                        // Wraps at large font sizes rather than squeezing either button.
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            val infiniteState = onOffState(playingAlbum && state.playback.repeat).asString()
                            TextButton(onClick = { if (playingAlbum) vm.toggleRepeat() else vm.play(infinite = true) }, enabled = state.tracks.isNotEmpty() && state.playback.ready,
                                modifier = if (playingAlbum) Modifier.semantics { stateDescription = infiniteState } else Modifier) {
                                Icon(Icons.Rounded.AllInclusive, null, Modifier.size(20.dp), tint = if (playingAlbum && state.playback.repeat) PlexHighlight else PlexMuted)
                                Spacer(Modifier.width(8.dp)); Text(stringResource(if (playingAlbum && state.playback.repeat) R.string.infinite_on else R.string.infinite_album), color = PlexMuted)
                            }
                            ReadingListToggle(state, vm, album)
                        }
                    } else Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val saved = state.isFavourite(album)
                        val savedState = favouriteState(saved).asString()
                        if (!playlist) IconButton(onClick = { vm.toggleAlbumFavourite() }, enabled = !state.offlineOnly && !state.ratingBusy, modifier = Modifier.semantics { stateDescription = savedState }) {
                            Icon(if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                stringResource(if (saved) R.string.favourite_clear else R.string.favourite_rate), tint = if (saved) PlexBlue else PlexMuted)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { vm.play(shuffled = true) }, enabled = state.tracks.isNotEmpty() && state.playback.ready) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle_album), tint = PlexMuted) }
                        Spacer(Modifier.width(10.dp))
                        val repeatingAlbum = playingAlbum && state.playback.repeat && !state.playback.repeatOne
                        val repeatingAlbumState = onOffState(repeatingAlbum).asString()
                        IconButton(onClick = { if (playingAlbum) vm.toggleAlbumRepeat() else vm.play(infinite = true) }, enabled = state.tracks.isNotEmpty() && state.playback.ready,
                            modifier = Modifier.semantics { stateDescription = repeatingAlbumState }) {
                            Icon(Icons.Rounded.Repeat, stringResource(R.string.repeat_album), tint = if (repeatingAlbum) PlexHighlight else PlexMuted)
                        }
                        Spacer(Modifier.width(10.dp))
                        PlayButton(playing = playingAlbum && state.playback.playing, enabled = state.tracks.isNotEmpty() && state.playback.ready) {
                            if (playingAlbum && state.playback.playing) vm.togglePlayback() else vm.play()
                        }
                    }
                }
            }
        }
        if (!playlist) item { AlbumDownloadControl(state, vm) }
        if (book) item {
            Column(Modifier.padding(horizontal = 22.dp)) {
                TextButton(onClick = { vm.comparePlexProgress() }, enabled = !state.progressBusy && !state.offlineOnly && !state.playback.playing && !state.playback.buffering) {
                    Icon(Icons.Rounded.Sync, null); Spacer(Modifier.width(8.dp)); Text(stringResource(if (state.progressBusy) R.string.compare_busy else if (state.progressSyncConflict?.albumId == album.id) R.string.compare_review else R.string.compare_with_plex))
                }
                if (state.progressSyncConflict?.albumId == album.id) Text(
                    stringResource(R.string.compare_conflict), Modifier.politeLiveRegion(),
                    color = PlexHighlight, style = MaterialTheme.typography.bodySmall)
                if (state.offlineOnly || state.playback.playing || state.playback.buffering) Text(
                    stringResource(if (state.offlineOnly) R.string.compare_offline else R.string.compare_pause),
                    color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (playlist) item { PlaylistActions(state, vm) }
        if (!book && !playlist) item {
            TextButton(onClick = { vm.openAddToPlaylist(state.tracks) }, enabled = state.tracks.isNotEmpty() && !state.offlineOnly, modifier = Modifier.padding(horizontal = 22.dp)) {
                Icon(Icons.Rounded.PlaylistAdd, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_album_to_playlist))
            }
        }
        state.albumError?.let { error -> item { InlineNotice(error.asString(), { vm.dismissMessage(Feature.ALBUM) }) } }
        if (state.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), color = PlexHighlight, strokeWidth = 2.dp) } }
        item {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow(stringResource(if (book) R.string.eyebrow_chapters else R.string.eyebrow_tracks), heading = true)
                if (book) Text(stringResource(R.string.tap_chapter), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (book) {
            itemsIndexed(chapters, key = { index, entry -> "${entry.trackIndex}:${entry.offsetMs}:$index" }) { _, entry ->
                ChapterRow(entry) { vm.play(index = entry.trackIndex, offsetMs = entry.offsetMs) }
            }
        } else itemsIndexed(state.tracks, key = { index, track -> "${track.id}:$index" }) { index, track ->
            val active = playingAlbum && state.playback.trackIndex == index
            if (playlist && state.selectedPlaylist?.smart == false) PlaylistEntryActions(state, vm, track, index) { actions -> TrackRow(track, index, active, actions) { vm.play(index = index) } }
            else if (playlist) TrackRow(track, index, active) { vm.play(index = index) }
            else Row(verticalAlignment = Alignment.CenterVertically) {
                // The row's TalkBack actions menu offers the same "Add track to playlist" as the button beside it.
                val addLabel = stringResource(R.string.add_track_to_playlist, "${index + 1}")
                val add = { vm.openAddToPlaylist(listOf(track)) }
                Box(Modifier.weight(1f)) { TrackRow(track, index, active, Modifier.rowActions(RowAction(addLabel, !state.offlineOnly, add))) { vm.play(index = index) } }
                IconButton(onClick = add, enabled = !state.offlineOnly) { Icon(Icons.Rounded.PlaylistAdd, addLabel) }
            }
        }
        if (!state.loading && state.tracks.isEmpty()) item { EmptyMessage(stringResource(R.string.no_tracks_title), stringResource(R.string.no_tracks_body), stringResource(R.string.action_retry)) { vm.chooseAlbum(album) } }
    }
}

@Composable
internal fun LastPlayedBar(progress: ListeningProgress, state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val download = state.downloads.firstOrNull { it.record.album.id == progress.album.id && it.record.server == progress.server }
    val album = download?.album ?: progress.album
    // Saved history holds only the file offset; the marker name needs the track list, known for downloads and the open album.
    val tracks = download?.tracks ?: state.tracks.takeIf { state.selectedAlbum?.id == progress.album.id }.orEmpty()
    Row(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF163348)).fillMaxWidth().heightIn(min = 66.dp)
        .clickable(onClickLabel = stringResource(R.string.a11y_open)) { vm.chooseAlbum(album) }.padding(start = 9.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Artwork(album, state.artworkConnection, Modifier.size(46.dp), state.mode == LibraryMode.AUDIOBOOK)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(album.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            val book = state.mode == LibraryMode.AUDIOBOOK
            val chapter = if (book) chapterNumber(tracks, progress.trackIndex, progress.positionMs) else 0
            // Shown as "12:03"; read as "12 minutes 3 seconds".
            val spoken = spokenDuration(progress.positionMs).asString()
            val spokenLine = if (book) stringResource(R.string.last_played_chapter, chapter, spoken) else stringResource(R.string.last_played_track, progress.trackIndex + 1, spoken)
            Text(if (book) stringResource(R.string.last_played_chapter, chapter, playbackTime(progress.positionMs)) else stringResource(R.string.last_played_track, progress.trackIndex + 1, playbackTime(progress.positionMs)),
                Modifier.semantics { contentDescription = spokenLine }, color = Color(0xFFB5CDDC), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { vm.resumeLastPlayed(progress) }, enabled = state.playback.ready && !state.loading, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.PlayArrow, stringResource(if (state.mode == LibraryMode.MUSIC) R.string.resume_last_music else R.string.resume_last_audiobook), Modifier.size(30.dp))
        }
    }
}

@Composable
internal fun MiniPlayer(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val p = state.playback
    val album = p.album ?: return
    val position by vm.position.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF163348))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClickLabel = stringResource(R.string.a11y_open_player)) { vm.openPlayer(true) }.padding(start = 9.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Artwork(album, state.artworkConnection, Modifier.size(46.dp), p.mode == LibraryMode.AUDIOBOOK)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (p.mode == LibraryMode.AUDIOBOOK) album.title else p.track?.title ?: album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(if (p.buffering) stringResource(R.string.connecting) else if (p.mode == LibraryMode.AUDIOBOOK) stringResource(R.string.chapter_with_artist, chapterNumber(p.track, p.trackCount, p.trackIndex, position.positionMs), albumArtist(album)) else p.track?.artist ?: albumArtist(album), color = Color(0xFFB5CDDC), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
            }
            IconButton(onClick = vm::togglePlayback, modifier = Modifier.size(48.dp)) { Icon(if (p.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (p.playing) R.string.action_pause else R.string.action_resume), Modifier.size(30.dp)) }
        }
        // A 2 dp hint for sighted users; the player's seek slider is the accessible position, so TalkBack skips this one.
        LinearProgressIndicator(progress = { if (position.durationMs > 0) (position.positionMs.toFloat() / position.durationMs).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp).clearAndSetSemantics {}, color = PlexHighlight, trackColor = Color.White.copy(alpha = .08f))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlayerScreen(state: PlexTouchUiState, vm: PlexTouchViewModel) {
    val p = state.playback
    val album = p.album ?: return
    val position by vm.position.collectAsStateWithLifecycle()
    val book = p.mode == LibraryMode.AUDIOBOOK
    var chapters by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var timerMenu by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(false) }
    var drag by remember(album.id, p.trackIndex) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF103E57), PlexBackground), endY = 1300f)).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openPlayer(false) }) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.minimise_player), Modifier.size(30.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Eyebrow(stringResource(if (book) R.string.eyebrow_listening_book else R.string.eyebrow_playing_library), PlexHighlight)
                Text(if (book) albumArtist(album) else album.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { chapters = true }) { Icon(Icons.Rounded.QueueMusic, stringResource(if (book) R.string.open_chapters else R.string.open_queue)) }
        }
        Artwork(album, state.artworkConnection, Modifier.padding(top = 16.dp, bottom = 28.dp).widthIn(max = 350.dp).fillMaxWidth(.82f).aspectRatio(1f), book, labelled = true)
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            Text(if (book) album.title else p.track?.title ?: album.title, Modifier.asHeading(), style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(7.dp))
            Text(if (book) (chapterTitle(p.track, p.trackCount, position.positionMs) ?: uiText(R.string.chapter_number_title, p.trackIndex + 1)).asString() else p.track?.artist ?: albumArtist(album), color = PlexMuted, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.error != null) Text(p.error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp).politeLiveRegion())
            val shownMs = drag?.toLong() ?: position.positionMs
            // Ticket 140: "Playback position, 12 minutes 3 seconds of 45 minutes". TalkBack's adjust gestures move 30 seconds
            // (accessibleSeekTarget); the buffered fill is drawn only, never announced as progress.
            val seekLabel = stringResource(R.string.a11y_seek)
            val seekState = spokenPosition(shownMs, position.durationMs).asString()
            Slider(value = drag ?: position.positionMs.toFloat().coerceIn(0f, position.durationMs.toFloat().coerceAtLeast(1f)), onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { vm.seek(it.toLong()) }; drag = null }, valueRange = 0f..position.durationMs.toFloat().coerceAtLeast(1f), enabled = position.durationMs > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics {
                    contentDescription = seekLabel
                    stateDescription = seekState
                    if (position.durationMs > 0) setProgress { requested -> vm.seek(accessibleSeekTarget(position.positionMs, requested, position.durationMs)); true }
                },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = PlexBlue, inactiveTrackColor = Color(0xFF314452)),
                // Played follows the thumb (including a drag); buffered is Media3's buffered position in the same item.
                track = { slider -> SeekTrack(seekSegments(slider.value.toLong(), position.bufferedMs, position.durationMs)) })
            // The slider already speaks both times; these are for sight only, so they do not tick under TalkBack.
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(playbackTime(shownMs), color = PlexMuted, fontSize = 11.sp)
                Text(stringResource(R.string.time_remaining, playbackTime((position.durationMs - shownMs).coerceAtLeast(0))), color = PlexMuted, fontSize = 11.sp)
            }
            // On its own line so the three labels never overlap at large font sizes.
            if (p.buffering) Text(stringResource(R.string.buffering), Modifier.fillMaxWidth().politeLiveRegion(), color = PlexMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
            else if (book) Text(stringResource(R.string.chapter_of, chapterNumber(p.track, p.trackCount, p.trackIndex, shownMs), chapterCount(p.track, p.trackCount)), Modifier.fillMaxWidth(), color = PlexMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
            // The slider scrubs the current file (precise even in a 20-hour book); this bar places that file in the whole book.
            if (book) bookProgress(state.queue, p.trackIndex, p.track?.id, shownMs)?.let { whole ->
                val bookLabel = stringResource(R.string.a11y_book_progress)
                val bookState = spokenBookPosition(whole).asString()
                Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = bookLabel; stateDescription = bookState; progressBarRangeInfo = ProgressBarRangeInfo(whole.fraction, 0f..1f) }) {
                    LinearProgressIndicator(progress = { whole.fraction }, Modifier.fillMaxWidth().padding(top = 12.dp).height(3.dp), color = PlexHighlight, trackColor = Color(0xFF314452))
                    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.book_position, playbackTime(whole.elapsedMs), playbackTime(whole.totalMs)), color = PlexMuted, fontSize = 11.sp)
                        Text(stringResource(R.string.book_time_left, listeningTime(whole.remainingMs).asString()), color = PlexMuted, fontSize = 11.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val shuffleState = onOffState(p.shuffle).asString()
                val repeatState = stringResource(when {
                    p.repeatOne -> R.string.repeat_track
                    p.repeat -> R.string.repeat_album
                    else -> R.string.repeat_off
                })
                if (!book) IconButton(onClick = vm::toggleShuffle, modifier = Modifier.semantics { stateDescription = shuffleState }) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.toggle_shuffle), tint = if (p.shuffle) PlexHighlight else PlexMuted) }
                IconButton(onClick = { if (book) vm.skip(-30_000) else vm.previous() }, modifier = Modifier.size(52.dp)) { Icon(if (book) Icons.Rounded.Replay30 else Icons.Rounded.SkipPrevious, stringResource(if (book) R.string.back_30 else R.string.previous_track), Modifier.size(36.dp)) }
                PlayButton(p.playing, size = 76) { vm.togglePlayback() }
                IconButton(onClick = { if (book) vm.skip(30_000) else vm.next() }, modifier = Modifier.size(52.dp)) { Icon(if (book) Icons.Rounded.Forward30 else Icons.Rounded.SkipNext, stringResource(if (book) R.string.forward_30 else R.string.next_track), Modifier.size(36.dp)) }
                if (!book) IconButton(onClick = vm::cycleMusicRepeat, modifier = Modifier.semantics { stateDescription = repeatState }) {
                    Icon(if (p.repeatOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, stringResource(R.string.toggle_repeat), tint = if (p.repeat) PlexHighlight else PlexMuted)
                }
            }
            // Wraps onto a second line at large font sizes instead of squeezing the labels.
            if (book) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Box {
                    PlayerTool(Icons.Rounded.Speed, speedLabel(p.speed), stringResource(R.string.playback_speed), spokenSpeed(p.speed).asString(), state.speedProfile.overridden) { speedMenu = true }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        SPEED_CHOICES.forEach { speed ->
                            val spoken = spokenSpeed(speed).asString()
                            DropdownMenuItem(text = { Text(speedLabel(speed), Modifier.semantics { contentDescription = spoken }) }, onClick = { vm.speed(speed); speedMenu = false })
                        }
                    }
                }
                PlayerTool(Icons.Rounded.FormatListNumbered, stringResource(R.string.chapters), stringResource(R.string.chapters)) { chapters = true }
                Box {
                    val remaining = ((p.sleepEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 60_000 + 1)
                    PlayerTool(Icons.Rounded.Bedtime, if (p.sleepEndsAt > 0) stringResource(R.string.duration_minutes, remaining) else stringResource(R.string.sleep), stringResource(R.string.sleep_timer),
                        sleepState(remaining.takeIf { p.sleepEndsAt > 0 }).asString(), p.sleepEndsAt > 0) { timerMenu = true }
                    DropdownMenu(expanded = timerMenu, onDismissRequest = { timerMenu = false }) { listOf(0, 15, 30, 45, 60).forEach { minutes -> DropdownMenuItem(text = { Text(if (minutes == 0) stringResource(R.string.timer_off) else pluralStringResource(R.plurals.sleep_minutes, minutes, minutes)) }, onClick = { vm.sleep(minutes); timerMenu = false }) } }
                }
                PlayerTool(Icons.Rounded.AllInclusive, stringResource(R.string.repeat), stringResource(R.string.infinite_play), null, p.repeat, toggle = true, onClick = vm::toggleRepeat)
            }
            if (book) state.speedProfile.bookOverride?.let { override -> BookSpeedOverride(override, state.speedProfile.defaultSpeed, vm::resetBookSpeed) }
            HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 12.dp), color = Color.White.copy(alpha = .08f))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
                TextButton(onClick = { vm.openDevices(true) }) { Icon(Icons.Rounded.SpeakerGroup, null, Modifier.size(18.dp), tint = PlexHighlight); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.this_phone), color = PlexHighlight, fontSize = 12.sp) }
                val soundState = onOffState(state.audioEffects.enabled).asString()
                TextButton(onClick = { sound = true }, modifier = Modifier.semantics { stateDescription = soundState }) {
                    val tint = if (state.audioEffects.enabled) PlexHighlight else PlexMuted
                    Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp), tint = tint); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sound_title), color = tint, fontSize = 12.sp)
                }
                if (!book) TextButton(onClick = { chapters = true }) { Icon(Icons.Rounded.QueueMusic, null, Modifier.size(20.dp), tint = PlexMuted); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.queue), color = PlexMuted) }
            }
        }
    }
    if (sound) SoundSheet(state.audioEffects, effectsNotice(p.unsupportedEffects), vm::updateAudioEffects) { sound = false }
    if (chapters) ModalBottomSheet(onDismissRequest = { chapters = false }, containerColor = PlexPanel) {
        Text(stringResource(if (book) R.string.chapters else R.string.up_next), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).asHeading())
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            if (book) itemsIndexed(bookChapters(state.queue, p.trackIndex, position.positionMs), key = { index, entry -> "${entry.trackIndex}:${entry.offsetMs}:$index" }) { _, entry ->
                ChapterRow(entry) { vm.chapter(entry.trackIndex, entry.offsetMs); chapters = false }
            } else itemsIndexed(state.queue, key = { index, track -> "${track.id}:$index" }) { index, track -> TrackRow(track, index, index == p.trackIndex) { vm.chapter(index); chapters = false } }
        }
    }
}

/** Ticket 133: the book plays at its own speed; one tap returns it to the default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookSpeedOverride(override: Float, defaultSpeed: Float, onReset: () -> Unit) {
    // Wraps (text above the button) at large font sizes; the note is never cut short.
    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.book_speed_override, speedLabel(override), speedLabel(defaultSpeed)), Modifier.align(Alignment.CenterVertically), color = PlexHighlight, fontSize = 12.sp)
        TextButton(onClick = onReset) { Text(stringResource(R.string.reset_book_speed, speedLabel(defaultSpeed)), fontSize = 12.sp) }
    }
}

/** A chapter: one TalkBack stop ("Chapter title, subtitle, 12 minutes 3 seconds") whose action is "Play". */
@Composable
private fun ChapterRow(entry: ChapterEntry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.a11y_play), onClick = onClick).padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (entry.active) Icon(Icons.Rounded.GraphicEq, stringResource(R.string.current_chapter), Modifier.size(22.dp), tint = PlexHighlight)
        else Text(entry.label, Modifier.widthIn(min = 22.dp), color = PlexMuted, fontSize = 12.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title.asString(), color = if (entry.active) PlexHighlight else Color.White, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(entry.subtitle.asString(), color = PlexMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SpokenDuration(entry.durationMs)
    }
}

/** "3:45" on screen, "3 minutes 45 seconds" to TalkBack. */
@Composable
private fun SpokenDuration(durationMs: Long) {
    val spoken = spokenDuration(durationMs).asString()
    Text(playbackTime(durationMs), Modifier.semantics { contentDescription = spoken }, color = PlexMuted, fontSize = 11.sp)
}

@Composable
internal fun PlayButton(playing: Boolean, enabled: Boolean = true, size: Int = 58, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = PlexBlue, contentColor = Color.White)) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (playing) R.string.pause_playback else R.string.play), Modifier.size((size * .52f).dp))
    }
}

/**
 * A labelled player tool. TalkBack reads [description] with [state] ("Playback speed, 1.25 times speed"); the short visual
 * [label] is not read again. A [toggle] tool (infinite play) is a switch whose on/off is [selected].
 */
@Composable
private fun PlayerTool(icon: ImageVector, label: String, description: String, state: String? = null, selected: Boolean = false, toggle: Boolean = false, onClick: () -> Unit) {
    val action = if (toggle) Modifier.toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() }) else Modifier.clickable(role = Role.Button, onClick = onClick)
    Column(Modifier.widthIn(min = 64.dp).clip(RoundedCornerShape(10.dp)).then(action).semantics { contentDescription = description; if (state != null) stateDescription = state }.padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (selected) PlexHighlight else PlexMuted)
        Text(label, Modifier.clearAndSetSemantics {}, color = if (selected) PlexHighlight else PlexMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

/** A track: one TalkBack stop whose action is "Play"; [modifier] carries any row actions (playlist editing). */
@Composable
private fun TrackRow(track: PlexTrack, index: Int, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.a11y_play), onClick = onClick).padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (active) Icon(Icons.Rounded.GraphicEq, stringResource(R.string.current_track), Modifier.size(22.dp), tint = PlexHighlight)
        else Text("${index + 1}", Modifier.widthIn(min = 22.dp), color = PlexMuted, fontSize = 12.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(track.title, color = if (active) PlexHighlight else Color.White, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(track.artist, color = PlexMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SpokenDuration(track.durationMs)
    }
}
