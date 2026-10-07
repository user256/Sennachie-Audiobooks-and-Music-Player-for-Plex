package org.johnfegan.plextouch.ui

import androidx.compose.runtime.Immutable
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.DownloadStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Ticket 140: what TalkBack says. Everything here is pure and returns [UiText], so the spoken labels, state descriptions and
 * adjust-action steps are unit-tested on the JVM; the composables only attach them to semantics.
 */

/** The seek slider's TalkBack adjust step (swipe up/down or volume keys while it has focus). */
const val SEEK_STEP_MS = 30_000L

/**
 * Compose's default TalkBack adjust step for a slider without `steps`: one twentieth of the range. A `setProgress`
 * request that moves by about this much is a TalkBack adjust, not a value chosen by another service (Voice Access, Switch
 * Access), so [accessibleSeekTarget] turns it into a [SEEK_STEP_MS] step.
 */
private const val DEFAULT_ADJUST_DIVISIONS = 20

/** "1 hour 5 minutes 3 seconds", "12 minutes 3 seconds", "45 minutes" or "0 seconds": every non-zero unit, spoken. */
fun spokenDuration(milliseconds: Long): UiText {
    val total = milliseconds.coerceAtLeast(0) / 1_000
    val hours = (total / 3_600).toInt()
    val minutes = (total / 60 % 60).toInt()
    val seconds = (total % 60).toInt()
    val parts = listOfNotNull(
        hours.takeIf { it > 0 }?.let { uiPlural(R.plurals.a11y_hours, it) },
        minutes.takeIf { it > 0 }?.let { uiPlural(R.plurals.a11y_minutes, it) },
        seconds.takeIf { it > 0 || total == 0L }?.let { uiPlural(R.plurals.a11y_seconds, it) },
    )
    return parts.reduce { spoken, next -> uiText(R.string.a11y_duration_join, spoken, next) }
}

/** A rounded-down "time left" in hours and minutes ("7 hours 3 minutes"), spoken in place of the visual "7h 3m". */
fun spokenMinutes(milliseconds: Long): UiText {
    val minutes = milliseconds.coerceAtLeast(0) / 60_000
    return if (minutes == 0L) uiPlural(R.plurals.a11y_minutes, 0) else spokenDuration(minutes * 60_000)
}

/** The seek slider's state: "12 minutes 3 seconds of 45 minutes"; an unknown length reads only the position. */
fun spokenPosition(positionMs: Long, durationMs: Long): UiText =
    if (durationMs <= 0) spokenDuration(positionMs)
    else uiText(R.string.a11y_position_of, spokenDuration(positionMs.coerceIn(0, durationMs)), spokenDuration(durationMs))

/** Ticket 132's whole-book bar: "3 hours 2 minutes of 10 hours, 6 hours 58 minutes left". */
fun spokenBookPosition(progress: BookProgress): UiText =
    uiText(R.string.a11y_book_position, spokenDuration(progress.elapsedMs), spokenDuration(progress.totalMs), spokenDuration(progress.remainingMs))

/** "1.25 times speed": the visual "1.25×" would be read as a multiplication sign. */
fun spokenSpeed(speed: Float): UiText =
    uiText(R.string.a11y_speed, "%.2f".format(Locale.ROOT, speed).trimEnd('0').trimEnd('.'))

/** A 0..1 fraction as whole percent, for bars whose exact time is not known (downloads, history). */
fun spokenPercent(fraction: Float): UiText = uiPlural(R.plurals.a11y_percent, (fraction.coerceIn(0f, 1f) * 100).roundToInt())

/** The album favourite toggle's state. */
fun favouriteState(saved: Boolean): UiText = uiText(if (saved) R.string.a11y_rated_five_stars else R.string.a11y_not_rated)

/** The download control's state: saved, saving N of M, needing a retry, or not saved. */
fun downloadState(download: DownloadStatus?): UiText = when {
    download == null -> uiText(R.string.a11y_not_saved_offline)
    download.ready -> uiText(R.string.a11y_saved_offline)
    download.failed > 0 -> uiPlural(R.plurals.a11y_download_failed, download.failed, download.completed, download.tracks.size, download.failed)
    else -> uiPlural(R.plurals.a11y_saving_offline, download.tracks.size, download.completed, download.tracks.size)
}

/** The sleep timer tool's state: "Off" or "12 minutes left". */
fun sleepState(remainingMinutes: Long?): UiText =
    if (remainingMinutes == null) uiText(R.string.a11y_off) else uiPlural(R.plurals.a11y_minutes_left, remainingMinutes.toInt())

/** On/off for a toggle whose label already names it ("Shuffle", "Infinite play"). */
fun onOffState(on: Boolean): UiText = uiText(if (on) R.string.a11y_on else R.string.a11y_off)

/**
 * Where a `setProgress` request on the seek slider should land. A request about one default adjust step away (TalkBack's
 * swipe or volume key) moves [stepMs] in that direction instead, so a 20-hour file is not adjusted an hour at a time;
 * any other request (a value set directly) is honoured. Always clamped to the file.
 */
fun accessibleSeekTarget(currentMs: Long, requestedMs: Float, durationMs: Long, stepMs: Long = SEEK_STEP_MS): Long {
    if (durationMs <= 0) return 0
    val delta = requestedMs - currentMs
    val defaultStep = durationMs.toFloat() / DEFAULT_ADJUST_DIVISIONS
    val adjust = delta != 0f && abs(abs(delta) - defaultStep) <= defaultStep * .05f
    val target = if (adjust) currentMs + if (delta > 0) stepMs else -stepMs else requestedMs.roundToLong()
    return target.coerceIn(0, durationMs)
}

private fun Float.roundToLong(): Long = kotlin.math.round(this).toLong()

/** The A–Z rail as one adjustable TalkBack control: its letters and which of them have artists. */
@Immutable
data class LetterRail(val letters: List<String>, val available: Set<String>) {
    /** The rail position TalkBack reports: the letter on screen, else the first available one. */
    fun indexOf(active: String?): Int = letters.indexOf(active).takeIf { it >= 0 } ?: letters.indexOfFirst { it in available }.coerceAtLeast(0)

    /**
     * The letter a `setProgress` request lands on. Letters without artists are skipped: the first available letter at or
     * beyond the requested one, else the available letter nearest it on the way back from there, so each TalkBack adjust
     * reaches a letter that scrolls somewhere. Null when no letter in that direction has artists.
     */
    fun step(currentIndex: Int, requested: Float): String? {
        val target = requested.roundToInt().coerceIn(0, letters.lastIndex)
        if (target == currentIndex) return null
        val beyond = if (target > currentIndex) target..letters.lastIndex else target downTo 0
        val between = if (target > currentIndex) target - 1 downTo currentIndex + 1 else target + 1 until currentIndex
        return (beyond + between).map(letters::get).firstOrNull { it in available }
    }

    companion object {
        val LETTERS: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }
        fun of(positions: Map<String, Int>) = LetterRail(LETTERS, positions.keys)
    }
}
