package org.johnfegan.plextouch.data

import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Ticket 137: storage accounting, the artwork budget, clear boundaries and in-use safety, against real folders. */
@OptIn(ExperimentalCoilApi::class)
class StorageControlsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val connection = PlexConnection("http://home", "private-token")

    /** A phone-shaped layout: caches under cache/, downloads under external Music/offline, preferences in shared_prefs. */
    private inner class Phone {
        val base: File = temporary.newFolder()
        val cache = File(base, "cache")
        val offline = File(base, "external/Music/offline").apply { mkdirs() }
        val files = File(base, "files").apply { mkdirs() }
        val prefs = File(base, "shared_prefs").apply { mkdirs() }
        val metadata = PlexCache(File(cache, PlexCache.DIRECTORY))
        val disk: DiskCache = DiskCache.Builder().directory(File(cache, "image_cache")).maxSizeBytes(ArtworkBudget.MB_50.bytes).build()
        val controls = StorageControls(metadata, CoilArtworkCache { disk }, { listOf(offline, files, prefs) })
        val track = File(offline, "album/track.mp3").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(4096) { 7 }) }
        val partial = File(offline, "album/next.mp3").apply { writeBytes(ByteArray(1000) { 3 }) }
        val credentials = File(prefs, "plex_touch_secure.xml").apply { writeText("<map><string name=\"token\">secret</string></map>") }
        val store = File(prefs, "plex_touch_store.xml").apply { writeText("<map><string name=\"listening_history\">[]</string><string name=\"audiobook_timeline_sync\">{}</string></map>") }
        val catalogue = File(prefs, "offline_catalog.xml").apply { writeText("<map/>") }
        fun untouched(): List<Pair<File, ByteArray>> = listOf(track, partial, credentials, store, catalogue).map { it to it.readBytes() }
    }

    private fun DiskCache.put(key: String, bytes: Int) {
        val editor = requireNotNull(openEditor(key))
        FileSystem.SYSTEM.write(editor.metadata) { writeUtf8("m") }
        FileSystem.SYSTEM.write(editor.data) { write(ByteArray(bytes)) }
        editor.commit()
    }

    private fun DiskCache.has(key: String) = openSnapshot(key)?.use { true } ?: false

    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue(message, System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    private fun album(id: String, vararg files: DownloadFile, cover: DownloadFile? = null) = DownloadAlbum("http://home", "1", LibraryMode.MUSIC,
        PlexAlbum(id, id, "Artist", 2000, files.size), files.mapIndexed { index, file -> DownloadTrack(PlexTrack("$id$index", "t", "a", "b", 1, "/p", "mp3"), file) }, cover)

    @Test fun reportsVerifiedDownloadsArtworkAndMetadataSeparately() = runBlocking {
        val phone = Phone()
        phone.metadata.write(connection, "/library/sections", "x".repeat(300))
        phone.metadata.write(connection, "/library/sections/1/all", "y".repeat(700))
        File(phone.metadata.root, "stray.json.tmp").writeText("half-written")
        phone.disk.put("http://home/cover/1", 2_000)
        val downloads = listOf(
            album("ready", DownloadFile("a", 1, 4096), DownloadFile("b", 2, 5000), cover = DownloadFile("c", 3, 120)),
            album("active", DownloadFile("d", 4, 0), DownloadFile("e", 5, 800)),
        )
        val report = phone.controls.measure(downloads)
        assertEquals("only verified bytes count; an active transfer's partial file does not", 4096L + 5000 + 120 + 800, report.downloadBytes)
        assertEquals("metadata counts saved entries, not a write in progress", 1000L, report.metadataBytes)
        assertEquals(phone.disk.size, report.artworkBytes)
        assertTrue(report.artworkBytes >= 2_000)
        assertEquals(StorageReport(), StorageControls(PlexCache(temporary.newFolder()), CoilArtworkCache { null }, { emptyList() }).measure(emptyList()))
    }

    @Test fun artworkBudgetIsBoundedAndEvictsLeastRecentlyUsedFirst() {
        assertEquals(listOf(50, 100, 250, 500), ArtworkBudget.entries.map { it.megabytes })
        assertEquals(ArtworkBudget.MB_100, ArtworkBudget.fromMegabytes(100))
        assertEquals("unknown stored values fall back to the default", ArtworkBudget.DEFAULT, ArtworkBudget.fromMegabytes(7))
        assertEquals(ArtworkBudget.DEFAULT, ArtworkBudget.fromMegabytes(-1))
        assertEquals(500L * 1024 * 1024, ArtworkBudget.MB_500.bytes)

        val folder = temporary.newFolder()
        val cache = DiskCache.Builder().directory(folder).maxSizeBytes(1_000).build()
        cache.put("one", 300); cache.put("two", 300); cache.put("three", 300)
        assertTrue(cache.has("one")) // reading "one" makes "two" the least recently used
        cache.put("four", 300)
        // Poll the size, not the entries: reading an entry would itself change the eviction order.
        eventually("the cache trims back within its budget") { cache.size <= 1_000 }
        assertFalse("the least recently used cover is evicted first", cache.has("two"))
        assertTrue(cache.has("one")); assertTrue(cache.has("three")); assertTrue(cache.has("four"))

        // A smaller budget chosen on the Storage screen applies when the cache is next opened (the next app start).
        val reopened = DiskCache.Builder().directory(folder).maxSizeBytes(400).build()
        reopened.put("five", 300)
        eventually("reopening with a smaller budget trims down to it") { reopened.size <= 400 }
        assertTrue("the newest cover survives the trim", reopened.has("five"))
    }

    @Test fun clearingCachesNeverTouchesDownloadsCredentialsHistoryOrOutbox() = runBlocking {
        val phone = Phone()
        val before = phone.untouched()
        phone.metadata.write(connection, "/library/sections", "{}")
        phone.metadata.write(connection, "/playlists/4/items", "{}")
        phone.disk.put("http://home/cover/1", 500)
        assertTrue(phone.controls.clearArtwork())
        assertFalse(phone.disk.has("http://home/cover/1"))
        assertEquals(0L, phone.disk.size)
        assertNotNull("clearing artwork leaves saved metadata alone", phone.metadata.read(connection, "/library/sections"))
        assertTrue(phone.controls.clearMetadata())
        assertNull(phone.metadata.read(connection, "/library/sections"))
        assertNull(phone.metadata.read(connection, "/playlists/4/items"))
        assertEquals(0L, phone.metadata.bytes())
        before.forEach { (file, bytes) -> assertArrayEquals("${file.name} must survive both clears", bytes, file.readBytes()) }
        // Clearing an empty or missing cache is a no-op success, and the cache keeps working afterwards.
        assertTrue(phone.controls.clearMetadata())
        phone.metadata.write(connection, "/library/sections", "{\"again\":1}")
        assertEquals("{\"again\":1}", phone.metadata.read(connection, "/library/sections"))
    }

    @Test fun refusesACacheFolderThatOverlapsDownloadsOrPreferences() = runBlocking {
        val phone = Phone()
        assertTrue(isSafeCacheTarget(File(phone.cache, "plex_metadata"), listOf(phone.offline, phone.prefs)))
        assertFalse("the downloads folder itself", isSafeCacheTarget(phone.offline, listOf(phone.offline)))
        assertFalse("inside the downloads folder", isSafeCacheTarget(File(phone.offline, "album"), listOf(phone.offline)))
        assertFalse("a parent of the downloads folder", isSafeCacheTarget(phone.base, listOf(phone.offline)))
        assertFalse("a dot-dot path into preferences", isSafeCacheTarget(File(phone.cache, "../shared_prefs"), listOf(phone.prefs)))
        assertTrue("a sibling whose name only starts the same", isSafeCacheTarget(File(phone.base, "external/Music/offline-cache"), listOf(phone.offline)))

        // A metadata cache wrongly rooted inside the downloads folder is never cleared.
        val misplaced = PlexCache(File(phone.offline, "album"))
        val before = phone.untouched()
        val controls = StorageControls(misplaced, CoilArtworkCache { phone.disk }, { listOf(phone.offline, phone.prefs) })
        assertFalse(controls.clearMetadata())
        val wrongArtwork = object : ArtworkCacheGateway {
            override val directory = phone.prefs
            override fun bytes() = 0L
            override fun clear() = error("must not be called")
        }
        assertFalse(StorageControls(phone.metadata, wrongArtwork, { listOf(phone.prefs) }).clearArtwork())
        before.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    @Test fun anActiveDownloadAndInUseCacheFilesSurviveAClear() = runBlocking {
        val phone = Phone()
        // A download still being written by the system stays open and writable across both clears.
        FileOutputStream(phone.partial, true).use { transfer ->
            transfer.write(ByteArray(500) { 3 })
            assertTrue(phone.controls.clearArtwork())
            assertTrue(phone.controls.clearMetadata())
            transfer.write(ByteArray(500) { 3 })
        }
        assertEquals(2000L, phone.partial.length())

        // A cover being read keeps its bytes until the reader closes; a cover mid-write is discarded, never half-saved.
        phone.disk.put("reading", 400)
        val snapshot = requireNotNull(phone.disk.openSnapshot("reading"))
        val writing = requireNotNull(phone.disk.openEditor("writing"))
        FileSystem.SYSTEM.write(writing.metadata) { writeUtf8("m") }
        FileSystem.SYSTEM.write(writing.data) { write(ByteArray(10)) }
        assertTrue(phone.controls.clearArtwork())
        assertEquals("the open reader still sees the whole file", 400L, FileSystem.SYSTEM.metadata(snapshot.data).size)
        snapshot.close()
        runCatching { writing.commit() }
        assertFalse("a write interrupted by a clear never becomes an entry", phone.disk.has("writing"))
        assertFalse(phone.disk.has("reading"))
        phone.disk.put("after", 100)
        assertTrue("the cache works normally after a clear", phone.disk.has("after"))
    }

    @Test fun concurrentWritesAndClearsNeverLeaveACorruptOrPartialEntry() {
        val root = temporary.newFolder()
        val writer = PlexCache(root)
        val clearer = PlexCache(root) // a second instance on the same folder, as the Storage screen holds
        val value = "{\"items\":\"" + "z".repeat(50_000) + "\"}"
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val running = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>(null)
        repeat(2) { thread ->
            pool.execute {
                start.await()
                var n = 0
                while (running.get()) runCatching {
                    writer.write(connection, "/library/metadata/${thread * 1000 + n++ % 20}/children", value)
                }.onFailure { failure.set(it) }
            }
        }
        pool.execute {
            start.await()
            while (running.get()) runCatching {
                (0 until 20).forEach { n -> writer.read(connection, "/library/metadata/$n/children")?.let { check(it == value) { "partial read" } } }
            }.onFailure { failure.set(it) }
        }
        pool.execute { start.await(); while (running.get()) runCatching { clearer.clear() }.onFailure { failure.set(it) } }
        start.countDown()
        Thread.sleep(1_500)
        running.set(false)
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertNull(failure.get())
        root.listFiles().orEmpty().forEach { file ->
            assertEquals("no temporary file is left behind: ${file.name}", "json", file.extension)
            assertEquals("every surviving entry is whole", value, file.readText())
        }
        assertTrue(clearer.clear())
        assertEquals(0, root.listFiles().orEmpty().size)
    }
}
