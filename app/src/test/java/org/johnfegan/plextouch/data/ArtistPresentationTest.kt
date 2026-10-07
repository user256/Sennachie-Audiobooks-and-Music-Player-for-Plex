package org.johnfegan.plextouch.data

import org.johnfegan.plextouch.ui.presentArtists as presentArtistsNamed
import org.johnfegan.plextouch.ui.artistLetter
import org.johnfegan.plextouch.ui.artistLetterPositions
import org.junit.Assert.*
import org.junit.Test

class ArtistPresentationTest {
    private val blue = PlexAlbum("1", "Blue Train", "John Coltrane", 1957, 5)
    private val love = blue.copy(id = "2", title = "A Love Supreme", artist = " john   COLTRANE ")
    private val miles = blue.copy(id = "3", title = "Kind of Blue", artist = "Miles Davis")

    // The screen passes its localised "Unknown artist"; these tests use the English copy.
    private fun presentArtists(albums: List<PlexAlbum>, query: String = "") = presentArtistsNamed(albums, query, "Unknown artist")

    @Test fun groupsAndSortsArtistsAndTheirAlbumsWithoutDuplicatingAlbums() {
        val artists = presentArtists(listOf(miles, blue, love, blue))
        assertEquals(listOf("John Coltrane", "Miles Davis"), artists.map { it.name })
        assertEquals(listOf("2", "1"), artists.first().albums.map { it.id })
        assertEquals(listOf(miles), artists.last().albums)
    }

    @Test fun artistSearchIsCaseInsensitiveAndMatchesAllWords() {
        assertEquals(listOf("john coltrane"), presentArtists(listOf(blue, miles), "  COLTRANE John ").map { it.key })
        assertTrue(presentArtists(listOf(blue), "Blue Train").isEmpty())
        assertTrue(presentArtists(listOf(blue), "John Davis").isEmpty())
    }

    @Test fun compilationsCollaborationsAndMissingNamesRemainSeparate() {
        val artists = presentArtists(listOf(blue.copy(artist = ""), blue.copy(id = "2", artist = "  "),
            blue.copy(id = "3", artist = "Various Artists"), blue.copy(id = "4", artist = "A & B")))
        assertEquals(listOf("A & B", "Unknown artist", "Various Artists"), artists.map { it.name })
        assertEquals(2, artists[1].albums.size)
    }

    @Test fun currentCatalogueControlsMembershipAndRetainsOfflineArtwork() {
        val local = blue.copy(localThumb = "/local/cover.jpg")
        assertEquals(listOf(local), presentArtists(listOf(local)).single().albums)
        assertTrue(presentArtists(emptyList()).isEmpty())
        assertTrue(presentArtists(listOf(blue.copy(id = "playlist:123"))).isEmpty())
        assertFalse(presentArtists(listOf(miles)).any { it.key == "john coltrane" })
    }

    @Test fun letterSelectorFindsFirstMatchAndUsesAccentsAndNumberBucket() {
        val artists = presentArtists(listOf("ZZ Top", "Édith Piaf", "Eagles", "2Cellos", "Adele", "東京").mapIndexed { index, name -> blue.copy(id = "$index", artist = name) })
        assertEquals(listOf("2Cellos", "東京", "Adele", "Eagles", "Édith Piaf", "ZZ Top"), artists.map { it.name })
        assertEquals(mapOf("#" to 0, "A" to 2, "E" to 3, "Z" to 5), artistLetterPositions(artists))
        assertEquals("E", artistLetter(" Édith Piaf"))
        assertEquals("#", artistLetter(""))
    }

    @Test fun filteredLetterPositionsNeverTargetAnAbsentArtist() {
        assertEquals(mapOf("M" to 0), artistLetterPositions(presentArtists(listOf(blue, love, miles), "Miles")))
        assertTrue(artistLetterPositions(emptyList()).isEmpty())
    }
}
