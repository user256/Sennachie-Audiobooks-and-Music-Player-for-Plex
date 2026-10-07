package org.johnfegan.plextouch.data

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.uiText
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.chapterName

class ChapterTest {
    private fun chapter(start: Long, end: Long, title: String = "", index: Int = 0) = PlexChapter(index, title, start, end)
    private fun track(chapters: List<PlexChapter>, id: String = "1", durationMs: Long = 600_000) =
        PlexTrack(id, "Book", "Author", "Book", durationMs, "/file.m4b", "m4b", chapterMarkers = chapters.takeIf { it.isNotEmpty() })
    private val markers = normaliseChapters(listOf(chapter(0, 100_000, "One"), chapter(100_000, 250_000, "Two"), chapter(250_000, 600_000, "Three")), 600_000)

    @Test fun normalisationSortsClampsTrimsOverlapsAndNamesUntitledMarkers() {
        val raw = listOf(
            chapter(300_000, 450_000, "Late", 9),
            chapter(100_000, 250_000, "  ", 2),
            chapter(0, 120_000, "Intro", 1),
            chapter(250_000, 250_000, "Empty", 3),
            chapter(450_000, 900_000, "Beyond", 4),
            chapter(700_000, 800_000, "Dropped", 5),
            chapter(-5_000, 0, "Negative", 6),
        )
        val chapters = normaliseChapters(raw, 600_000)
        // An untitled marker keeps a blank title; the screens name it "Chapter N" from resources.
        assertEquals(listOf("Intro", "", "Late", "Beyond"), chapters.map { it.title })
        assertEquals(listOf(UiText.Raw("Intro"), uiText(R.string.chapter_number_title, 2), UiText.Raw("Late"), UiText.Raw("Beyond")), chapters.map(::chapterName))
        assertEquals(listOf(1, 2, 3, 4), chapters.map { it.index })
        assertEquals(listOf(0L, 100_000L, 300_000L, 450_000L), chapters.map { it.startMs })
        assertEquals(listOf(100_000L, 250_000L, 450_000L, 600_000L), chapters.map { it.endMs })
        assertTrue(normaliseChapters(emptyList(), 600_000).isEmpty())
        assertEquals(listOf(0L, 100_000L), normaliseChapters(raw.take(3), 0).map { it.startMs }.take(2))
    }

    @Test fun chapterAtUsesTheLastMarkerStartingAtOrBeforeThePosition() {
        val book = track(markers)
        assertNull(chapterAt(track(emptyList()), 50_000))
        assertNull(chapterAt(track(listOf(chapter(10_000, 50_000, "Late start", 1))), 9_999))
        assertEquals("One", chapterAt(book, 0)?.title)
        assertEquals("One", chapterAt(book, 99_999)?.title)
        assertEquals("Two", chapterAt(book, 100_000)?.title)
        assertEquals("Three", chapterAt(book, 250_000)?.title)
        assertEquals("Three", chapterAt(book, 5_000_000)?.title)
    }

    @Test fun singleFileWithMarkersListsThoseMarkersAndSeeksInsideTheFile() {
        val entries = bookChapters(listOf(track(markers)), 0, 130_000)
        assertEquals(listOf("One", "Two", "Three").map(UiText::Raw), entries.map { it.title })
        assertEquals(listOf(0, 0, 0), entries.map { it.trackIndex })
        assertEquals(listOf(0L, 100_000L, 250_000L), entries.map { it.offsetMs })
        assertEquals(listOf(100_000L, 150_000L, 350_000L), entries.map { it.durationMs })
        assertEquals(listOf(false, true, false), entries.map { it.active })
        assertEquals(uiText(R.string.chapter_starts_at, "1:40"), entries[1].subtitle)
        assertEquals(2, chapterNumber(listOf(track(markers)), 0, 130_000))
        assertEquals(3, chapterCount(listOf(track(markers))))
        assertEquals(UiText.Raw("Two"), chapterTitle(track(markers), 1, 130_000))
        assertTrue(bookChapters(listOf(track(markers)), -1, 130_000).none { it.active })
    }

