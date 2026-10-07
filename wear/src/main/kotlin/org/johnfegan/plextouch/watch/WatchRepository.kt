package org.johnfegan.plextouch.watch

import android.content.Context
import android.os.storage.StorageManager
import androidx.core.content.edit
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.johnfegan.plextouch.wear.shared.CommandResult
import org.johnfegan.plextouch.wear.shared.Decoded
import org.johnfegan.plextouch.wear.shared.FileDigest
import org.johnfegan.plextouch.wear.shared.PhoneState
import org.johnfegan.plextouch.wear.shared.TransferFile
import org.johnfegan.plextouch.wear.shared.TransferManifest
import org.johnfegan.plextouch.wear.shared.WatchBook
import org.johnfegan.plextouch.wear.shared.WatchBooks
import org.johnfegan.plextouch.wear.shared.WearCodec

/** How the watch can reach the phone app right now. */
enum class PhoneReach { CHECKING, CONNECTED, UNREACHABLE, NOT_INSTALLED }

/**
 * The watch app's state (ticket 141), one instance per process. The book (with its unsent checkpoints) and the phone's
 * last published state are persisted in private preferences so the complication and a restarted app still have them;
 * nothing here is a credential, and nothing is backed up (`allowBackup="false"`).
 */
object WatchRepository {
    private const val PREFS = "plextouch_watch"
    private const val BOOK = "book"
    private const val PHONE = "phone_state"
    private val gson = Gson()
    private val lock = Any()
    @Volatile private var loaded = false

    private val bookFlow = MutableStateFlow(WatchBook())
    private val phoneFlow = MutableStateFlow<PhoneState?>(null)
    private val reachFlow = MutableStateFlow(PhoneReach.CHECKING)
    private val commandFlow = MutableStateFlow<CommandResult?>(null)
    private val unsupportedFlow = MutableStateFlow(false)

    val book: StateFlow<WatchBook> = bookFlow.asStateFlow()
    val phone: StateFlow<PhoneState?> = phoneFlow.asStateFlow()
    val reach: StateFlow<PhoneReach> = reachFlow.asStateFlow()
    val lastCommand: StateFlow<CommandResult?> = commandFlow.asStateFlow()
    /** The phone app speaks a newer protocol version: both apps should be updated. */
    val unsupported: StateFlow<Boolean> = unsupportedFlow.asStateFlow()

    fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val prefs = prefs(context)
            bookFlow.value = WatchBooks.sanitise(runCatching { gson.fromJson(prefs.getString(BOOK, null), WatchBook::class.java) }.getOrNull())
            phoneFlow.value = (WearCodec.decode(prefs.getString(PHONE, null)?.toByteArray(Charsets.UTF_8)) as? Decoded.Message)?.message as? PhoneState
            loaded = true
        }
    }

    /** Applies one [WatchBooks] transition and saves it; returns the new state. */
    fun update(context: Context, change: (WatchBook) -> WatchBook): WatchBook = synchronized(lock) {
        load(context)
        val before = bookFlow.value
        val after = change(before)
        if (after != before) {
            prefs(context).edit { putString(BOOK, gson.toJson(after)) }
            bookFlow.value = after
        }
        after
    }

    fun phoneState(context: Context, bytes: ByteArray) {
        when (val decoded = WearCodec.decode(bytes)) {
            is Decoded.Message -> (decoded.message as? PhoneState)?.let { state ->
                load(context)
                prefs(context).edit { putString(PHONE, String(bytes, Charsets.UTF_8)) }
                phoneFlow.value = state
                unsupportedFlow.value = false
            }
            is Decoded.Unsupported -> unsupportedFlow.value = true
            else -> Unit
        }
    }

    fun reach(value: PhoneReach) { reachFlow.value = value }
    fun commandResult(result: CommandResult?) { commandFlow.value = result }
    fun unsupported() { unsupportedFlow.value = true }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The book's files in app-private storage: `files/book/<transferId>/<index>.<ext>`, received as `<index>.part` and
 * renamed only after the watch's own size and SHA-256 check passes.
 */
object WatchFiles {
    fun root(context: Context) = File(context.filesDir, "book")
    private fun dir(context: Context, transferId: String) = File(root(context), transferId)
    fun part(context: Context, transferId: String, index: Int) = File(dir(context, transferId), "$index.part")
    fun file(context: Context, manifest: TransferManifest, index: Int) = File(dir(context, manifest.transferId), "$index.${manifest.files[index].extension}")
    /** What the system would let the app allocate (including cache it can clear), as Android recommends over `usableSpace`. */
    fun freeBytes(context: Context): Long = runCatching {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
    }.getOrDefault(0L)

    /** Bytes stored so far, including the file being received. */
    fun storedBytes(context: Context, manifest: TransferManifest): Long =
        dir(context, manifest.transferId).listFiles()?.sumOf { it.length() } ?: 0

    fun verify(file: File, entry: TransferFile): Boolean =
        file.isFile && file.length() == entry.bytes && file.inputStream().use(FileDigest::sha256) == entry.sha256

    /** The cheap check before every playback: each file present at its verified size. */
    fun present(context: Context, manifest: TransferManifest): Boolean =
        manifest.files.indices.all { index -> file(context, manifest, index).let { it.isFile && it.length() == manifest.files[index].bytes } }

    /** Deletes every book folder except `keep` (a removed or replaced copy never lingers). */
    fun clear(context: Context, keep: String? = null) {
        root(context).listFiles()?.filter { it.name != keep }?.forEach { it.deleteRecursively() }
    }
}
