package org.johnfegan.plextouch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.app.ListeningHistoryView
import org.johnfegan.plextouch.data.BookHistoryEntry
import org.johnfegan.plextouch.data.BookHistoryStatus
import org.johnfegan.plextouch.data.ListeningLog
import org.johnfegan.plextouch.data.ListeningStats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "2 hours 5 minutes", "2 hours" or "42 minutes", with plurals from resources. */
fun statDuration(milliseconds: Long): UiText {
    val minutes = (milliseconds.coerceAtLeast(0) / 60_000).toInt()
    val hours = minutes / 60
    return when {
        hours == 0 -> uiPlural(R.plurals.history_minutes, minutes)
        minutes % 60 == 0 -> uiPlural(R.plurals.history_hours, hours)
        else -> uiText(R.string.history_hours_minutes, uiPlural(R.plurals.history_hours, hours), uiPlural(R.plurals.history_minutes, minutes % 60))
    }
}

/** Dates in the phone's locale ("7 Oct 2026"); the composables take no formatter, so their parameters stay stable. */
@Composable
private fun rememberDateFormatter(): DateTimeFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

/** Ticket 138: in-progress, completed and reset books with dates, time listened and streaks, all from this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryScreen(
    view: ListeningHistoryView?,
    notice: UiText?,
    loadedAlbumId: String?,
    onLoad: () -> Unit,
    onBack: () -> Unit,
    onReset: (String) -> Unit,
    onClear: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { onLoad() }
    var resetting by remember { mutableStateOf<BookHistoryEntry?>(null) }
    var clearing by remember { mutableStateOf(false) }
    resetting?.let { entry ->
        AlertDialog(
            onDismissRequest = { resetting = null },
            title = { Text(stringResource(R.string.history_reset_title, entry.album.title)) },
            text = { Text(stringResource(R.string.history_reset_body)) },
            confirmButton = { TextButton(onClick = { resetting = null; onReset(entry.album.id) }) { Text(stringResource(R.string.history_reset_action), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text(stringResource(R.string.history_keep)) } },
        )
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = { TextButton(onClick = { clearing = false; onClear() }) { Text(stringResource(R.string.history_clear_confirm), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.history_keep)) } },
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.history_title), Modifier.asHeading()) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.action_back)) } }) }) { padding ->
        if (view == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(30.dp), color = PlexHighlight, strokeWidth = 2.dp)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            notice?.let { item { InlineNotice(it.asString(), onDismissNotice) } }
            item { StatsCard(view.stats) }
            item { Eyebrow(stringResource(R.string.history_books_title), heading = true) }
            if (view.books.isEmpty()) item { Text(stringResource(R.string.history_empty), color = PlexMuted, style = MaterialTheme.typography.bodyMedium) }
            items(view.books, key = { it.album.id }) { entry ->
                BookHistoryRow(entry, canReset = entry.album.id != loadedAlbumId && entry.status != BookHistoryStatus.RESET) { resetting = entry }
            }
            item {
                TextButton(onClick = { clearing = true }) { Text(stringResource(R.string.history_clear_action), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun StatsCard(stats: ListeningStats) {
    val formatter = rememberDateFormatter()
    Surface(color = PlexPanel, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.history_time_title), Modifier.asHeading(), style = MaterialTheme.typography.titleMedium)
            StatLine(stringResource(R.string.history_today), statDuration(stats.todayMs).asString())
            StatLine(stringResource(R.string.history_week), statDuration(stats.weekMs).asString())
            StatLine(stringResource(R.string.history_month), statDuration(stats.monthMs).asString())
            StatLine(stringResource(R.string.history_retained), statDuration(stats.retainedMs).asString())
            StatLine(stringResource(R.string.history_current_streak), uiPlural(R.plurals.history_streak_days, stats.currentStreak).asString())
            StatLine(stringResource(R.string.history_longest_streak), uiPlural(R.plurals.history_streak_days, stats.longestStreak).asString())
            stats.since?.let { since ->
                Text(stringResource(R.string.history_since, formatter.format(since), uiPlural(R.plurals.history_days_listened, stats.daysListened).asString()), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(R.string.history_explain, uiPlural(R.plurals.history_streak_days, ListeningLog.RETENTION_DAYS.toInt()).asString(), uiPlural(R.plurals.history_minutes, (ListeningLog.STREAK_THRESHOLD_MS / 60_000).toInt()).asString()),
                color = PlexMuted, style = MaterialTheme.typography.bodySmall,
            )
            if (stats.zones.size > 1) Text(stringResource(R.string.history_zones, stats.zones.joinToString()), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    // "Today, 42 minutes" read as one line rather than two.
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = PlexMuted, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BookHistoryRow(entry: BookHistoryEntry, canReset: Boolean, onReset: () -> Unit) {
    val formatter = rememberDateFormatter()
    fun date(at: Long): String = formatter.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate())
    val resetLabel = stringResource(R.string.history_reset_action)
    val percent = spokenPercent(entry.fraction).asString()
    Surface(color = PlexPanel, shape = RoundedCornerShape(12.dp)) {
        // Ticket 140: the book's title, status, progress and dates are one TalkBack stop; "Start over" is also on its actions menu.
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.rowActions(RowAction(resetLabel, canReset, onReset)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.album.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(albumArtist(entry.album), color = PlexMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(stringResource(when (entry.status) {
                    BookHistoryStatus.IN_PROGRESS -> R.string.history_in_progress
                    BookHistoryStatus.COMPLETED -> R.string.history_completed
                    BookHistoryStatus.RESET -> R.string.history_reset
                }), color = PlexHighlight, style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(progress = { entry.fraction }, modifier = Modifier.fillMaxWidth().semantics { stateDescription = percent }, color = PlexHighlight)
            val detail = when (entry.status) {
                BookHistoryStatus.IN_PROGRESS -> stringResource(R.string.history_in_progress_detail, (entry.fraction * 100).toInt(), date(entry.updatedAt))
                BookHistoryStatus.COMPLETED -> stringResource(R.string.history_completed_detail, date(entry.completedAt ?: entry.updatedAt))
                BookHistoryStatus.RESET -> stringResource(R.string.history_reset_detail, date(entry.resetAt ?: entry.updatedAt))
            }
            Text(detail, color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            if (entry.status == BookHistoryStatus.IN_PROGRESS) entry.completedAt?.let {
                Text(stringResource(R.string.history_finished_before, date(it)), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (canReset) TextButton(onClick = onReset, contentPadding = PaddingValues(0.dp)) { Text(resetLabel, color = PlexHighlight) }
        }
    }
}
