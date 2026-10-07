package org.johnfegan.plextouch.ui

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import java.text.Normalizer
import java.util.Locale

/**
 * Folds text for matching: accents and other combining marks are dropped, a few letters that do not decompose are spelled
 * out (ß, æ, ø, œ, ł, đ, þ), case is ignored and runs of whitespace collapse. Plain ASCII skips the Unicode normaliser.
 */
fun searchKey(text: String): String {
    val ascii = text.all { it.code < 0x80 }
    val stripped = if (ascii) text else Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
    val lower = stripped.lowercase(Locale.ROOT)
    val spelled = if (ascii) lower else buildString(lower.length) {
        for (char in lower) append(SPELLED[char] ?: char)
    }
    return spelled.trim().replace(WHITESPACE, " ")
}

/** The words of a query, each folded by [searchKey]; a blank query has none. */
fun searchWords(query: String): List<String> = searchKey(query).split(' ').filter(String::isNotEmpty)

/** True when every query word appears somewhere in the album's title or artist/author, ignoring case and accents. */
fun matchesSearch(album: PlexAlbum, words: List<String>): Boolean {
    if (words.isEmpty()) return true
    val key = albumSearchKey(album)
    return words.all(key::contains)
}

private fun albumSearchKey(album: PlexAlbum) = searchKey("${album.title} ${album.artist}")
private val MARKS = Regex("\\p{M}+")
private val WHITESPACE = Regex("\\s+")
private val SPELLED = mapOf('ß' to "ss", 'æ' to "ae", 'ø' to "o", 'œ' to "oe", 'ł' to "l", 'đ' to "d", 'þ' to "th", 'ı' to "i")

/**
 * One catalogue prepared for typing: the albums are put in Search's title order once and each one's folded title and artist
 * is kept beside it, so a keystroke is a single pass of substring checks with no sorting or Unicode work. Measured on the
 * JVM for 20,000 albums: building ~50 ms cold (done off the main thread), each query ~1.3 ms; expect a few times that on a phone.
 */
class SearchIndex(val albums: List<PlexAlbum>) {
    private val sorted: List<PlexAlbum> = presentAlbums(albums, "", AlbumSort.TITLE)
    private val keys: Array<String> = Array(sorted.size) { albumSearchKey(sorted[it]) }

    /** Matching albums in title order; a blank query lists the whole catalogue. */
    fun search(query: String): List<PlexAlbum> {
        val words = searchWords(query)
        if (words.isEmpty()) return sorted
        val matches = ArrayList<PlexAlbum>()
        for (i in keys.indices) if (words.all(keys[i]::contains)) matches += sorted[i]
        return matches
    }
}

/**
 * What Search looks through. `scope` names the catalogue (server, mode, library and whether only downloads count), so a
 * change of scope replaces the results at once while a new list for the same scope is treated as a background refresh.
 */
@Immutable
data class SearchInput(val scope: String, val albums: List<PlexAlbum>, val query: String, val offline: Boolean)

/** `ready` is false until the first pass over a newly chosen catalogue has finished, so Search does not flash "No matches". */
@Immutable
data class SearchResults(val query: String = "", val albums: List<PlexAlbum> = emptyList(), val offline: Boolean = false, val ready: Boolean = false)

/** Downloads that belong to one server, mode and library — the catalogue offline-only mode browses and searches. */
fun libraryDownloads(downloads: List<DownloadStatus>, server: String?, mode: LibraryMode, library: String?): List<DownloadStatus> =
    downloads.filter { it.record.server == server && it.record.mode == mode && it.record.libraryId == library }

/**
 * The catalogue Search filters, taken only from memory. Connected, that is the library already loaded for the current mode;
 * in offline-only mode it is the finished downloads alone, whatever else the state still holds, so nothing needs the network.
 */
