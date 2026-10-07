package org.johnfegan.plextouch.data

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ticket 138: a small, local, append-only listening log. One entry per account scope, server, local calendar day and
 * library mode, holding the playing wall-time measured on this phone that day. It is never sent anywhere.
 *
 * `date` is the ISO local date in the phone's time zone *at the time of listening*, and `zone` is that zone's id (the
 * latest one seen that day), so a trip across time zones is explainable rather than silently re-bucketed. `lastAt` is the
 * latest wall-clock instant counted for the day; it lets a duplicated or overlapping sample add nothing.
 */
@Immutable
data class ListeningDay(
    val scope: String?,
    val server: String,
    val date: String,
    val zone: String,
    val mode: LibraryMode,
    val listenedMs: Long,
    /** Ids of the albums or books played that day, most recent last, at most [MAX_ALBUMS_PER_DAY]. */
    val albums: List<String>,
    val lastAt: Long,
) {
    val localDate: LocalDate get() = LocalDate.parse(date)
}

/** One measured stretch of playing time, `startedAt` to `endedAt` in wall-clock milliseconds, in the phone's zone then. */
data class ListeningSample(
    val scope: String?,
    val server: String,
    val mode: LibraryMode,
    val albumId: String,
    val startedAt: Long,
    val endedAt: Long,
    val zone: ZoneId,
)

object ListeningLog {
    /** Twice the playback service's 5 s heartbeat: a stalled or suspended clock can never add more than this at once. */
    const val MAX_SAMPLE_MS = 10_000L
    /** Days kept, counting today; older days are dropped whenever the log is written. */
    const val RETENTION_DAYS = 400L
    /** A day counts towards a streak once this much was played on it. */
    const val STREAK_THRESHOLD_MS = 60_000L
    /** A hard ceiling on stored entries whatever the number of accounts and servers, so the blob stays small. */
    const val MAX_ENTRIES = 1_600
}

const val MAX_ALBUMS_PER_DAY = 50

/** Gson can leave a non-null Kotlin field null in a damaged blob; such entries are dropped rather than crash a screen. */
fun ListeningDay.wellFormed(): Boolean = runCatching { server.length + zone.length + mode.name.length + albums.size; localDate; true }.getOrDefault(false)

/**
 * Adds one sample to the log. The sample is capped to its last [ListeningLog.MAX_SAMPLE_MS], trimmed so nothing at or
 * before the latest instant already counted for this scope and server is counted again (duplicates and overlaps add
 * nothing), split at local midnight, and the log is then pruned to [ListeningLog.RETENTION_DAYS]. A counted instant that
 * lies well in the future of this sample means the wall clock was set back; it is ignored rather than freezing counting.
 */
fun recordListening(
    log: List<ListeningDay>,
    sample: ListeningSample,
    maxSampleMs: Long = ListeningLog.MAX_SAMPLE_MS,
    retentionDays: Long = ListeningLog.RETENTION_DAYS,
): List<ListeningDay> {
    if (sample.server.isBlank() || sample.albumId.isBlank() || sample.endedAt <= sample.startedAt) return log
    val end = sample.endedAt
    val counted = log.asSequence().filter { it.scope == sample.scope && it.server == sample.server }
        .map { it.lastAt }.filter { it <= end + maxSampleMs }.maxOrNull()
    val start = maxOf(sample.startedAt, end - maxSampleMs, counted ?: Long.MIN_VALUE)
    if (end <= start) return log
    val days = log.toMutableList()
    var cursor = start
    while (cursor < end) {
        val date = Instant.ofEpochMilli(cursor).atZone(sample.zone).toLocalDate()
        val pieceEnd = minOf(end, date.plusDays(1).atStartOfDay(sample.zone).toInstant().toEpochMilli())
        addPiece(days, sample, date, pieceEnd - cursor, pieceEnd)
        cursor = pieceEnd
    }
    val today = Instant.ofEpochMilli(end).atZone(sample.zone).toLocalDate()
    return pruneListeningLog(days, today, retentionDays)
}

