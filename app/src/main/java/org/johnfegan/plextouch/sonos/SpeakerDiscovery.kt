package org.johnfegan.plextouch.sonos

import java.util.concurrent.CancellationException
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText

data class SpeakerDiscovery(val speakers: List<SonosSpeaker>, val notice: UiText? = null)

/** An empty/failed multicast probe is normal on some routers and VPN routes. */
fun discoverSpeakers(
    local: () -> List<SonosSpeaker>,
    fallback: (() -> List<SonosSpeaker>)? = null,
    preferFallback: Boolean = false,
    fallbackName: UiText = uiText(R.string.speaker_directory_default),
): SpeakerDiscovery {
    if (!preferFallback) {
        val nearby = try { local() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        if (nearby.isNotEmpty()) return SpeakerDiscovery(nearby)
    }
    if (fallback == null) return SpeakerDiscovery(emptyList(), uiText(R.string.speakers_none_found))
    return try {
        val speakers = fallback()
        SpeakerDiscovery(speakers, uiText(if (speakers.isEmpty()) R.string.speakers_directory_empty else R.string.speakers_directory_found, fallbackName))
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) {
        SpeakerDiscovery(emptyList(), uiText(R.string.speakers_directory_unavailable, fallbackName))
    }
}
