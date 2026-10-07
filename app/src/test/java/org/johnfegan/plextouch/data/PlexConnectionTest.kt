package org.johnfegan.plextouch.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlexConnectionTest {
    @Test
    fun `stream urls retain paths and encode a token`() {
        val connection = PlexConnection("http://plex.local:32400/", "a token&value")
        assertEquals(
            "http://plex.local:32400/library/parts/1/file.mp3?X-Plex-Token=a+token%26value",
            connection.streamUrl("/library/parts/1/file.mp3"),
        )
    }

    @Test
    fun `Plex link sign in opens the device linking page`() {
        assertEquals("https://plex.tv/link", PlexAuthClient("phone-id").linkUrl())
    }
}
