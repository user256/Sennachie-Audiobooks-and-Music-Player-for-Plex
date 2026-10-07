package org.johnfegan.plextouch.watch

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.johnfegan.plextouch.wear.shared.Command
import org.johnfegan.plextouch.wear.shared.CommandOutcome
import org.johnfegan.plextouch.wear.shared.HeldBookPosition
import org.johnfegan.plextouch.wear.shared.LocalPosition
import org.johnfegan.plextouch.wear.shared.NowPlaying
import org.johnfegan.plextouch.wear.shared.PhoneState
import org.johnfegan.plextouch.wear.shared.PhoneStatus
import org.johnfegan.plextouch.wear.shared.ReceiptOutcome
import org.johnfegan.plextouch.wear.shared.RequestTransfer
import org.johnfegan.plextouch.wear.shared.ResumeChoice
import org.johnfegan.plextouch.wear.shared.TransferManifest
import org.johnfegan.plextouch.wear.shared.TransferRefusal
import org.johnfegan.plextouch.wear.shared.TransferRemoved
import org.johnfegan.plextouch.wear.shared.WatchBooks
import org.johnfegan.plextouch.wear.shared.WatchCheckpoints
import org.johnfegan.plextouch.wear.shared.WearAction

private const val SKIP_MS = 30_000L
/** The phone app's own speed steps for audiobooks; the phone clamps and saves the choice as the book's speed. */
private val SPEEDS = listOf(0.8f, 1f, 1.1f, 1.2f, 1.25f, 1.3f, 1.5f, 1.75f, 2f, 2.5f, 3f)

@Composable
fun WatchApp() {
    val nav = rememberSwipeDismissableNavController()
    MaterialTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                composable("home") { HomeScreen(open = { nav.navigate(it) }) }
                composable("phone") { PhoneScreen(openSpeed = { nav.navigate("speed") }) }
                composable("speed") { SpeedScreen(done = { nav.popBackStack() }) }
                composable("list") { ReadingListScreen(open = { nav.navigate("entry/$it") }) }
                composable("entry/{id}") { entry -> EntryScreen(entry.arguments?.getString("id").orEmpty(), copy = { nav.navigate("consent/$it") }) }
                composable("consent/{id}") { entry ->
                    ConsentScreen(entry.arguments?.getString("id").orEmpty(), done = {
                        nav.popBackStack("home", inclusive = false)
                        nav.navigate("book")
                    }, cancel = { nav.popBackStack() })
                }
                composable("book") { BookScreen() }
            }
        }
    }
}

@Composable
private fun Page(content: ScalingLazyListScope.() -> Unit) {
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Action(label: String, secondary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
    )
}

@Composable
private fun Round(@DrawableRes icon: Int, description: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    if (primary) FilledIconButton(onClick = onClick, enabled = enabled) { Icon(painterResource(icon), contentDescription = description) }
    else IconButton(onClick = onClick, enabled = enabled) { Icon(painterResource(icon), contentDescription = description) }
}

/** The connection line every phone-backed screen starts with; null when the phone is reachable and has state. */
@Composable
private fun phoneStatus(reach: PhoneReach, phone: PhoneState?, unsupported: Boolean): String? {
    val app = stringResource(R.string.app_name)
    return when {
        unsupported -> stringResource(R.string.status_update, app)
        reach == PhoneReach.CHECKING -> stringResource(R.string.status_checking)
        reach == PhoneReach.NOT_INSTALLED -> stringResource(R.string.status_no_app, app)
        reach == PhoneReach.UNREACHABLE -> stringResource(R.string.status_unreachable)
        phone == null -> stringResource(R.string.status_waiting_state)
        phone.status == PhoneStatus.SIGNED_OUT -> stringResource(R.string.status_signed_out)
        else -> null
    }
}

