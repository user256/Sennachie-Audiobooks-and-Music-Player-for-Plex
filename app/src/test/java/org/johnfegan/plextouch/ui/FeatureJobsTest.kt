package org.johnfegan.plextouch.ui

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.johnfegan.plextouch.R

class FeatureJobsTest {
    private val libraryError = uiText(R.string.job_failed, uiText(R.string.job_loading_music), UiText.Raw("offline"))
    private val loadingDune = uiText(R.string.job_loading_title, "Dune")

    @Test fun aFailedLoadReportsToItsOwnFeatureAndKeepsAnotherFeaturesError() = runBlocking {
        val state = MutableStateFlow(PlexTouchUiState(messages = mapOf(Feature.LIBRARY to libraryError)))
        FeatureJobs(this, state).launch(loadingDune, Feature.ALBUM) { throw IOException("timed out") }.join()
        assertEquals(libraryError, state.value.libraryError)
        // A platform exception's own message is shown as it is; we cannot translate it.
        assertEquals(uiText(R.string.job_failed, loadingDune, UiText.Raw("timed out")), state.value.albumError)
    }

    @Test fun ourOwnExceptionTextIsShownAsTheReason() = runBlocking {
        val state = MutableStateFlow(PlexTouchUiState())
        val reason = uiText(R.string.server_unreachable)
        FeatureJobs(this, state).launch(loadingDune, Feature.ALBUM) { throw InvalidStateException(reason) }.join()
        assertEquals(uiText(R.string.job_failed, loadingDune, reason), state.value.albumError)
        assertFalse(state.value.loading)
    }

    @Test fun startingALoadClearsOnlyThatFeaturesPreviousError() = runBlocking {
        val stale = UiText.Raw("stale")
        val state = MutableStateFlow(PlexTouchUiState(messages = mapOf(Feature.LIBRARY to libraryError, Feature.ALBUM to UiText.Raw("Loading Emma failed: 404"), Feature.PLAYLIST to stale)))
        FeatureJobs(this, state).launch(loadingDune, Feature.ALBUM) {
            assertNull(state.value.albumError)
            assertEquals(libraryError, state.value.libraryError)
            assertEquals(stale, state.value.playlistError)
            assertTrue(state.value.loading)
        }.join()
        assertEquals(mapOf(Feature.LIBRARY to libraryError, Feature.PLAYLIST to stale), state.value.messages)
    }

    @Test fun aFailureWithoutAMessageFallsBackToTheConnectionHint() = runBlocking {
        val state = MutableStateFlow(PlexTouchUiState())
        FeatureJobs(this, state).launch(uiText(R.string.job_checking_libraries), Feature.LIBRARY) { throw IOException() }.join()
        assertEquals(uiText(R.string.job_failed, uiText(R.string.job_checking_libraries), uiText(R.string.job_failed_fallback)), state.value.libraryError)
    }

    @Test fun loadingStaysOnUntilTheLastJobFinishes() = runBlocking {
        val state = MutableStateFlow(PlexTouchUiState())
        val jobs = FeatureJobs(this, state)
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val a = jobs.launch(uiText(R.string.job_loading_music), Feature.LIBRARY) { first.await() }
        val b = jobs.launch(uiText(R.string.job_loading_playlists), Feature.PLAYLIST) { second.await() }
        yield()
        assertTrue(state.value.loading)
        first.complete(Unit); a.join()
        assertTrue("one job is still running", state.value.loading)
        second.complete(Unit); b.join()
        assertFalse(state.value.loading)
    }

    @Test fun messagesAreReplacedPerFeatureAndRemovedWhenNull() {
        val chooseServer = uiText(R.string.setup_choose_server)
        val noSpeakers = uiText(R.string.speakers_none_found)
        val state = PlexTouchUiState().withMessage(Feature.SETUP, chooseServer).withMessage(Feature.SPEAKER, noSpeakers)
        assertEquals(chooseServer, state.setupMessage)
        assertEquals(noSpeakers, state.speakerError)
        val cleared = state.withMessage(Feature.SETUP, null)
        assertNull(cleared.setupMessage)
        assertEquals(noSpeakers, cleared.speakerError)
        assertTrue(cleared.withoutMessages(Feature.SPEAKER, Feature.LIBRARY).messages.isEmpty())
    }
}
