package org.johnfegan.plextouch.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 140's source guard. Compose UI tests cannot run offline (no ui-test-junit4 in the cache), so these checks read the
 * Compose sources instead and fail on the regressions the TalkBack pass fixed. Unit tests run with the module directory as
 * the working directory.
 */
class AccessibilitySourceGuardTest {
    private val roots = listOf("src/main/java/org/johnfegan/plextouch", "src/personal/java/org/johnfegan/plextouch", "src/public/java/org/johnfegan/plextouch")
    private val sources: Map<String, String> by lazy {
        roots.map(::File).filter(File::isDirectory).flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
            .associate { it.path to it.readText() }
    }
    private val composeSources get() = sources.filterValues { "@Composable" in it }

    @Test fun theGuardReadsTheComposeScreens() {
        val names = composeSources.keys.map { File(it).name }
        listOf("PlayerScreens.kt", "LibraryScreens.kt", "ArtistScreens.kt", "SettingsScreen.kt", "MainActivity.kt").forEach { assertTrue(it, it in names) }
    }

    @Test fun iconOnlyButtonsAreLabelled() {
        // `IconButton(...) { Icon(icon, null` is an icon-only control TalkBack would read as "Button" alone.
        val unlabelled = Regex("""IconButton\([^{}]*\)\s*\{\s*Icon\([^,()]+(\([^()]*\))?,\s*null""")
        assertEquals(emptyList<String>(), findings(unlabelled))
    }

    @Test fun clickableRowsSayWhatTheyDo() {
        // Every clickable names its action ("Open", "Play") or role, so TalkBack does not say a bare "Double-tap to activate".
        // The A–Z letters are exempt: the rail clears them and is one adjustable control instead.
        val bare = composeSources.flatMap { (path, text) ->
            text.lines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//").takeUnless { it.trimStart().startsWith("import ") }.orEmpty()
                if (".clickable" in code && "onClickLabel" !in code && "role =" !in code && "jumpTo(letter)" !in code) "${File(path).name}:${index + 1}" else null
            }
        }
        assertEquals(emptyList<String>(), bare)
    }

    @Test fun buttonsGrowWithTheirText() {
        // A fixed height clips a button's label at large font sizes; buttons take heightIn(min = …) instead.
        val fixed = Regex("""Button\([^\n]*\.height\(\d+\.dp\)""")
        assertEquals(emptyList<String>(), findings(fixed))
    }

    @Test fun coversInsideRowsAreNotReadTwice() {
        // A cover is labelled only where it stands alone (album and player screens); in rows and tiles the title is read.
        val labelled = composeSources.values.sumOf { Regex("""labelled = true""").findAll(it).count() }
        assertEquals(2, labelled)
        val artwork = sources.entries.first { it.key.endsWith("ui/Artwork.kt") }.value
        assertTrue(artwork.contains("contentDescription = if (labelled) stringResource(R.string.cover_of, album.title) else null"))
    }

    @Test fun screenTitlesAreHeadings() {
        val headings = composeSources.mapValues { (_, text) -> Regex("""asHeading\(\)|heading = true""").findAll(text).count() }
        listOf("PlayerScreens.kt", "LibraryScreens.kt", "ArtistScreens.kt", "CollectionScreens.kt", "DownloadScreens.kt", "SettingsScreen.kt",
            "HistoryScreen.kt", "StorageScreen.kt", "PlaylistScreens.kt", "SoundControls.kt", "PlexHomeSettings.kt", "ConnectionDiagnostics.kt", "MainActivity.kt",
        ).forEach { name -> assertTrue("$name has no heading", (headings.entries.firstOrNull { File(it.key).name == name }?.value ?: 0) > 0) }
    }

    private fun findings(pattern: Regex): List<String> = composeSources.flatMap { (path, text) ->
        pattern.findAll(text).map { match -> "${File(path).name}:${text.substring(0, match.range.first).count { it == '\n' } + 1}" }.toList()
    }
}
