package org.johnfegan.plextouch.data

import java.io.IOException
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.johnfegan.plextouch.ui.UiText

class OfflineDownloadsTest {
    private class Catalog : DownloadCatalog {
        var records = emptyList<DownloadAlbum>()
        override fun load() = records
        override fun save(albums: List<DownloadAlbum>) { records = albums }
    }
    private class Backend : DownloadBackend {
        var next = 1L
        val transfers = mutableMapOf<Long, Transfer>()
        val paths = mutableMapOf<String, Long>()
        val lengths = mutableMapOf<String, Long>()
        var crashAfterEnqueue = false
        override fun enqueue(connection: PlexConnection, source: String, path: String, title: UiText): Long {
            val id = next++
            paths[path] = id
            transfers[id] = Transfer(TransferState.QUEUED)
            if (crashAfterEnqueue) { crashAfterEnqueue = false; throw IOException("Process interrupted") }
            return id
        }
        override fun transfer(file: DownloadFile) = transfers[file.id]
        override fun length(path: String) = lengths[path] ?: 0
        override fun uri(path: String) = "file:///app/offline/$path"
        override fun recover(path: String) = paths[path]
        override fun remove(file: DownloadFile) { transfers.remove(file.id); paths.remove(file.path); lengths.remove(file.path) }
        fun finish(file: DownloadFile) { transfers[file.id] = Transfer(TransferState.COMPLETE, 100, 100); lengths[file.path] = 100 }
    }
    private val connection = PlexConnection("https://server.example", "secret-account-token")
    private val album = PlexAlbum("album", "An album", "An artist", 2000, 2)
    private val tracks = listOf(
        PlexTrack("1", "First", "Artist", "Album", 60_000, "/library/parts/1/file.mp3", "mp3"),
        PlexTrack("2", "Second", "Artist", "Album", 120_000, "/library/parts/2/file.mp3", "mp3"),
    )
    private val catalog = Catalog()
    private val backend = Backend()
    private val engine = OfflineDownloads(catalog, backend)
    private fun download(mode: LibraryMode = LibraryMode.MUSIC, server: PlexConnection = connection) = engine.download(server, "library", mode, album, tracks)

    @Test fun ratingRefreshPreservesOfflineFilesAndOnlyUpdatesMatchingLibraryAndServer() {
        download()
        val original = catalog.records.single()
        engine.updateAlbumMetadata("other", "library", listOf(album.copy(userRating = 10f)))
        engine.updateAlbumMetadata(connection.serverUrl, "other", listOf(album.copy(userRating = 10f)))
        assertEquals(original, catalog.records.single())
        engine.updateAlbumMetadata(connection.serverUrl, "library", listOf(album.copy(userRating = 10f)))
        assertEquals(original.tracks, catalog.records.single().tracks)
        assertEquals(original.cover, catalog.records.single().cover)
        assertTrue(engine.snapshots().single().album.isFavourite)
        engine.updateAlbumMetadata(connection.serverUrl, "library", listOf(album.copy(userRating = null)))
        assertFalse(engine.snapshots().single().album.isFavourite)
        assertEquals(3L, backend.next)
    }

    @Test fun pendingAndCompleteDownloadsAreNotEnqueuedTwice() {
        download(); download()
        assertEquals(3L, backend.next)
        catalog.records.single().tracks.forEach { backend.finish(it.file) }
        assertTrue(engine.snapshots().single().ready)
        download()
        assertEquals(3L, backend.next)
    }

    @Test fun partialOrTruncatedFilesAreNeverMarkedDownloaded() {
        download()
        val saved = catalog.records.single().tracks
        backend.finish(saved[0].file)
        backend.finish(saved[1].file)
        backend.lengths[saved[1].file.path] = 30
        val snapshot = engine.snapshots().single()
        assertFalse(snapshot.ready)
        assertEquals(1, snapshot.failed)
        assertNotNull(snapshot.tracks[0].localUri)
        assertNull(snapshot.tracks[1].localUri)
    }

    @Test fun retryKeepsSuccessfulTracksAndOnlyRequeuesFailures() {
        download()
        val original = catalog.records.single().tracks
        backend.finish(original[0].file)
        backend.transfers[original[1].file.id] = Transfer(TransferState.FAILED)
        download()
        val after = catalog.records.single().tracks
        assertEquals(original[0].file.id, after[0].file.id)
        assertNotEquals(original[1].file.id, after[1].file.id)
        assertEquals(4L, backend.next)
    }

