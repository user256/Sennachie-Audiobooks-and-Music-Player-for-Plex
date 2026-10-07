package org.johnfegan.plextouch.wear.shared

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Ticket 141: the Wear Data Layer contract between the phone app and the watch app.
 *
 * Every message travels on [WearPaths.MESSAGE] as a small JSON envelope `{"v":1,"type":"…","body":{…}}`; the phone's
 * state is a data item on [WearPaths.STATE] with the same envelope. Nothing in the protocol carries a Plex token, a
 * server address, a stream URL or an account fingerprint: the phone stays the only Plex client, and a watch transfer is
 * named by an opaque [TransferManifest.transferId] that only the phone can map back to an account and server.
 */
object WearPaths {
    const val MESSAGE = "/plextouch/v1/message"
    const val STATE = "/plextouch/v1/state"
    const val FILE_PREFIX = "/plextouch/v1/file/"
    /** Declared by the phone app (res/values/wear.xml) so the watch can find a phone with the app installed. */
    const val PHONE_CAPABILITY = "plextouch_phone"
    /** Declared by the watch app so the phone publishes state only when a watch with the app is paired. */
    const val WATCH_CAPABILITY = "plextouch_watch"

    fun filePath(transferId: String, index: Int): String = "$FILE_PREFIX$transferId/$index"

    /** The transfer id and file index of a channel path, or null for anything that is not a well-formed file path. */
    fun parseFilePath(path: String?): Pair<String, Int>? {
        if (path == null || !path.startsWith(FILE_PREFIX)) return null
        val parts = path.removePrefix(FILE_PREFIX).split('/')
        if (parts.size != 2 || !TRANSFER_ID.matches(parts[0])) return null
        val index = parts[1].toIntOrNull()?.takeIf { it in 0 until WatchTransferBounds.MAX_FILES } ?: return null
        return parts[0] to index
    }

    val TRANSFER_ID = Regex("[A-Za-z0-9-]{8,64}")
}

/** What the watch asks the phone's media session to do. */
enum class WearAction { TOGGLE, PLAY, PAUSE, BACK_30, FORWARD_30, PREVIOUS_CHAPTER, NEXT_CHAPTER, SPEED }

enum class CommandOutcome { DONE, NOTHING_LOADED, NOT_AUDIOBOOK, FAILED }

/** Why the phone (or the watch, before asking) refuses to put a book on the watch. */
enum class TransferRefusal { SIGNED_OUT, NOT_ON_LIST, NOT_DOWNLOADED, TOO_LARGE, TOO_MANY_FILES, INSUFFICIENT_SPACE, ALREADY_HOLDING, BUSY, FAILED }

/** What the phone did with a watch checkpoint; every outcome lets the watch drop it from its queue. */
enum class ReceiptOutcome {
    /** Joined the phone's timeline outbox (and its local history); Plex sees it when the outbox drains. */
    QUEUED,
    /** The phone has listened to this book since the watch did; the phone's position is kept. */
    STALE,
    /** The phone is now signed in to another account or server than the one the book was sent from. */
    SCOPE_CHANGED,
    /** Too early in the file, at its very end, or outside the book: never synced, never a completion. */
    IGNORED,
    /** The phone no longer knows this transfer (removed, or the app's data was cleared). */
    UNKNOWN_TRANSFER,
}

enum class PhoneStatus { SIGNED_OUT, IDLE, LOADED }

sealed interface WearMessage {
    /** False when Gson left a required field null (a damaged or foreign message); such a message is ignored. */
    fun wellFormed(): Boolean
}

data class Command(val action: WearAction, val speed: Float? = null) : WearMessage {
    @Suppress("SENSELESS_COMPARISON")
    override fun wellFormed() = action != null && (action != WearAction.SPEED || (speed != null && speed.isFinite()))
}

data class CommandResult(val action: WearAction, val outcome: CommandOutcome) : WearMessage {
    @Suppress("SENSELESS_COMPARISON")
    override fun wellFormed() = action != null && outcome != null
}

/** Asks the phone to republish its state; [heldAlbumId] names the book the watch holds so the phone can include its position. */
data class RequestState(val heldAlbumId: String? = null) : WearMessage {
    override fun wellFormed() = true
}