fun searchInput(state: PlexTouchUiState): SearchInput {
    val server = state.connection?.serverUrl
    val scope = listOf(server.orEmpty(), state.accountScope.orEmpty(), state.mode.name, state.selectedLibraryId.orEmpty(), if (state.offlineOnly) "offline" else "online").joinToString("\u0000")
    val albums = if (state.offlineOnly) libraryDownloads(state.downloads, server, state.mode, state.selectedLibraryId).filter { it.ready }.map { it.album }
    else state.albums
    return SearchInput(scope, albums, state.query, state.offlineOnly)
}

/**
 * Keeps Search's results in step with the query and the in-memory catalogue without ever waiting on Plex.
 *
 * - A keystroke filters the catalogue already shown, off the main thread; only the newest query may publish, so a slow pass
 *   for an older query can never replace the results for a newer one.
 * - A refreshed catalogue for the same scope is indexed in the background while the current results stay on screen. It is
 *   swapped in only once typing has paused for [typingIdleMs], so rows do not reorder under the finger, and then re-filtered
 *   with the newest query. A refresh that changes nothing leaves the results untouched.
 * - A different scope (mode, library, server or offline-only) replaces the results straight away; the old rows belong to
 *   another catalogue.
 *
 * [submit] must be called from [scope]'s thread (the main thread in the app).
 */
class CatalogueSearch(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val typingIdleMs: Long = TYPING_IDLE_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val flow = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = flow.asStateFlow()

    private var catalogueScope: String? = null
    private var submitted: List<PlexAlbum>? = null
    private var index: SearchIndex? = null
    private var pending: List<PlexAlbum>? = null
    private var staged: SearchIndex? = null
    private var query = ""
    private var offline = false
    private var lastKeystroke = Long.MIN_VALUE / 2
    private var generation = 0L
    private var job: Job? = null

    fun submit(input: SearchInput) {
        val scopeChanged = input.scope != catalogueScope
        val queryChanged = input.query != query
        val catalogueChanged = scopeChanged || input.albums !== submitted
        if (!scopeChanged && !queryChanged && !catalogueChanged) return
        if (scopeChanged) {
            catalogueScope = input.scope
            index = null
            staged = null
            flow.value = SearchResults(input.query, emptyList(), input.offline, ready = false)
        }
        if (queryChanged) lastKeystroke = clock()
        query = input.query
        offline = input.offline
        if (catalogueChanged) { submitted = input.albums; pending = input.albums }
        val run = ++generation
        job?.cancel()
        job = scope.launch { refresh(run, queryChanged) }
    }

    private suspend fun refresh(run: Long, queryChanged: Boolean) {
        val shown = index
        if (shown != null && queryChanged) publish(run, withContext(worker) { shown.search(query) })
        val incoming = pending ?: return
        val next = staged?.takeIf { it.albums === incoming } ?: withContext(worker) {
            if (shown != null && shown.albums == incoming) shown else SearchIndex(incoming)
        }.also { staged = it }
        if (next === shown) { pending = null; staged = null; return }
        // Nothing on screen can move under the finger when the list is empty, so only a non-empty list waits for a pause.
        if (shown != null && flow.value.albums.isNotEmpty()) {
            var wait = lastKeystroke + typingIdleMs - clock()
            while (wait > 0) { delay(wait); wait = lastKeystroke + typingIdleMs - clock() }
        }
        val albums = withContext(worker) { next.search(query) }
        if (run != generation) return
        index = next
        pending = null
        staged = null
        publish(run, albums)
    }

    private fun publish(run: Long, albums: List<PlexAlbum>) {
        if (run == generation) flow.value = SearchResults(query, albums, offline, ready = true)
    }

    /** Waits for the current pass, for tests. */
    internal suspend fun settle() { job?.join() }

    companion object {
        /** How long typing must pause before a refreshed catalogue may reorder the visible results. */
        const val TYPING_IDLE_MS = 600L
    }
}

/** Search may check Plex for a newer catalogue only when connected, not offline-only, with a library chosen and no load running. */
fun shouldRefreshCatalogue(state: PlexTouchUiState, loading: Boolean): Boolean =
    !state.offlineOnly && !loading && state.connection != null && state.selectedLibraryId != null
