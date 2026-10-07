package org.johnfegan.plextouch.player

import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.AudioEffectsSettings
import org.johnfegan.plextouch.data.EqualiserPreset
import org.johnfegan.plextouch.data.equaliserLevels
import org.johnfegan.plextouch.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectsTest {
    private val on = AudioEffectsSettings(enabled = true, voiceBoost = true, preset = EqualiserPreset.SPEECH)
    private val all = EffectCapabilities(voiceBoost = true, equaliser = true)

    private class FakeHandle(val kind: AudioEffectKind, val session: Int) : AudioEffectHandle {
        var on = false
        var released = false
        var applied: AudioEffectsSettings? = null
        override fun apply(settings: AudioEffectsSettings) { applied = settings }
        override fun setEnabled(enabled: Boolean) { on = enabled }
        override fun release() { released = true }
    }

    private class FakeFactory(var capabilities: EffectCapabilities, val refuse: Set<AudioEffectKind> = emptySet()) : AudioEffectFactory {
        val created = mutableListOf<FakeHandle>()
        var attempts = 0
        var probes = 0
        override fun capabilities(): EffectCapabilities { probes++; return capabilities }
        override fun create(kind: AudioEffectKind, audioSessionId: Int): AudioEffectHandle {
            attempts++
            if (kind in refuse) throw UnsupportedOperationException("no $kind")
            return FakeHandle(kind, audioSessionId).also { created += it }
        }
    }

    @Test fun planIsEmptyWhileTheMasterSwitchIsOff() {
        val plan = audioEffectsPlan(on.copy(enabled = false), all)
        assertTrue(plan.active.isEmpty())
        assertTrue(plan.unsupported.isEmpty())
        assertNull(plan.notice)
    }

    @Test fun flatEqualiserIsNoEqualiser() {
        assertEquals(setOf(AudioEffectKind.VOICE_BOOST), audioEffectsPlan(on.copy(preset = EqualiserPreset.FLAT), all).active)
    }

    @Test fun missingCapabilityIsReportedNotAttempted() {
        val plan = audioEffectsPlan(on, EffectCapabilities(voiceBoost = false, equaliser = true))
        assertEquals(setOf(AudioEffectKind.EQUALISER), plan.active)
        assertEquals(setOf(AudioEffectKind.VOICE_BOOST), plan.unsupported)
        assertEquals(R.string.effects_unsupported_voice, (plan.notice as UiText.Res).id)
        assertEquals(R.string.effects_unsupported_all, (audioEffectsPlan(on, EffectCapabilities.NONE).notice as UiText.Res).id)
        assertEquals(R.string.effects_unsupported_equaliser, (effectsNotice(setOf(AudioEffectKind.EQUALISER)) as UiText.Res).id)
    }

    @Test fun unchosenEffectsAreNeverReportedAsUnsupported() {
        assertTrue(audioEffectsPlan(on.copy(voiceBoost = false, preset = EqualiserPreset.FLAT), EffectCapabilities.NONE).unsupported.isEmpty())
    }

    @Test fun noAudioSessionAttachesNothing() {
        assertTrue(audioEffectsPlan(on, all, audioSessionId = NO_AUDIO_SESSION).active.isEmpty())
    }

    @Test fun attachesTheChosenEffectsToThePlayersSession() {
        val factory = FakeFactory(all)
        val controller = AudioEffectsController(factory) { }
        controller.update(on)
        assertEquals(0, factory.attempts) // no session yet
        controller.attach(42)
        assertEquals(2, factory.created.size)
        assertTrue(factory.created.all { it.session == 42 && it.on && it.applied == on })
    }

    @Test fun creationFailureIsReportedOnceAndNeverRetried() {
        val factory = FakeFactory(all, refuse = setOf(AudioEffectKind.EQUALISER))
        val reports = mutableListOf<Set<AudioEffectKind>>()
        val controller = AudioEffectsController(factory, reports::add)
        controller.update(on)
        controller.attach(7)
        assertEquals(listOf(setOf(AudioEffectKind.EQUALISER)), reports)
        val attempts = factory.attempts
        // More settings changes, a new audio session: the failed effect is not tried again and nothing is re-reported.
        controller.update(on.copy(preset = EqualiserPreset.BASS_CUT))
        controller.attach(8)
        controller.update(on.copy(preset = EqualiserPreset.TREBLE_LIFT))
        assertEquals(listOf(setOf(AudioEffectKind.EQUALISER)), reports)
        assertEquals(attempts + 1, factory.attempts) // only Voice boost was re-created, for session 8
        assertEquals(1, factory.probes)
        assertEquals(setOf(AudioEffectKind.VOICE_BOOST), controller.state.active)
    }

    @Test fun unsupportedCapabilityIsNeverCreated() {
        val factory = FakeFactory(EffectCapabilities.NONE)
        val reports = mutableListOf<Set<AudioEffectKind>>()
        val controller = AudioEffectsController(factory, reports::add)
        controller.attach(3)
        controller.update(on)
        controller.update(on)
        assertEquals(0, factory.attempts)
        assertEquals(listOf(setOf(AudioEffectKind.VOICE_BOOST, AudioEffectKind.EQUALISER)), reports)
    }

    @Test fun throwingCapabilityProbeMeansNothingIsSupported() {
        val factory = object : AudioEffectFactory {
            override fun capabilities(): EffectCapabilities = throw RuntimeException("audiofx unavailable")
            override fun create(kind: AudioEffectKind, audioSessionId: Int): AudioEffectHandle = error("must not be called")
        }
        val controller = AudioEffectsController(factory) { }
        controller.attach(3)
        controller.update(on)
        assertEquals(setOf(AudioEffectKind.VOICE_BOOST, AudioEffectKind.EQUALISER), controller.state.unsupported)
    }

    @Test fun switchingOffDisablesEveryEffectAtOnce() {
        val factory = FakeFactory(all)
        val controller = AudioEffectsController(factory) { }
        controller.attach(5)
        controller.update(on)
        controller.update(on.copy(enabled = false))
        assertTrue(factory.created.all { !it.on && !it.released })
        assertTrue(controller.state.active.isEmpty())
        // Back on: the same effects are re-enabled, not re-created.
        controller.update(on)
        assertEquals(2, factory.created.size)
        assertTrue(factory.created.all { it.on })
    }

    @Test fun aNewAudioSessionReleasesAndReattaches() {
        val factory = FakeFactory(all)
        val controller = AudioEffectsController(factory) { }
        controller.update(on)
        controller.attach(5)
        val first = factory.created.toList()
        controller.attach(5) // same session: nothing happens
        assertEquals(2, factory.created.size)
        controller.attach(9)
        assertTrue(first.all { it.released })
        assertEquals(listOf(9, 9), factory.created.drop(2).map { it.session })
        controller.release()
        assertTrue(factory.created.all { it.released })
    }

    @Test fun equaliserLevelsFollowThePresetAndTheDeviceRange() {
        val centres = listOf(60, 230, 910, 3_600, 14_000)
        assertEquals(listOf(0, 0, 0, 0, 0), equaliserLevels(EqualiserPreset.FLAT, centres, -1500, 1500))
        assertEquals(listOf(-600, -300, 0, 500, 100), equaliserLevels(EqualiserPreset.SPEECH, centres, -1500, 1500))
        assertEquals(listOf(-400, -300, 0, 400, 100), equaliserLevels(EqualiserPreset.SPEECH, centres, -400, 400))
        // Bands between anchors interpolate; bands outside them take the nearest anchor.
        val between = equaliserLevels(EqualiserPreset.SPEECH, listOf(30, 1_800, 20_000), -1500, 1500)
        assertEquals(-600, between[0])
        assertTrue(between[1] in 1..499)
        assertEquals(100, between[2])
    }
}
