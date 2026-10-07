package org.johnfegan.plextouch.widget

import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.ui.LibraryTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShortcutsTest {
    @Test fun mapsEachShortcutNameAndIgnoresAnythingElse() {
        assertEquals(AppShortcut.RESUME_AUDIOBOOK, shortcutFor(ACTION_SHORTCUT, "resume_audiobook"))
        assertEquals(AppShortcut.OPEN_MUSIC, shortcutFor(ACTION_SHORTCUT, "open_music"))
        assertEquals(AppShortcut.DOWNLOADS, shortcutFor(ACTION_SHORTCUT, "downloads"))
        assertNull(shortcutFor(ACTION_SHORTCUT, "https://plex.example/library?X-Plex-Token=secret"))
        assertNull(shortcutFor(ACTION_SHORTCUT, null))
        assertNull(shortcutFor("android.intent.action.MAIN", "downloads"))
        assertNull(shortcutFor(null, null))
    }

    @Test fun shortcutIdsAreStableNamesWithNoUrlOrToken() {
        AppShortcut.entries.forEach { assertTrue(it.id.matches(Regex("[a-z_]+"))) }
    }

    @Test fun signedOutEveryShortcutJustOpensTheApp() {
        AppShortcut.entries.forEach { assertNull(shortcutPlan(it, signedIn = false)) }
    }

    @Test fun signedInPlansSwitchModeAndTab() {
        assertEquals(ShortcutPlan(LibraryMode.AUDIOBOOK, LibraryTab.HOME, resume = true), shortcutPlan(AppShortcut.RESUME_AUDIOBOOK, true))
        assertEquals(ShortcutPlan(LibraryMode.MUSIC, LibraryTab.LIBRARY, resume = false), shortcutPlan(AppShortcut.OPEN_MUSIC, true))
        val downloads = shortcutPlan(AppShortcut.DOWNLOADS, true)!!
        assertNull(downloads.mode)
        assertEquals(LibraryTab.DOWNLOADS, downloads.tab)
        assertFalse(downloads.resume)
    }
}
