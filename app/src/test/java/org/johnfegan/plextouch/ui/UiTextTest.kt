package org.johnfegan.plextouch.ui

import java.io.IOException
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.DownloadAlbum
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexServer
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.sonos.discoverSpeakers
import org.johnfegan.plextouch.sonos.speakerDescriptionUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextTest {
    @Test fun failureTextPrefersOurOwnTextThenThePlatformMessageThenTheFallback() {
        val fallback = uiText(R.string.job_failed_fallback)
        assertEquals(uiText(R.string.server_unreachable), InvalidStateException(uiText(R.string.server_unreachable)).userText(fallback))
        assertEquals(UiText.Raw("No route"), IOException("No route").userText(fallback))
        assertEquals(fallback, IOException().userText(fallback))
        assertEquals(fallback, IOException(" ").userText(fallback))
    }

    @Test fun requireAndCheckKeepTheirExceptionTypesAndCarryTheText() {
        val input = assertThrows(IllegalArgumentException::class.java) { requireText(false) { uiText(R.string.playlist_name_required) } }
        assertEquals(uiText(R.string.playlist_name_required), (input as UserFacing).text)
        val state = assertThrows(IllegalStateException::class.java) { checkText(false) { uiText(R.string.connection_not_saved) } }
        assertEquals(uiText(R.string.connection_not_saved), (state as UserFacing).text)
        requireText(true) { error("not evaluated") }
        checkText(true) { error("not evaluated") }
    }

    @Test fun speakerAddressValidationExplainsItselfFromResources() {
        val malformed = assertThrows(IllegalArgumentException::class.java) { speakerDescriptionUrl("speaker.local") }
        assertEquals(uiText(R.string.speaker_ip_invalid), (malformed as UserFacing).text)
        val public = assertThrows(IllegalArgumentException::class.java) { speakerDescriptionUrl("8.8.8.8") }
        assertEquals(uiText(R.string.speaker_ip_private), (public as UserFacing).text)
    }

    @Test fun discoveryNoticesNameTheDirectoryAsAnArgument() {
        val directory = UiText.Raw("Home")
        assertEquals(uiText(R.string.speakers_none_found), discoverSpeakers({ emptyList() }, null).notice)
        assertEquals(uiText(R.string.speakers_directory_empty, directory), discoverSpeakers({ emptyList() }, { emptyList() }, fallbackName = directory).notice)
        assertEquals(uiText(R.string.speakers_directory_unavailable, directory), discoverSpeakers({ emptyList() }, { throw IOException("private") }, fallbackName = directory).notice)
        assertEquals(uiText(R.string.speakers_directory_unavailable, uiText(R.string.speaker_directory_default)), discoverSpeakers({ emptyList() }, { throw IOException() }).notice)
    }

    @Test fun downloadLabelsCountTheSavedFiles() {
        val tracks = listOf(PlexTrack("1", "One", "A", "B", 1, "/1", "mp3"), PlexTrack("2", "Two", "A", "B", 1, "/2", "mp3"))
        val record = DownloadAlbum("server", "lib", LibraryMode.MUSIC, PlexAlbum("9", "B", "A", null, 2), emptyList())
        fun status(completed: Int, failed: Int = 0, waiting: Int = 0, bytes: Long = 0) = DownloadStatus(record, tracks, null, completed, failed, waiting, bytes, 0)
        assertEquals(uiText(R.string.download_ready, uiText(R.string.size_mb, 2.0)), status(2, bytes = 2_097_152).label)
        assertEquals(uiPlural(R.plurals.download_failed_some, 1, 1, 2, 1), status(1, failed = 1).label)
        assertEquals(uiPlural(R.plurals.download_waiting, 0, 0, 2), status(0, waiting = 2).label)
        assertEquals(uiPlural(R.plurals.download_progress, 1, 1, 2, uiText(R.string.size_gb, 1.0)), status(1, bytes = 1_073_741_824).label)
    }

    @Test fun anUnnamedServerReadsAsPlexServer() {
        assertEquals(uiText(R.string.plex_server_default), serverName(PlexServer(" ", listOf(PlexConnection("http://a", "t")))))
        assertEquals(UiText.Raw("Den"), serverName(PlexServer("Den", emptyList())))
    }

    @Test fun pluralsDefaultToTheCountAsTheirOnlyArgument() {
        assertEquals(UiText.Plural(R.plurals.search_results, 3, listOf(3)), uiPlural(R.plurals.search_results, 3))
        assertTrue(uiText(R.string.tab_home) is UiText.Res)
    }
}
