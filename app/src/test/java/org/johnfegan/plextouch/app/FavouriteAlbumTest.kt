package org.johnfegan.plextouch.app

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.uiText

class FavouriteAlbumTest {
    private val gateway = FakeGateway()
    private val gateways = FakeGateways(gateway)
    private val downloads = FakeDownloads()
    private val favourite = FavouriteAlbum(gateways, downloads)
    private val album = album("20", "Blue")
    private val error = uiText(R.string.favourite_failed)

    @Test fun aRefusedRatingRollsBackTheOptimisticHeartAndReportsTheError() = runBlocking {
        gateway.rate = { _, _, _ -> throw IOException("timed out") }
        val applied = mutableListOf<Float?>()
        val result = favourite.toggle(connection, "1", album, favourite = true) { applied += it.userRating }
        assertEquals("shown as rated at once, then put back", listOf(10f, null), applied)
        assertEquals(FavouriteResult.Failed(error), result)
        assertTrue("nothing reaches the offline catalogue", downloads.metadata.isEmpty())
    }

    @Test fun anAcceptedRatingKeepsTheHeartAndReturnsTheRefreshedLibrary() = runBlocking {
        val rated = album.copy(userRating = 10f)
        gateway.albumList = { listOf(rated, album("21")) }
        val applied = mutableListOf<Float?>()
        val result = favourite.toggle(connection, "1", album, favourite = true) { applied += it.userRating }
        assertEquals(listOf(10f), applied)
        assertEquals(FavouriteResult.Saved(uiText(R.string.favourite_saved), listOf(rated, album("21"))), result)
        assertEquals(listOf(listOf(rated), listOf(rated, album("21"))), downloads.metadata)
        assertEquals("the rating bypasses the browsing cache", listOf(connection to true), gateways.opened)
        assertEquals(listOf("rate:20:true", "albums"), gateway.calls)
    }

    @Test fun clearingTheRatingSaysSo() = runBlocking {
        gateway.albumList = { listOf(album) }
        val result = favourite.toggle(connection, "1", album.copy(userRating = 10f), favourite = false) {}
        assertEquals(FavouriteResult.Saved(uiText(R.string.favourite_cleared), listOf(album)), result)
    }

    @Test fun aFailedRefreshAfterAnAcceptedRatingKeepsTheHeart() = runBlocking {
        gateway.albumList = { throw IOException("gone away") }
        val applied = mutableListOf<Float?>()
        val result = favourite.toggle(connection, "1", album, favourite = true) { applied += it.userRating }
        assertEquals("Plex holds the rating, so it is not undone", listOf(10f), applied)
        assertEquals(FavouriteResult.Failed(error), result)
    }
}
