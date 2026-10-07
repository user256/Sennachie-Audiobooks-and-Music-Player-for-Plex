package org.johnfegan.plextouch.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.AudioEffectsSettings
import org.johnfegan.plextouch.data.EqualiserPreset

/** Seek-bar fill fractions: played is drawn over buffered, which is drawn over the unbuffered track. */
@Immutable
data class SeekSegments(val played: Float, val buffered: Float)

/**
 * Clamps the player's values into 0..1 fractions. An unknown duration draws an empty track; buffered never trails
 * played (what is playing has been loaded), so a seek past the buffer shows only the played fill.
 */
fun seekSegments(positionMs: Long, bufferedMs: Long, durationMs: Long): SeekSegments {
    if (durationMs <= 0) return SeekSegments(0f, 0f)
    val played = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
    val buffered = (bufferedMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
    return SeekSegments(played, buffered.coerceAtLeast(played))
}

/** The seek bar's track: played, buffered and unbuffered duration as three fills. */
@Composable
internal fun SeekTrack(segments: SeekSegments, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(SeekUnbuffered, cornerRadius = radius)
        if (segments.buffered > 0f) drawRoundRect(SeekBuffered, size = Size(size.width * segments.buffered, size.height), cornerRadius = radius)
        if (segments.played > 0f) drawRoundRect(PlexBlue, size = Size(size.width * segments.played, size.height), cornerRadius = radius)
    }
}

private val SeekUnbuffered = Color(0xFF314452)
private val SeekBuffered = Color(0xFF6B8396)

@StringRes
private fun EqualiserPreset.label(): Int = when (this) {
    EqualiserPreset.FLAT -> R.string.eq_flat
    EqualiserPreset.SPEECH -> R.string.eq_speech
    EqualiserPreset.BASS_CUT -> R.string.eq_bass_cut
    EqualiserPreset.TREBLE_LIFT -> R.string.eq_treble_lift
}

/**
 * Voice boost and equaliser for phone playback. The master switch turns every effect off at once; [notice] is the
 * service's one-time report that something chosen cannot run on this device.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SoundSheet(settings: AudioEffectsSettings, notice: UiText?, onChange: (AudioEffectsSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = PlexPanel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.sound_title), Modifier.asHeading(), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.sound_scope), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            SoundSwitch(stringResource(R.string.sound_enabled), stringResource(R.string.sound_enabled_body), settings.enabled, true) { onChange(settings.copy(enabled = it)) }
            SoundSwitch(stringResource(R.string.voice_boost), stringResource(R.string.voice_boost_body), settings.voiceBoost, settings.enabled) { onChange(settings.copy(voiceBoost = it)) }
            Text(stringResource(R.string.equaliser), Modifier.asHeading(), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqualiserPreset.entries.forEach { preset ->
                    FilterChip(selected = settings.preset == preset, enabled = settings.enabled, onClick = { onChange(settings.copy(preset = preset)) }, label = { Text(stringResource(preset.label())) })
                }
            }
            if (notice != null) Text(notice.asString(), Modifier.politeLiveRegion(), color = PlexHighlight, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SoundSwitch(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // The title, explanation and switch are one TalkBack stop and one touch target ("Voice boost, on, switch").
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = PlexMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