    @Test fun singleFileWithoutMarkersAndMultiFileBooksListTracks() {
        val plain = listOf(track(emptyList()))
        assertEquals(listOf(UiText.Raw("Book")), bookChapters(plain, 0, 130_000).map { it.title })
        assertEquals(1, chapterNumber(plain, 0, 130_000))
        assertEquals(1, chapterCount(plain))
        val oneMarker = listOf(track(listOf(chapter(0, 600_000, "Only"))))
        assertEquals(UiText.Raw("Book"), bookChapters(oneMarker, 0, 10).single().title)
        val multi = listOf(track(markers, "1"), track(emptyList(), "2"), track(emptyList(), "3"))
        val entries = bookChapters(multi, 1, 130_000)
        assertEquals(listOf("1", "2", "3"), entries.map { it.label })
        assertEquals(listOf(0, 1, 2), entries.map { it.trackIndex })
        assertEquals(listOf(0L, 0L, 0L), entries.map { it.offsetMs })
        assertEquals(listOf(false, true, false), entries.map { it.active })
        assertEquals(2, chapterNumber(multi, 1, 130_000))
        assertEquals(3, chapterCount(multi))
        assertEquals(UiText.Raw("Book"), chapterTitle(multi[0], 3, 130_000))
        assertEquals(4, chapterNumber(emptyList(), 3, 130_000))
    }

    @Test fun downloadCatalogueRoundTripsChaptersAndOldRecordsReadAsEmpty() {
        val gson = Gson()
        val album = PlexAlbum("a", "Book", "Author", 2020, 1)
        val record = DownloadAlbum("https://server", "lib", LibraryMode.AUDIOBOOK, album, listOf(DownloadTrack(track(markers), DownloadFile("a/file.m4b", 4, 100))))
        val restored = gson.fromJson(gson.toJson(listOf(record)), Array<DownloadAlbum>::class.java).single()
        assertEquals(record, restored)
        assertEquals(markers, restored.tracks.single().track.chapters)
        val legacy = gson.fromJson("""{"id":"1","title":"Book","artist":"Author","album":"Book","durationMs":600000,"streamPath":"/file.m4b","container":"m4b"}""", PlexTrack::class.java)
        assertEquals(emptyList<PlexChapter>(), legacy.chapters)
        assertEquals(legacy, track(emptyList()))
    }

    @Test fun albumChildrenRequestChaptersAndParseThem() = runBlocking {
        val request = AtomicReference<String?>()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                request.set(input.readLine().substringBefore(" HTTP/"))
                while (!input.readLine().isNullOrEmpty()) { }
                val body = """{"MediaContainer":{"Metadata":[{"ratingKey":"42","title":"Whole book","parentTitle":"Book","grandparentTitle":"Author","duration":600000,
                    "Media":[{"container":"m4b","Part":[{"key":"/library/parts/42/file.m4b"}]}],
                    "Chapter":[{"id":2,"index":2,"startTimeOffset":250000,"endTimeOffset":600000,"thumb":"/x"},{"id":1,"index":1,"tag":"Opening","startTimeOffset":0,"endTimeOffset":250000}]}]}}""".toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            }
        }
        try {
            val api = PlexClient(PlexConnection("http://127.0.0.1:${server.localPort}", "token"), timeoutMillis = 1000)
            val tracks = api.albumTracks("7")
            assertEquals("GET /library/metadata/7/children?includeChapters=1", request.get())
            assertEquals(listOf(PlexChapter(1, "Opening", 0, 250_000), PlexChapter(2, "", 250_000, 600_000)), tracks.single().chapters)
            assertEquals("/library/parts/42/file.m4b", tracks.single().streamPath)
        } finally { server.close(); worker.join(1000) }
    }
}