@Composable
private fun HomeScreen(open: (String) -> Unit) {
    val phone by WatchRepository.phone.collectAsStateWithLifecycle()
    val reach by WatchRepository.reach.collectAsStateWithLifecycle()
    val unsupported by WatchRepository.unsupported.collectAsStateWithLifecycle()
    val book by WatchRepository.book.collectAsStateWithLifecycle()
    val status = phoneStatus(reach, phone, unsupported)
    val now = phone?.nowPlaying?.takeIf { phone?.status != PhoneStatus.SIGNED_OUT }
    Page {
        item { ListHeader { Text(stringResource(R.string.app_name)) } }
        status?.let { item { Note(it) } }
        item {
            Action(stringResource(R.string.home_phone), now?.title ?: stringResource(R.string.status_nothing).takeIf { status == null }) { open("phone") }
        }
        item { Action(stringResource(R.string.home_reading_list)) { open("list") } }
        item {
            Action(stringResource(R.string.home_watch_book), listOfNotNull(book.manifest?.title, stringResource(R.string.preview_label).takeIf { BuildConfig.OFFLINE_PREVIEW }).joinToString(" · ")) { open("book") }
        }
    }
}

@Composable
private fun PhoneScreen(openSpeed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phone by WatchRepository.phone.collectAsStateWithLifecycle()
    val reach by WatchRepository.reach.collectAsStateWithLifecycle()
    val unsupported by WatchRepository.unsupported.collectAsStateWithLifecycle()
    val result by WatchRepository.lastCommand.collectAsStateWithLifecycle()
    var sendFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { WatchRepository.commandResult(null); PhoneLink.requestState(context.applicationContext) }
    val status = phoneStatus(reach, phone, unsupported)
    val now = phone?.nowPlaying?.takeIf { phone?.status != PhoneStatus.SIGNED_OUT }
    fun send(action: WearAction) = scope.launch {
        WatchRepository.commandResult(null)
        sendFailed = !PhoneLink.send(context.applicationContext, Command(action))
    }
    Page {
        item { ListHeader { Text(stringResource(if (now?.live == true) R.string.now_playing else R.string.last_played)) } }
        status?.let { item { Note(it) } }
        if (now == null && status == null) item { Note(stringResource(R.string.status_nothing)) }
        now?.let { book ->
            item { Text(book.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            item { Note(chapterLine(book)) }
            item { Note(positionLine(book, phone?.capturedAt ?: 0)) }
        }
        item {
            val connected = status == null && now != null
            Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Round(R.drawable.ic_back_30, stringResource(R.string.action_back_30), enabled = connected) { send(WearAction.BACK_30) }
                Round(if (now?.playing == true) R.drawable.ic_pause else R.drawable.ic_play,
                    stringResource(if (now?.playing == true) R.string.action_pause else R.string.action_play), primary = true, enabled = status == null) { send(WearAction.TOGGLE) }
                Round(R.drawable.ic_forward_30, stringResource(R.string.action_forward_30), enabled = connected) { send(WearAction.FORWARD_30) }
            }
        }
        item {
            val connected = status == null && now?.live == true
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Round(R.drawable.ic_skip_previous, stringResource(R.string.action_previous_chapter), enabled = connected) { send(WearAction.PREVIOUS_CHAPTER) }
                Round(R.drawable.ic_skip_next, stringResource(R.string.action_next_chapter), enabled = connected) { send(WearAction.NEXT_CHAPTER) }
            }
        }
        if (now?.live == true && now.audiobook) item {
            Action(stringResource(R.string.action_speed, stringResource(R.string.speed_value, speedLabel(now.speed))), enabled = status == null, onClick = openSpeed)
        }
        val failure = when {
            sendFailed -> R.string.command_failed
            result?.outcome == CommandOutcome.NOTHING_LOADED -> R.string.command_nothing
            result?.outcome == CommandOutcome.NOT_AUDIOBOOK -> R.string.command_not_audiobook
            result?.outcome == CommandOutcome.FAILED -> R.string.command_failed
            else -> null
        }
        failure?.let { item { Note(stringResource(it)) } }
    }
}

@Composable
private fun SpeedScreen(done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phone by WatchRepository.phone.collectAsStateWithLifecycle()
    val current = phone?.nowPlaying?.speed
    Page {
        item { ListHeader { Text(stringResource(R.string.speed_title)) } }
        item { Note(stringResource(R.string.speed_saved_note)) }
        SPEEDS.forEach { speed ->
            item {
                Action(stringResource(R.string.speed_value, speedLabel(speed)), enabled = current != speed) {
                    scope.launch { PhoneLink.send(context.applicationContext, Command(WearAction.SPEED, speed)); done() }
                }
            }
        }
    }
}