data class RequestTransfer(val albumId: String) : WearMessage {
    @Suppress("SENSELESS_COMPARISON")
    override fun wellFormed() = albumId != null && albumId.isNotBlank()
}

data class TransferRefused(val albumId: String, val reason: TransferRefusal) : WearMessage {
    @Suppress("SENSELESS_COMPARISON")
    override fun wellFormed() = albumId != null && reason != null
}

/** One audio file of a transferred book. [chapterStartsMs] are the embedded chapter markers of a single-file book. */
data class TransferFile(
    val index: Int,
    val trackId: String,
    val title: String,
    val durationMs: Long,
    val bytes: Long,
    val sha256: String,
    val extension: String,
    val chapterStartsMs: List<Long> = emptyList(),
) {
    /** Gson leaves a missing list null. */
    @Suppress("USELESS_ELVIS")
    val markers: List<Long> get() = chapterStartsMs ?: emptyList()

    @Suppress("SENSELESS_COMPARISON")
    fun wellFormed() = trackId != null && TRACK_ID.matches(trackId) && title != null && bytes > 0 && durationMs >= 0 &&
        sha256 != null && SHA256.matches(sha256) && extension != null && EXTENSION.matches(extension) &&
        (chapterStartsMs == null || chapterStartsMs.size <= WatchTransferBounds.MAX_MARKERS)

    companion object {
        val TRACK_ID = Regex("[0-9]{1,20}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val EXTENSION = Regex("[A-Za-z0-9]{1,8}")
    }
}

/**
 * Sent by the phone before any file: what the watch is about to receive and where to resume. [resumeIndex] and
 * [resumePositionMs] are the phone's saved position when the transfer started (the first file at 0 when there is none).
 */
data class TransferManifest(
    val transferId: String,
    val albumId: String,
    val title: String,
    val author: String,
    val files: List<TransferFile>,
    val resumeIndex: Int,
    val resumePositionMs: Long,
    val createdAt: Long,
) : WearMessage {
    val totalBytes: Long get() = files.sumOf { it.bytes }

    @Suppress("SENSELESS_COMPARISON")
    override fun wellFormed() = transferId != null && WearPaths.TRANSFER_ID.matches(transferId) && albumId != null && title != null &&
        author != null && files != null && files.isNotEmpty() && files.size <= WatchTransferBounds.MAX_FILES &&
        files.all { it != null && it.wellFormed() } && files.map { it.index } == files.indices.toList() &&
        resumeIndex in files.indices && resumePositionMs >= 0 && totalBytes <= WatchTransferBounds.MAX_BOOK_BYTES
}

/**
 * The watch pulls files one at a time after it has accepted a manifest, so the phone never runs a long transfer job: each
 * request opens one channel and hands the file to Play services. A retry after a failed or interrupted file is the same
 * request again.
 */
data class RequestFile(val transferId: String, val index: Int) : WearMessage {
    @Suppress("SENSELESS_COMPARISON")
    override fun wellFormed() = transferId != null && WearPaths.TRANSFER_ID.matches(transferId) && index in 0 until WatchTransferBounds.MAX_FILES
}

/** A position the watch played to, for the phone's outbox. Never a completion: there is no "finished" field at all. */
data class Checkpoint(
    val id: String,
    val transferId: String,
    val trackId: String,
    val positionMs: Long,
    val durationMs: Long,
    /** Where the watch started this file: the manifest's resume offset for the first file, otherwise 0. */
    val originPositionMs: Long,
    val capturedAt: Long,
    val sessionId: String,
    val playing: Boolean,
) : WearMessage {
    @Suppress("SENSELESS_COMPARISON")
    override fun wellFormed() = id != null && id.isNotBlank() && transferId != null && trackId != null &&
        TransferFile.TRACK_ID.matches(trackId) && sessionId != null && positionMs >= 0 && originPositionMs >= 0 && durationMs >= 0
}

data class CheckpointReceipt(val id: String, val outcome: ReceiptOutcome) : WearMessage {
    @Suppress("SENSELESS_COMPARISON")
    override fun wellFormed() = id != null && outcome != null
}

/** The watch deleted its copy; the phone forgets the transfer so late checkpoints are refused. */
data class TransferRemoved(val transferId: String) : WearMessage {
    @Suppress("SENSELESS_COMPARISON")
    override fun wellFormed() = transferId != null
}

/** The phone's now-playing state; [chapterTitle] is server text (or null), the watch labels untitled chapters itself. */
data class NowPlaying(
    val albumId: String,
    val title: String,
    val author: String,
    val audiobook: Boolean,
    val playing: Boolean,
    val live: Boolean,
    val chapterTitle: String?,
    val chapterNumber: Int,
    val chapterCount: Int,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val bookPermille: Int,
)

/** A reading-list title; [downloadedBytes] is set only when the phone holds a complete download of it. */
data class ListEntry(val albumId: String, val title: String, val author: String, val downloadedBytes: Long?)

/** The phone's own saved position in the book the watch holds, so the watch can offer a choice instead of overwriting. */
data class HeldBookPosition(val albumId: String, val trackId: String?, val positionMs: Long, val updatedAt: Long, val finished: Boolean)

data class PhoneState(
    val status: PhoneStatus,
    val nowPlaying: NowPlaying? = null,
    val readingList: List<ListEntry> = emptyList(),
    val heldBook: HeldBookPosition? = null,
    val capturedAt: Long = 0,
) : WearMessage {
    @Suppress("SENSELESS_COMPARISON")
    override fun wellFormed() = status != null && (readingList == null || readingList.size <= WatchTransferBounds.MAX_LIST_ENTRIES) &&
        (status != PhoneStatus.LOADED || nowPlaying != null)

    /** Gson leaves a missing list null; callers read this instead. */
    @Suppress("USELESS_ELVIS")
    val entries: List<ListEntry> get() = (readingList ?: emptyList()).filterNotNull()
}

sealed interface Decoded {
    data class Message(val message: WearMessage) : Decoded
    /** A newer phone or watch sent a type this build does not know; ignored, never an error shown to the user. */
    data class Unknown(val type: String) : Decoded
    /** The other side speaks a newer protocol version; the UI asks for both apps to be updated. */
    data class Unsupported(val version: Int) : Decoded
    data object Malformed : Decoded
}

object WearCodec {
    /** Bumped only for an incompatible change; new optional fields and new message types keep version 1. */
    const val VERSION = 1
    /** The Data Layer's message limit is ~100 KB; anything bigger than this is refused before it is sent or parsed. */
    const val MAX_BYTES = 90_000

    private val gson = Gson()
    private val types: Map<String, Class<out WearMessage>> = mapOf(
        "command" to Command::class.java,
        "commandResult" to CommandResult::class.java,
        "requestState" to RequestState::class.java,
        "requestTransfer" to RequestTransfer::class.java,
        "transferRefused" to TransferRefused::class.java,
        "manifest" to TransferManifest::class.java,
        "requestFile" to RequestFile::class.java,
        "checkpoint" to Checkpoint::class.java,
        "receipt" to CheckpointReceipt::class.java,
        "transferRemoved" to TransferRemoved::class.java,
        "state" to PhoneState::class.java,
    )
    private val names: Map<Class<out WearMessage>, String> = types.entries.associate { (name, type) -> type to name }

    fun encode(message: WearMessage): ByteArray {
        val envelope = JsonObject().apply {
            addProperty("v", VERSION)
            addProperty("type", names.getValue(message.javaClass))
            add("body", gson.toJsonTree(message))
        }
        val bytes = gson.toJson(envelope).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Wear message too large" }
        return bytes
    }

    fun decode(bytes: ByteArray?): Decoded {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) return Decoded.Malformed
        return try {
            val envelope = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            val version = envelope.get("v")?.asInt ?: return Decoded.Malformed
            if (version > VERSION) return Decoded.Unsupported(version)
            if (version < 1) return Decoded.Malformed
            val type = envelope.get("type")?.asString ?: return Decoded.Malformed
            val kind = types[type] ?: return Decoded.Unknown(type)
            val body = envelope.get("body")?.takeIf { it.isJsonObject } ?: return Decoded.Malformed
            val message = gson.fromJson(body, kind) ?: return Decoded.Malformed
            if (message.wellFormed()) Decoded.Message(message) else Decoded.Malformed
        } catch (_: RuntimeException) {
            Decoded.Malformed
        }
    }
}
