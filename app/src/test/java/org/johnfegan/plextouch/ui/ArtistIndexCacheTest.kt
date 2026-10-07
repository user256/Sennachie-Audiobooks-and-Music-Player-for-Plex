package org.johnfegan.plextouch.ui

import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistIndexCacheTest {
    private val unknown = "Unknown artist"
    private val scope = ArtistIndexScope("https://plex.example", "account-a", "7", LibraryMode.MUSIC, offlineOnly = false)
    private val blue = PlexAlbum("1", "Blue Train", "John Coltrane", 1957, 5)
    private val love = blue.copy(id = "2", title = "A Love Supreme")
    private val miles = blue.copy(id = "3", title = "Kind of Blue", artist = "Miles Davis")
    private val catalogue = listOf(blue, love, miles)

    private var groupings = 0
    private val cache = ArtistIndexCache(maxScopes = 3) { albums, name -> groupings++; groupArtists(albums, name) }

    @Test fun anUnchangedCatalogueIsGroupedOnceEvenAsANewList() {
        val first = cache.index(scope, catalogue, unknown)
        assertSame(first, cache.index(scope, catalogue, unknown))
        assertSame(first, cache.index(scope, catalogue.toList(), unknown))
        assertEquals(1, groupings)
        assertEquals(listOf("John Coltrane", "Miles Davis"), first.artists.map { it.name })
        assertEquals(mapOf("J" to 0, "M" to 1), first.positions)
    }

    @Test fun albumArtistAndRatingChangesRegroup() {
        val first = cache.index(scope, catalogue, unknown)
        val added = cache.index(scope, catalogue + blue.copy(id = "4", title = "Giant Steps"), unknown)
        assertEquals(2, groupings)
        assertEquals(3, added.artists.first().albums.size)
        val renamed = cache.index(scope, listOf(blue, love, miles.copy(artist = "Davis, Miles")), unknown)
        assertEquals(listOf("Davis, Miles", "John Coltrane"), renamed.artists.map { it.name })
        val rated = cache.index(scope, listOf(blue.copy(userRating = 10f), love, miles), unknown)
        assertEquals(4, groupings)
        assertTrue(rated.artists.first().albums.any { it.isFavourite })
        assertNotSame(first, rated)
        assertFalse(cache.index(scope, emptyList(), unknown).artists.isNotEmpty())
    }

    @Test fun eachServerAccountLibraryModeAndOfflineScopeHasItsOwnEntry() {
        val scopes = listOf(scope, scope.copy(server = "https://other.example"), scope.copy(accountScope = "account-b"),
            scope.copy(libraryId = "8"), scope.copy(mode = LibraryMode.AUDIOBOOK), scope.copy(offlineOnly = true))
        scopes.forEach { each -> assertEquals(each, cache.index(each, catalogue, unknown).scope) }
        assertEquals(scopes.size, groupings)
    }

    @Test fun switchingAwayAndBackReusesTheGrouping() {
        val music = cache.index(scope, catalogue, unknown)
        cache.index(scope.copy(libraryId = "8"), listOf(miles), unknown)
        assertSame(music, cache.index(scope, catalogue.toList(), unknown))
        assertEquals(2, groupings)
    }

    @Test fun onlyTheMostRecentScopesAreKept() {
        val scopes = (1..4).map { scope.copy(libraryId = "$it") }
        scopes.forEach { cache.index(it, catalogue, unknown) }
        cache.index(scopes[3], catalogue, unknown)
        assertEquals(4, groupings)
        cache.index(scopes[0], catalogue, unknown)
        assertEquals("the least recently used scope was dropped", 5, groupings)
        cache.index(scopes[3], catalogue, unknown)
        assertEquals(5, groupings)
    }

    @Test fun aNewUnknownArtistTextRegroupsAndClearForgetsEverything() {
        val album = blue.copy(artist = " ")
        assertEquals("Unknown artist", cache.index(scope, listOf(album), unknown).artists.single().name)
        assertEquals("Artiste inconnu", cache.index(scope, listOf(album), "Artiste inconnu").artists.single().name)
        cache.clear()
        cache.index(scope, listOf(album), "Artiste inconnu")
        assertEquals(3, groupings)
    }

    @Test fun cachedPositionsKeepAccentFoldingAndTheNumberBucket() {
        val names = listOf("ZZ Top", "Édith Piaf", "Eagles", "2Cellos", "Adele", "東京", "")
        val index = cache.index(scope, names.mapIndexed { i, name -> blue.copy(id = "$i", artist = name) }, unknown)
        assertEquals(listOf("2Cellos", "東京", "Adele", "Eagles", "Édith Piaf", "Unknown artist", "ZZ Top"), index.artists.map { it.name })
        assertEquals(mapOf("#" to 0, "A" to 2, "E" to 3, "U" to 5, "Z" to 6), index.positions)
    }

    @Test fun filteringTheCachedGroupingMatchesTheOldSearch() {
        val albums = catalogue + blue.copy(id = "5", artist = "Alice Coltrane")
        val index = cache.index(scope, albums, unknown)
        for (query in listOf("", "  ", "coltrane", "COLTRANE john", "davis", "nobody")) {
            val filtered = filterArtists(index.artists, query)
            assertEquals(query, presentArtists(albums, query, unknown), filtered)
            assertEquals(query, artistLetterPositions(presentArtists(albums, query, unknown)), artistLetterPositions(filtered))
        }
        assertSame(index.artists, filterArtists(index.artists, " "))
    }

    @Test fun anIndexIsOnlyShownForTheCatalogueItWasBuiltFor() {
        val state = PlexTouchUiState(connection = PlexConnection("https://plex.example", "token"), accountScope = "account-a", selectedLibraryId = "7", mode = LibraryMode.MUSIC)
        val index = cache.index(ArtistIndexScope.of(state), catalogue, unknown)
        assertTrue(index.isFor(state))
        assertFalse(index.isFor(state.copy(selectedLibraryId = "8")))
        assertFalse(index.isFor(state.copy(mode = LibraryMode.AUDIOBOOK)))
        assertFalse(index.isFor(state.copy(accountScope = "account-b")))
        assertFalse(index.isFor(state.copy(offlineOnly = true)))
        assertFalse(index.isFor(state.copy(connection = PlexConnection("https://other.example", "token"))))
    }
}