private fun addPiece(days: MutableList<ListeningDay>, sample: ListeningSample, date: LocalDate, ms: Long, at: Long) {
    val key = date.toString()
    val index = days.indexOfFirst { it.scope == sample.scope && it.server == sample.server && it.date == key && it.mode == sample.mode }
    val existing = days.getOrNull(index)
    val albums = (existing?.albums.orEmpty() - sample.albumId + sample.albumId).takeLast(MAX_ALBUMS_PER_DAY)
    val day = ListeningDay(sample.scope, sample.server, key, sample.zone.id, sample.mode, (existing?.listenedMs ?: 0) + ms, albums, maxOf(existing?.lastAt ?: 0, at))
    if (index >= 0) days[index] = day else days += day
}

/** Keeps the last `retentionDays` local days (today included) and at most [ListeningLog.MAX_ENTRIES] entries, newest first. */
fun pruneListeningLog(log: List<ListeningDay>, today: LocalDate, retentionDays: Long = ListeningLog.RETENTION_DAYS): List<ListeningDay> {
    val oldest = today.minusDays(retentionDays - 1)
    return log.filter { it.wellFormed() && !it.localDate.isBefore(oldest) }
        .sortedWith(compareByDescending<ListeningDay> { it.date }.thenByDescending { it.lastAt })
        .take(ListeningLog.MAX_ENTRIES)
}

/** Clearing history touches only this account on this server; every other scope's log stays. */
fun clearListeningLog(log: List<ListeningDay>, server: String, scope: String?): List<ListeningDay> =
    log.filterNot { it.server == server && it.scope == scope }

@Immutable
data class ListeningStats(
    val todayMs: Long = 0,
    val weekMs: Long = 0,
    val monthMs: Long = 0,
    /** Everything still retained: at most [ListeningLog.RETENTION_DAYS] days. */
    val retainedMs: Long = 0,
    val daysListened: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    /** The first retained day with any listening, or null for an empty log. */
    val since: LocalDate? = null,
    /** The time zones the days were recorded in; more than one explains a day that looks shifted. */
    val zones: List<String> = emptyList(),
)

/**
 * Totals and streaks for one account on one server, all modes together, from local calendar days. "This week" is today and
 * the six days before it; "this month" is the last 30 days. A streak is a run of consecutive days with at least
 * `thresholdMs` played; the current streak still counts while today has not reached the threshold yet, as long as
 * yesterday did.
 */
fun listeningStats(log: List<ListeningDay>, server: String, scope: String?, today: LocalDate, thresholdMs: Long = ListeningLog.STREAK_THRESHOLD_MS): ListeningStats {
    val mine = log.filter { it.server == server && it.scope == scope && it.wellFormed() }
    if (mine.isEmpty()) return ListeningStats()
    val byDate = mine.groupBy { it.localDate }.mapValues { (_, days) -> days.sumOf { it.listenedMs } }
    fun within(days: Long) = byDate.filterKeys { !it.isAfter(today) && !it.isBefore(today.minusDays(days - 1)) }.values.sum()
    val qualifying = byDate.filterValues { it >= thresholdMs }.keys
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (date in qualifying.sorted()) {
        run = if (previous != null && previous.plusDays(1) == date) run + 1 else 1
        longest = maxOf(longest, run)
        previous = date
    }
    var current = 0
    var cursor = if (today in qualifying) today else today.minusDays(1)
    while (cursor in qualifying) { current++; cursor = cursor.minusDays(1) }
    return ListeningStats(
        todayMs = byDate[today] ?: 0, weekMs = within(7), monthMs = within(30), retainedMs = byDate.values.sum(),
        daysListened = byDate.count { it.value > 0 }, currentStreak = current, longestStreak = longest,
        since = byDate.keys.minOrNull(), zones = mine.map { it.zone }.distinct().sorted(),
    )
}