@Composable
private fun ReadingListScreen(open: (String) -> Unit) {
    val phone by WatchRepository.phone.collectAsStateWithLifecycle()
    val reach by WatchRepository.reach.collectAsStateWithLifecycle()
    val unsupported by WatchRepository.unsupported.collectAsStateWithLifecycle()
    val status = phoneStatus(reach, phone, unsupported)
    val context = LocalContext.current
    val entries = phone?.takeIf { it.status != PhoneStatus.SIGNED_OUT }?.entries.orEmpty()
    Page {
        item { ListHeader { Text(stringResource(R.string.home_reading_list)) } }
        status?.let { item { Note(it) } }
        if (entries.isEmpty() && phone?.status != PhoneStatus.SIGNED_OUT) item { Note(stringResource(R.string.reading_list_empty)) }
        entries.forEach { entry ->
            item {
                Action(entry.title, entry.downloadedBytes?.let { stringResource(R.string.entry_on_phone, size(context, it)) } ?: entry.author) { open(entry.albumId) }
            }
        }
    }
}

@Composable
private fun EntryScreen(albumId: String, copy: (String) -> Unit) {
    val phone by WatchRepository.phone.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val entry = phone?.entries?.firstOrNull { it.albumId == albumId }
    Page {
        item { ListHeader { Text(entry?.title.orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis) } }
        entry?.author?.takeIf { it.isNotBlank() }?.let { item { Note(it) } }
        val bytes = entry?.downloadedBytes
        if (bytes == null) item { Note(stringResource(R.string.entry_not_downloaded)) }
        else {
            item { Note(stringResource(R.string.entry_on_phone, size(context, bytes))) }
            item { Action(stringResource(R.string.copy_to_watch), stringResource(R.string.preview_label).takeIf { BuildConfig.OFFLINE_PREVIEW }) { copy(albumId) } }
        }
    }
}

/** Explicit consent: the size, the space left, how it travels and that only one book fits, before anything is asked of the phone. */
@Composable
private fun ConsentScreen(albumId: String, done: () -> Unit, cancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phone by WatchRepository.phone.collectAsStateWithLifecycle()
    val entry = phone?.entries?.firstOrNull { it.albumId == albumId }
    val bytes = entry?.downloadedBytes ?: 0
    var refusal by remember { mutableStateOf<TransferRefusal?>(null) }
    Page {
        item { ListHeader { Text(stringResource(R.string.consent_title)) } }
        item { Note(stringResource(R.string.consent_body, entry?.title.orEmpty(), size(context, bytes), size(context, WatchFiles.freeBytes(context)))) }
        refusal?.let { item { Note(stringResource(R.string.book_failed, stringResource(refusalText(it)))) } }
        item {
            Action(stringResource(R.string.consent_confirm), enabled = entry != null && bytes > 0) {
                val app = context.applicationContext
                var refused: TransferRefusal? = null
                WatchRepository.update(app) { book ->
                    val (next, reason) = WatchBooks.request(book, albumId, bytes, WatchFiles.freeBytes(app), System.currentTimeMillis())
                    refused = reason
                    next
                }
                refusal = refused
                if (refused == null) scope.launch {
                    if (!PhoneLink.send(app, RequestTransfer(albumId))) WatchRepository.update(app) { WatchBooks.refused(it, albumId, TransferRefusal.FAILED) }
                    done()
                }
            }
        }
        item { Action(stringResource(R.string.consent_cancel), onClick = cancel) }
    }
}

