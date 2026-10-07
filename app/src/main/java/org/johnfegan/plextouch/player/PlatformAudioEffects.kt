package org.johnfegan.plextouch.player

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import org.johnfegan.plextouch.data.AudioEffectsSettings
import org.johnfegan.plextouch.data.EqualiserPreset
import org.johnfegan.plextouch.data.equaliserLevels

/**
 * The Android side of ticket 135. `LoudnessEnhancer` (Voice boost) and `Equalizer` are both available from API 19, so
 * minSdk 26 needs no version branches (`DynamicsProcessing` would need API 28 and a fallback anyway). Each is created
 * on the ExoPlayer's own audio session only, never session 0 (the global mix), so it shapes this app's phone output
 * and nothing else: no file is rewritten and the Sonos handoff is untouched.
 */
internal class PlatformAudioEffects : AudioEffectFactory {
    override fun capabilities(): EffectCapabilities {
        val types = AudioEffect.queryEffects()?.map { it.type }.orEmpty()
        return EffectCapabilities(
            voiceBoost = AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER in types,
            equaliser = AudioEffect.EFFECT_TYPE_EQUALIZER in types,
        )
    }

    override fun create(kind: AudioEffectKind, audioSessionId: Int): AudioEffectHandle = when (kind) {
        AudioEffectKind.VOICE_BOOST -> VoiceBoost(LoudnessEnhancer(audioSessionId))
        AudioEffectKind.EQUALISER -> PresetEqualiser(Equalizer(0, audioSessionId))
    }
}

private fun AudioEffect.enable(enabled: Boolean) {
    if (this.enabled == enabled) return
    check(setEnabled(enabled) == AudioEffect.SUCCESS) { "Effect refused setEnabled($enabled)" }
}

private class VoiceBoost(private val effect: LoudnessEnhancer) : AudioEffectHandle {
    override fun apply(settings: AudioEffectsSettings) { effect.setTargetGain(AudioEffectsSettings.VOICE_BOOST_GAIN_MB) }
    override fun setEnabled(enabled: Boolean) = effect.enable(enabled)
    override fun release() = effect.release()
}

private class PresetEqualiser(private val effect: Equalizer) : AudioEffectHandle {
    override fun apply(settings: AudioEffectsSettings) {
        val bands = effect.numberOfBands.toInt()
        val range = effect.bandLevelRange
        val centres = (0 until bands).map { effect.getCenterFreq(it.toShort()) / 1000 }
        equaliserLevels(settings.preset, centres, range[0].toInt(), range[1].toInt()).forEachIndexed { band, level ->
            effect.setBandLevel(band.toShort(), level.toShort())
        }
    }
    override fun setEnabled(enabled: Boolean) = effect.enable(enabled)
    override fun release() = effect.release()
}

/** The `EFFECTS` session command's arguments. */
internal fun AudioEffectsSettings.toBundle(): Bundle = Bundle().apply {
    putBoolean("enabled", enabled); putBoolean("voiceBoost", voiceBoost); putString("preset", preset.name)
}

internal fun audioEffectsSettings(args: Bundle): AudioEffectsSettings = AudioEffectsSettings(
    enabled = args.getBoolean("enabled"),
    voiceBoost = args.getBoolean("voiceBoost"),
    preset = runCatching { EqualiserPreset.valueOf(args.getString("preset").orEmpty()) }.getOrDefault(EqualiserPreset.FLAT),
)
