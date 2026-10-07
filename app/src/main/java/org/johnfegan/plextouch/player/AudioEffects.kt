package org.johnfegan.plextouch.player

import androidx.compose.runtime.Immutable
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.AudioEffectsSettings
import org.johnfegan.plextouch.data.EqualiserPreset
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText

/*
 * Ticket 135: optional sound processing for phone playback.
 *
 * Effects are Android `audiofx` objects attached to the ExoPlayer's audio session inside `PlexPlaybackService`, so they
 * only ever shape what this phone renders. They never touch the bytes: downloads on disk and Plex's files are read
 * unchanged, and a Sonos handoff streams straight from Plex to the speaker, outside this audio session.
 *
 * Everything in this file is plain Kotlin so the decisions (what to enable, what to report, when to re-attach) are unit
 * tested; `PlatformAudioEffects` is the thin Android adapter.
 */

enum class AudioEffectKind { VOICE_BOOST, EQUALISER }

/** What the device's effect library offers, from `AudioEffect.queryEffects()`. */
@Immutable
data class EffectCapabilities(val voiceBoost: Boolean, val equaliser: Boolean) {
    fun supports(kind: AudioEffectKind): Boolean = when (kind) {
        AudioEffectKind.VOICE_BOOST -> voiceBoost
        AudioEffectKind.EQUALISER -> equaliser
    }

    companion object { val NONE = EffectCapabilities(voiceBoost = false, equaliser = false) }
}

/** The effects that should be processing now, and the wanted ones this device cannot provide. */
@Immutable
data class EffectsState(val active: Set<AudioEffectKind> = emptySet(), val unsupported: Set<AudioEffectKind> = emptySet()) {
    val notice: UiText? get() = effectsNotice(unsupported)
}

/** The effects the settings ask for; the master switch off means none. A flat equaliser is no equaliser. */
fun wantedEffects(settings: AudioEffectsSettings): Set<AudioEffectKind> = if (!settings.enabled) emptySet() else buildSet {
    if (settings.voiceBoost) add(AudioEffectKind.VOICE_BOOST)
    if (settings.preset != EqualiserPreset.FLAT) add(AudioEffectKind.EQUALISER)
}

/**
 * Decides what to run: wanted effects the device lacks, or that already failed to start, are reported as unsupported
 * and never attempted again. Without an audio session (`C.AUDIO_SESSION_ID_UNSET`, 0) nothing is attached: session 0
 * is the global output mix, which an app must not process.
 */
fun audioEffectsPlan(
    settings: AudioEffectsSettings,
    capabilities: EffectCapabilities,
    failed: Set<AudioEffectKind> = emptySet(),
    audioSessionId: Int = 1,
): EffectsState {
    val wanted = wantedEffects(settings)
    val unsupported = wanted.filterTo(mutableSetOf()) { !capabilities.supports(it) || it in failed }
    return EffectsState(active = if (audioSessionId == NO_AUDIO_SESSION) emptySet() else wanted - unsupported, unsupported = unsupported)
}

/** The one line shown under the sound controls when something the listener chose cannot run here. */
fun effectsNotice(unsupported: Set<AudioEffectKind>): UiText? = when {
    unsupported.isEmpty() -> null
    unsupported.size > 1 -> uiText(R.string.effects_unsupported_all)
    AudioEffectKind.VOICE_BOOST in unsupported -> uiText(R.string.effects_unsupported_voice)
    else -> uiText(R.string.effects_unsupported_equaliser)
}

/** One live platform effect. Any method may throw; the controller treats that as "unsupported here". */
interface AudioEffectHandle {
    fun apply(settings: AudioEffectsSettings)
    fun setEnabled(enabled: Boolean)
    fun release()
}

interface AudioEffectFactory {
    fun capabilities(): EffectCapabilities
    /** Throws when the platform refuses the effect for this session. */
    fun create(kind: AudioEffectKind, audioSessionId: Int): AudioEffectHandle
}

/**
 * Keeps the platform effects in line with the settings and the player's audio session. Main-thread only.
 *
 * - Off is instant: effects leaving the plan are `setEnabled(false)` at once and kept, so switching back is cheap.
 * - A failure (missing from `queryEffects()`, a throwing constructor, a refused `setEnabled`) marks that effect failed
 *   for the life of this controller: it is reported through [report] and never retried, so a broken effect library
 *   cannot cause a retry loop or interrupt playback. The next service start probes again.
 * - [report] is called only when the unsupported set changes.
 * - A new audio session releases every effect and re-attaches the current plan to the new session.
 */
class AudioEffectsController(private val factory: AudioEffectFactory, private val report: (Set<AudioEffectKind>) -> Unit) {
    private val capabilities by lazy { try { factory.capabilities() } catch (_: Exception) { EffectCapabilities.NONE } }
    private var settings = AudioEffectsSettings()
    private var audioSessionId = NO_AUDIO_SESSION
    private val handles = mutableMapOf<AudioEffectKind, AudioEffectHandle>()
    private val failed = mutableSetOf<AudioEffectKind>()
    private var reported: Set<AudioEffectKind> = emptySet()
    var state = EffectsState(); private set

    fun update(settings: AudioEffectsSettings) { this.settings = settings; reconcile() }

    fun attach(audioSessionId: Int) {
        if (audioSessionId == this.audioSessionId) return
        releaseHandles()
        this.audioSessionId = audioSessionId
        reconcile()
    }

    fun release() { releaseHandles(); audioSessionId = NO_AUDIO_SESSION }

    private fun reconcile() {
        val plan = audioEffectsPlan(settings, capabilities, failed, audioSessionId)
        handles.filterKeys { it !in plan.active }.values.forEach { quietly { it.setEnabled(false) } }
        for (kind in plan.active) {
            try {
                val handle = handles[kind] ?: factory.create(kind, audioSessionId).also { handles[kind] = it }
                handle.apply(settings)
                handle.setEnabled(true)
            } catch (_: Exception) {
                failed += kind
                handles.remove(kind)?.let { quietly { it.release() } }
            }
        }
        state = audioEffectsPlan(settings, capabilities, failed, audioSessionId)
        if (state.unsupported != reported) { reported = state.unsupported; report(reported) }
    }

    private fun releaseHandles() {
        handles.values.forEach { quietly { it.release() } }
        handles.clear()
    }

    private inline fun quietly(block: () -> Unit) { try { block() } catch (_: Exception) { } }
}

/** `C.AUDIO_SESSION_ID_UNSET`, kept here so the plan has no Media3 dependency. */
const val NO_AUDIO_SESSION = 0
