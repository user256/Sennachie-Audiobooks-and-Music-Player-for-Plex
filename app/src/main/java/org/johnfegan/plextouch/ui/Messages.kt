package org.johnfegan.plextouch.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.johnfegan.plextouch.R

/**
 * Which screen or dialog owns a message. Each renders only its own slot, so a failed library load never appears in the
 * playlist dialog and a success never lands in an error slot (successes go out as transient notices instead).
 */
enum class Feature { SETUP, LIBRARY, ALBUM, PLAYLIST, METADATA, DOWNLOAD, SPEAKER }

fun PlexTouchUiState.withMessage(feature: Feature, text: UiText?): PlexTouchUiState =
    copy(messages = if (text == null) messages - feature else messages + (feature to text))

fun PlexTouchUiState.withoutMessages(vararg features: Feature): PlexTouchUiState = copy(messages = messages - features.toSet())

/**
 * Labelled background work. It keeps the shared loading flag and reports a failure to one feature only, so starting a load
 * clears that feature's previous error and nobody else's. A failure reads "<label> failed: <reason>", the reason being the
 * exception's own [UserFacing] text, else the platform's message, else a connection hint.
 */
class FeatureJobs(private val scope: CoroutineScope, private val state: MutableStateFlow<PlexTouchUiState>) {
    private var active = 0

    fun launch(label: UiText, feature: Feature, work: suspend () -> Unit): Job = scope.launch {
        active++
        state.update { it.withMessage(feature, null).copy(loading = true) }
        try {
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.update { it.withMessage(feature, uiText(R.string.job_failed, label, error.userText(uiText(R.string.job_failed_fallback)))) }
        } finally {
            active--
            state.update { it.copy(loading = active > 0) }
        }
    }
}
