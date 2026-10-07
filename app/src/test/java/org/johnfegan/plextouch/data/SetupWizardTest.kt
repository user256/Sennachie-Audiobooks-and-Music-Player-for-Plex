package org.johnfegan.plextouch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupWizardTest {
    @Test fun `a new setup starts with Plex sign in`() {
        assertEquals(SetupStep.SIGN_IN, setupStep(SetupPlan.MUSIC, connected = false, hasServers = false, musicLibraryId = null, audiobookLibraryId = null))
    }

    @Test fun `approved Plex account moves to server selection`() {
        assertEquals(SetupStep.SERVER, setupStep(SetupPlan.BOTH, connected = false, hasServers = true, musicLibraryId = null, audiobookLibraryId = null))
    }

    @Test fun `both libraries are selected in music then audiobook order`() {
        assertEquals(SetupStep.MUSIC_LIBRARY, setupStep(SetupPlan.BOTH, connected = true, hasServers = false, musicLibraryId = null, audiobookLibraryId = null))
        assertEquals(SetupStep.AUDIOBOOK_LIBRARY, setupStep(SetupPlan.BOTH, connected = true, hasServers = false, musicLibraryId = "music", audiobookLibraryId = null))
        assertNull(setupStep(SetupPlan.BOTH, connected = true, hasServers = false, musicLibraryId = "music", audiobookLibraryId = "books"))
    }

    @Test fun `single choices finish at their matching library and music is the preferred destination`() {
        assertEquals(SetupStep.MUSIC_LIBRARY, setupStep(SetupPlan.MUSIC, connected = true, hasServers = false, musicLibraryId = null, audiobookLibraryId = null))
        assertEquals(LibraryMode.MUSIC, SetupPlan.BOTH.destination)
        assertEquals(LibraryMode.AUDIOBOOK, SetupPlan.AUDIOBOOKS.destination)
    }
}