@Composable
private fun BookScreen() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val book by WatchRepository.book.collectAsStateWithLifecycle()
    val phone by WatchRepository.phone.collectAsStateWithLifecycle()
    val player = remember { LocalPlayer(app) }
    DisposableEffect(player) { onDispose { player.release() } }
    val local by player.state
    var confirmRemove by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<HeldBookPosition?>(null) }
    var stored by remember { mutableLongStateOf(0L) }
    val manifest = book.manifest
    LaunchedEffect(manifest?.transferId, book.receiving) {
        while (manifest != null && book.receiving != null) { stored = WatchFiles.storedBytes(app, manifest); delay(1_000) }
    }
    val present = remember(manifest?.transferId, book.complete) { manifest != null && book.complete && WatchFiles.present(app, manifest) }

    Page {
        item { ListHeader { Text(manifest?.title ?: stringResource(R.string.home_watch_book), maxLines = 2, overflow = TextOverflow.Ellipsis) } }
        if (BuildConfig.OFFLINE_PREVIEW) item { Note(stringResource(R.string.preview_label)) }
        when {
            manifest == null && book.heldAlbumId(System.currentTimeMillis()) != null -> item { Note(stringResource(R.string.book_waiting)) }
            manifest == null -> {
                book.failure?.let { item { Note(stringResource(R.string.book_failed, stringResource(refusalText(it)))) } }
                item { Note(stringResource(R.string.book_none)) }
            }
            !book.complete -> {
                val receiving = book.receiving
                if (receiving != null) item {
                    Note(stringResource(R.string.book_receiving, receiving + 1, manifest.files.size, size(context, stored), size(context, manifest.totalBytes)))
                }
                book.failure?.let { failure ->
                    item { Note(stringResource(R.string.book_failed, stringResource(refusalText(failure)))) }
                    item {
                        Action(stringResource(R.string.book_retry)) {
                            WatchRepository.update(app) { WatchBooks.retry(it) }
                            scope.launch { PhoneLink.requestNextFile(app) }
                        }
                    }
                }
            }
            !present -> item { Note(stringResource(R.string.book_verify_failed)) }
            else -> {
                val pendingAsk = ask
                if (pendingAsk != null) {
                    item { ListHeader { Text(stringResource(R.string.resume_title)) } }
                    item { Note(stringResource(R.string.resume_body, positionText(manifest, pendingAsk.trackId, pendingAsk.positionMs), positionText(manifest, book.position?.trackId, book.position?.positionMs ?: 0))) }
                    item {
                        Action(stringResource(R.string.resume_phone)) {
                            val chosen = WatchRepository.update(app) { WatchBooks.resumeFromPhone(it, pendingAsk, System.currentTimeMillis()) }
                            ask = null
                            player.start(manifest, chosen.position)
                        }
                    }
                    item { Action(stringResource(R.string.resume_watch)) { ask = null; player.start(manifest, book.position) } }
                } else {
                    item { Note(if (local.loaded) positionText(manifest, manifest.files.getOrNull(local.index)?.trackId, local.positionMs) else stringResource(R.string.book_ready)) }
                    item {
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Round(R.drawable.ic_back_30, stringResource(R.string.action_back_30), enabled = local.loaded) { player.skip(-SKIP_MS) }
                            Round(if (local.playing) R.drawable.ic_pause else R.drawable.ic_play,
                                stringResource(if (local.playing) R.string.action_pause else R.string.book_play_local), primary = true, enabled = local.connected) {
                                if (local.loaded) player.toggle() else {
                                    val held = phone?.heldBook?.takeIf { it.albumId == manifest.albumId }
                                    val position = book.position ?: LocalPosition(manifest.files[manifest.resumeIndex].trackId, manifest.resumePositionMs, manifest.createdAt)
                                    if (WatchCheckpoints.resumeChoice(position, held) == ResumeChoice.ASK) ask = held else player.start(manifest, position)
                                }
                            }
                            Round(R.drawable.ic_forward_30, stringResource(R.string.action_forward_30), enabled = local.loaded) { player.skip(SKIP_MS) }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                            Round(R.drawable.ic_skip_previous, stringResource(R.string.action_previous_chapter), enabled = local.loaded) { player.chapter(manifest, forward = false) }
                            Round(R.drawable.ic_skip_next, stringResource(R.string.action_next_chapter), enabled = local.loaded) { player.chapter(manifest, forward = true) }
                        }
                    }
                }
            }
        }
        if (book.pending.isNotEmpty()) item { Note(pluralStringResource(R.plurals.pending_sync, book.pending.size, book.pending.size)) }
        noticeText(book.notice)?.let { item { Note(stringResource(it)) } }
        if (manifest != null || book.failure != null) {
            if (!confirmRemove) item { Action(stringResource(R.string.book_remove)) { confirmRemove = true } }
            else {
                item { Note(stringResource(R.string.book_remove_confirm, manifest?.title.orEmpty())) }
                item {
                    Action(stringResource(R.string.book_remove)) {
                        confirmRemove = false
                        player.clear()
                        val removed = book.manifest?.transferId
                        WatchRepository.update(app) { WatchBooks.removed(it) }
                        WatchFiles.clear(app)
                        removed?.let { id -> scope.launch { PhoneLink.send(app, TransferRemoved(id)) } }
                    }
                }
                item { Action(stringResource(R.string.consent_cancel)) { confirmRemove = false } }
            }
        }
    }
}