    @Test fun rebootRestoresMetadataAndLocalChapterOrderWithoutPlex() {
        download(LibraryMode.AUDIOBOOK)
        catalog.records.single().tracks.forEach { backend.finish(it.file) }
        engine.snapshots()
        val recreated = OfflineDownloads(catalog, backend).snapshots().single()
        assertTrue(recreated.ready)
        assertEquals(LibraryMode.AUDIOBOOK, recreated.record.mode)
        assertEquals(listOf("1", "2"), recreated.tracks.map { it.id })
        assertTrue(recreated.tracks.all { it.localUri!!.startsWith("file:///") })
        assertFalse(Gson().toJson(catalog.records).contains(connection.token))
    }

    @Test fun missingFileInvalidatesDownloadEvenWhenAndroidSaysComplete() {
        download()
        catalog.records.single().tracks.forEach { backend.finish(it.file) }
        assertTrue(engine.snapshots().single().ready)
        backend.lengths.remove(catalog.records.single().tracks[0].file.path)
        val snapshot = engine.snapshots().single()
        assertFalse(snapshot.ready)
        assertNull(snapshot.tracks[0].localUri)
    }

    @Test fun deletingSystemReceiptDoesNotInvalidateVerifiedLocalFile() {
        download()
        catalog.records.single().tracks.forEach { backend.finish(it.file) }
        engine.snapshots()
        backend.transfers.clear()
        assertTrue(engine.snapshots().single().ready)
    }

    @Test fun interruptionBetweenEnqueueAndReceiptSaveRecoversWithoutDuplicate() {
        backend.crashAfterEnqueue = true
        try { download(); fail("Expected interruption") } catch (_: IOException) { }
        assertEquals(0L, catalog.records.single().tracks[0].file.id)
        val restarted = OfflineDownloads(catalog, backend)
        restarted.snapshots()
        assertEquals(1L, catalog.records.single().tracks[0].file.id)
        restarted.download(connection, "library", LibraryMode.MUSIC, album, tracks)
        assertEquals(3L, backend.next)
    }

    @Test fun removalOnlyTouchesSelectedServerAndAlbum() {
        download()
        download(server = connection.copy(serverUrl = "https://other.example"))
        assertEquals(2, catalog.records.size)
        engine.remove(connection.serverUrl, album.id)
        assertEquals("https://other.example", catalog.records.single().server)
        assertEquals(2, backend.paths.size)
    }

    @Test fun waitingForNetworkIsNotFailureAndIsNotRequeued() {
        download()
        catalog.records.single().tracks.forEach { backend.transfers[it.file.id] = Transfer(TransferState.WAITING) }
        assertEquals(2, engine.snapshots().single().waiting)
        download()
        assertEquals(3L, backend.next)
    }

    @Test fun homeUsersOnOneServerKeepSeparateRecordsFilesAndRemovals() {
        engine.download(connection, "library", LibraryMode.MUSIC, album, tracks, "scope-a")
        engine.download(connection, "library", LibraryMode.MUSIC, album, tracks, "scope-b")
        assertEquals("one record per account scope", 2, catalog.records.size)
        val (a, b) = catalog.records.partition { it.accountScope == "scope-a" }.let { it.first.single() to it.second.single() }
        assertTrue("files never shared between users", a.tracks.map { it.file.path }.intersect(b.tracks.map { it.file.path }.toSet()).isEmpty())
        assertTrue(a.belongsTo(connection.serverUrl, "scope-a"))
        assertFalse("another user on the same server does not see it", a.belongsTo(connection.serverUrl, "scope-b"))
        assertFalse("nor does an unscoped reader", a.belongsTo(connection.serverUrl, null))
        engine.remove(connection.serverUrl, album.id, "scope-b")
        assertEquals("removing B's copy leaves A's record and files", listOf(a), catalog.records)
        assertEquals(2, backend.paths.size)
    }

    @Test fun legacyRecordsAreAdoptedOnlyByTheirServerAndNeverOverAScopedCopy() {
        download()
        download(server = connection.copy(serverUrl = "https://other.example"))
        val legacy = catalog.records.first { it.server == connection.serverUrl }
        assertNull(legacy.accountScope)
        assertFalse("hidden from scoped accounts until adopted", legacy.belongsTo(connection.serverUrl, "scope-a"))
        engine.adoptLegacy(connection.serverUrl, "scope-a")
        assertEquals("scope-a", catalog.records.first { it.server == connection.serverUrl }.accountScope)
        assertEquals("files are not moved", legacy.tracks, catalog.records.first { it.server == connection.serverUrl }.tracks)
        assertNull("other servers' legacy records wait for their own server", catalog.records.first { it.server != connection.serverUrl }.accountScope)
        engine.adoptLegacy(connection.serverUrl, "scope-b")
        assertEquals("an adopted record is not re-adopted by another user", "scope-a", catalog.records.first { it.server == connection.serverUrl }.accountScope)
    }

    @Test fun destinationsAreScopedSafeHashes() {
        val key = downloadKey("https://server.example", "../../escape")
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(key, downloadKey("https://other.example", "../../escape"))
    }
}
