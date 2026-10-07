package org.johnfegan.plextouch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutTest {
    private val secrets = listOf("server-token-secret", "account-token-secret", "PINCODE", "https://plex.example:32400")
    private val before = mapOf(
        "server_url" to "https://plex.example:32400",
        "token" to "server-token-secret",
        "plex_account_token" to "account-token-secret",
        "pending_pin_id" to 42L,
        "pending_pin_code" to "PINCODE",
        "pending_pin_expiry" to 1_700_000_000_000L,
        "audiobook_timeline_sync" to """{"events":[{"scope":"abc","server":"https://plex.example:32400"}],"baselines":[]}""",
        "plex_client_identifier" to "device-uuid",
        "smart_rewind_paused_at" to """{"scope":"abc","server":"https://plex.example:32400","albumId":"10","trackId":"11","positionMs":5000}""",
        "smart_rewind_seconds" to 10,
        "listening_history" to """[{"server":"https://plex.example:32400","album":{"id":"10"}}]""",
        "speed_AUDIOBOOK" to 1.25f,
        "speed_MUSIC" to 1f,
        "book_speeds" to """[{"scope":"abc","server":"https://plex.example:32400","albumId":"10","speed":1.5,"usedAt":1}]""",
        "library_AUDIOBOOK" to "3",
        "library_MUSIC" to "1",
        "setup_plan" to "BOTH",
        "mode" to "AUDIOBOOK",
        "offline_only" to false,
        "sonos_speakers" to """[{"name":"Kitchen","location":"http://192.168.1.53:1400/xml/device_description.xml"}]""",
    )

    @Test fun signOutRemovesEveryPlexCredentialAndTheOutbox() {
        val kept = PlexStore.keptAfterSignOut(before)
        assertTrue(kept.keys.none { it == "token" || it.contains("account_token") || it.contains("pin") || it.contains("timeline") || it == "server_url" || it == "smart_rewind_paused_at" })
        // Only the history may still mention the server address; no token or PIN survives in any value.
        kept.filterKeys { it != "listening_history" && it != "book_speeds" }.values.forEach { value ->
            secrets.forEach { secret -> assertFalse("$value leaks $secret", value.toString().contains(secret)) }
        }
        assertFalse(kept.values.any { it.toString().contains("server-token-secret") || it.toString().contains("account-token-secret") || it.toString().contains("PINCODE") })
    }

    @Test fun signOutKeepsDownloadsHistorySpeakersLibrariesAndSpeeds() {
        val kept = PlexStore.keptAfterSignOut(before)
        assertEquals(
            setOf("plex_client_identifier", "listening_history", "speed_AUDIOBOOK", "speed_MUSIC", "book_speeds", "library_AUDIOBOOK", "library_MUSIC", "smart_rewind_seconds", "mode", "offline_only", "sonos_speakers"),
            kept.keys,
        )
        assertEquals(before["listening_history"], kept["listening_history"])
        assertEquals(before["sonos_speakers"], kept["sonos_speakers"])
        assertEquals(1.25f, kept["speed_AUDIOBOOK"])
        assertEquals("per-book speeds are account-scoped, so they stay", before["book_speeds"], kept["book_speeds"])
        assertEquals("3", kept["library_AUDIOBOOK"])
        assertEquals(before.size - PlexStore.SIGN_OUT_KEYS.size, kept.size)
    }
}