private fun noticeText(notice: ReceiptOutcome?): Int? = when (notice) {
    ReceiptOutcome.STALE -> R.string.notice_stale
    ReceiptOutcome.SCOPE_CHANGED -> R.string.notice_scope
    ReceiptOutcome.UNKNOWN_TRANSFER -> R.string.notice_unknown
    else -> null
}

@StringRes
private fun refusalText(reason: TransferRefusal): Int = when (reason) {
    TransferRefusal.SIGNED_OUT -> R.string.refusal_signed_out
    TransferRefusal.NOT_ON_LIST -> R.string.refusal_not_on_list
    TransferRefusal.NOT_DOWNLOADED -> R.string.refusal_not_downloaded
    TransferRefusal.TOO_LARGE -> R.string.refusal_too_large
    TransferRefusal.TOO_MANY_FILES -> R.string.refusal_too_many_files
    TransferRefusal.INSUFFICIENT_SPACE -> R.string.refusal_insufficient_space
    TransferRefusal.ALREADY_HOLDING -> R.string.refusal_already_holding
    TransferRefusal.BUSY -> R.string.refusal_busy
    TransferRefusal.FAILED -> R.string.refusal_failed
}

@Composable
private fun chapterLine(book: NowPlaying): String {
    val title = book.chapterTitle?.takeIf { it.isNotBlank() }
    return when {
        title != null -> title
        book.chapterCount > 0 -> stringResource(R.string.chapter_of, book.chapterNumber, book.chapterCount)
        else -> stringResource(R.string.chapter_number, book.chapterNumber)
    }
}

/** The phone publishes on events only; while playing, the watch advances the shown offset by the published speed. */
@Composable
private fun positionLine(book: NowPlaying, capturedAt: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(book.playing, capturedAt) {
        while (book.playing && book.live) { delay(1_000); now = System.currentTimeMillis() }
    }
    if (!book.live) return stringResource(R.string.percent_listened, book.bookPermille / 10)
    val elapsed = if (book.playing) ((now - capturedAt).coerceAtLeast(0) * book.speed).toLong() else 0
    val position = (book.positionMs + elapsed).let { if (book.durationMs > 0) it.coerceAtMost(book.durationMs) else it }
    return stringResource(R.string.position_of, clock(position), clock(book.durationMs))
}

@Composable
private fun positionText(manifest: TransferManifest, trackId: String?, positionMs: Long): String {
    val index = manifest.files.indexOfFirst { it.trackId == trackId }.coerceAtLeast(0)
    val markers = manifest.files[index].markers
    val chapter = if (markers.size >= 2) markers.count { it <= positionMs }.coerceAtLeast(1) else index + 1
    return stringResource(R.string.position_label, chapter, clock(positionMs))
}

private fun clock(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun speedLabel(speed: Float): String = if (speed % 1f == 0f) "%.0f".format(speed) else "%.2f".format(speed).trimEnd('0')

private fun size(context: Context, bytes: Long): String =
    if (bytes >= 1L shl 30) context.getString(R.string.size_gb, bytes / 1_073_741_824.0) else context.getString(R.string.size_mb, bytes.coerceAtLeast(0) / 1_048_576.0)
