package org.johnfegan.plextouch.wear

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson

/**
 * The phone's records of books sent to the watch (ticket 141), newest first and bounded: the watch holds one book, and a
 * few older records let a late checkpoint from a replaced copy still be recognised and refused cleanly. A record holds
 * an account fingerprint, the server address, the album snapshot and the file list with hashes; no token.
 */
class WatchTransferStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun records(): List<WatchTransferRecord> = synchronized(lock) { load() }

    fun find(transferId: String): WatchTransferRecord? = records().firstOrNull { it.transferId == transferId }

    fun put(record: WatchTransferRecord) = synchronized(lock) {
        save((listOf(record) + load().filterNot { it.transferId == record.transferId }).take(MAX_RECORDS))
    }

    fun remove(transferId: String) = synchronized(lock) { save(load().filterNot { it.transferId == transferId }) }

    @Suppress("SENSELESS_COMPARISON")
    private fun load(): List<WatchTransferRecord> = runCatching {
        gson.fromJson(prefs.getString(RECORDS, "[]"), Array<WatchTransferRecord>::class.java).toList()
            .filter { it != null && it.transferId != null && it.scope != null && it.server != null && it.album != null && it.files != null }
    }.getOrDefault(emptyList())

    private fun save(records: List<WatchTransferRecord>) = prefs.edit { putString(RECORDS, gson.toJson(records)) }

    private companion object {
        const val PREFS = "plex_touch_wear"
        const val RECORDS = "watch_transfers"
        const val MAX_RECORDS = 3
        val lock = Any()
    }
}
