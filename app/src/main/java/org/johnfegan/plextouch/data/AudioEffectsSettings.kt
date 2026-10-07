package org.johnfegan.plextouch.data

import androidx.compose.runtime.Immutable
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A small, fixed set of equaliser curves (ticket 135). Each is a list of (frequency Hz, gain millibels) anchors;
 * [equaliserLevels] interpolates them onto whatever bands the device's `Equalizer` exposes.
 */
enum class EqualiserPreset(val anchors: List<Pair<Int, Int>>) {
    FLAT(emptyList()),
    /** Spoken word: trims rumble and boominess, lifts the 2–4 kHz consonant range. */
    SPEECH(listOf(60 to -600, 230 to -300, 910 to 0, 3_600 to 500, 14_000 to 100)),
    BASS_CUT(listOf(60 to -800, 230 to -400, 910 to 0, 3_600 to 0, 14_000 to 0)),
    TREBLE_LIFT(listOf(60 to 0, 230 to 0, 910 to 0, 3_600 to 300, 14_000 to 600)),
}

/**
 * Sound processing for phone playback. Global rather than per library mode: it describes the phone's output (a quiet
 * narrator, a thin speaker), and one switch that turns everything off is easier to trust than two.
 * [enabled] is the master switch; while it is false nothing is processed whatever the other fields say.
 */
@Immutable
data class AudioEffectsSettings(
    val enabled: Boolean = false,
    val voiceBoost: Boolean = true,
    val preset: EqualiserPreset = EqualiserPreset.SPEECH,
) {
    companion object {
        /** Loudness lift applied by Voice boost, in millibels (+6 dB). */
        const val VOICE_BOOST_GAIN_MB = 600
    }
}

/**
 * Per-band levels (millibels) for [preset] on a device equaliser whose bands centre on [centresHz], clamped to the
 * device's [minMb]..[maxMb]. Interpolation is linear in log-frequency between the preset's anchors.
 */
fun equaliserLevels(preset: EqualiserPreset, centresHz: List<Int>, minMb: Int, maxMb: Int): List<Int> = centresHz.map { hz ->
    val anchors = preset.anchors
    val level = when {
        anchors.isEmpty() || hz <= 0 -> 0
        hz <= anchors.first().first -> anchors.first().second
        hz >= anchors.last().first -> anchors.last().second
        else -> {
            val upper = anchors.indexOfFirst { it.first >= hz }
            val (f0, g0) = anchors[upper - 1]
            val (f1, g1) = anchors[upper]
            val t = (ln(hz.toDouble()) - ln(f0.toDouble())) / (ln(f1.toDouble()) - ln(f0.toDouble()))
            (g0 + (g1 - g0) * t).roundToInt()
        }
    }
    if (minMb > maxMb) 0 else level.coerceIn(minMb, maxMb)
}
