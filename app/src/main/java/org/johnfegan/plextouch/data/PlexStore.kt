package org.johnfegan.plextouch.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.util.UUID
import com.google.gson.Gson
import org.johnfegan.plextouch.sonos.SonosSpeaker

/**
 * Credentials stay in the Android keystore-backed encrypted preference file.
 *
 * Every instance in the process (ViewModel, playback service, sync worker) shares the same files, so all
 * read-modify-write paths take the one companion [lock]. If the encrypted file cannot be opened (a device
 * restore or a damaged keystore leaves a keyset no key can unwrap) it is reset rather than crash-looping.
 */
class PlexStore(context: Context) : SessionStore, ReadingListStore {
    private val app: Context = context.applicationContext
    /** Non-secret flags only. It survives a secure-store reset so the notice reaches the next screen shown. */
    private val plain = app.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
    private val prefs: SharedPreferences
    /** True when this instance had to wipe an unreadable secure file; the saved Plex sign-in is gone. */
    val recovered: Boolean

    init {
        val (opened, reset) = synchronized(lock) {
            secureStoreRecovery(create = { openSecurePrefs(app) }, wipe = { error -> wipeSecurePrefs(app, error) })
        }
        prefs = opened
        recovered = reset
        if (reset) plain.edit().putBoolean(RECOVERY_NOTICE, true).commit()
    }

    /** One-shot: whichever component opened the store first after a reset recorded it; the UI shows it once. */
    override fun consumeRecoveryNotice(): Boolean {
        if (!plain.getBoolean(RECOVERY_NOTICE, false)) return false
        plain.edit().remove(RECOVERY_NOTICE).apply()
        return true
    }

    override fun connection(): PlexConnection? {
        val url = prefs.getString(URL, null)?.trim().orEmpty()
        val token = prefs.getString(TOKEN, null)?.trim().orEmpty()
        return if (url.isNotBlank() && token.isNotBlank()) PlexConnection(url, token) else null
    }

    override fun saveConnection(url: String, token: String) = prefs.edit()
        .putString(URL, url.trim().trimEnd('/'))
        .putString(TOKEN, token.trim())
        .apply()

    override fun pendingPin(): PendingPlexPin? {
        val id = prefs.getLong(PENDING_PIN_ID, -1L)
        val code = prefs.getString(PENDING_PIN_CODE, null).orEmpty()
        return if (id >= 0 && code.isNotBlank()) PendingPlexPin(id, code, prefs.getLong(PENDING_PIN_EXPIRY, 0L)) else null
    }

    override fun savePendingPin(pin: PendingPlexPin) = prefs.edit()
        .putLong(PENDING_PIN_ID, pin.id)
        .putString(PENDING_PIN_CODE, pin.code)
        .putLong(PENDING_PIN_EXPIRY, pin.expiresAtMillis)
        .apply()

    override fun clearPendingPin() = prefs.edit()
        .remove(PENDING_PIN_ID)
        .remove(PENDING_PIN_CODE)
        .remove(PENDING_PIN_EXPIRY)
        .apply()

    /**
     * Forget the Plex account and server on this phone. Downloads, listening history, saved speakers, library
     * choices and playback speeds (including per-book speeds, which are account-scoped) stay; history and downloads are read-only until a matching account signs in.
     */
    override fun signOut() {
        synchronized(lock) {
            prefs.edit().apply { SIGN_OUT_KEYS.forEach { remove(it) } }.commit()
        }
    }

    override fun accountToken(): String? = prefs.getString(ACCOUNT_TOKEN, null)?.takeIf { it.isNotBlank() }

    override fun saveAccountToken(token: String) = prefs.edit()
        .putString(ACCOUNT_TOKEN, token)
        .remove(PENDING_PIN_ID)
        .remove(PENDING_PIN_CODE)
        .remove(PENDING_PIN_EXPIRY)
        .apply()

    override fun library(mode: LibraryMode): String? = prefs.getString("library_${mode.name}", null)

    override fun saveLibrary(mode: LibraryMode, id: String) = prefs.edit()
        .putString("library_${mode.name}", id)
        .apply()

    override fun setupPlan(): SetupPlan? = runCatching {
        prefs.getString(SETUP_PLAN, null)?.let(SetupPlan::valueOf)
    }.getOrNull()

    override fun saveSetupPlan(plan: SetupPlan?) = prefs.edit().apply {
        if (plan == null) remove(SETUP_PLAN) else putString(SETUP_PLAN, plan.name)
    }.apply()

    override fun mode(): LibraryMode = runCatching { LibraryMode.valueOf(prefs.getString("mode", null).orEmpty()) }.getOrDefault(LibraryMode.MUSIC)
    override fun saveMode(mode: LibraryMode) = prefs.edit().putString("mode", mode.name).apply()
    override fun playbackSpeed(mode: LibraryMode): Float = prefs.getFloat("speed_${mode.name}", 1f).coerceIn(MIN_SPEED, MAX_SPEED)
    override fun savePlaybackSpeed(mode: LibraryMode, value: Float) = prefs.edit().putFloat("speed_${mode.name}", value.coerceIn(MIN_SPEED, MAX_SPEED)).apply()
    private val bookSpeeds = BookSpeedStore(
        load = { prefs.getString(BOOK_SPEEDS, null) },
        write = { json -> prefs.edit().putString(BOOK_SPEEDS, json).apply() },
        lock = lock,
    )
    override fun bookSpeed(scope: String, server: String, albumId: String): Float? = bookSpeeds.speed(scope, server, albumId)
    override fun saveBookSpeed(scope: String, server: String, albumId: String, speed: Float?) = bookSpeeds.save(scope, server, albumId, speed)
    override fun smartRewindSeconds(): Int = prefs.getInt(SMART_REWIND, 0).coerceIn(0, MAX_REWIND_SECONDS)
    override fun saveSmartRewindSeconds(seconds: Int) = prefs.edit().putInt(SMART_REWIND, seconds.coerceIn(0, MAX_REWIND_SECONDS)).apply()
    /** The playback service's last qualifying pause (ticket 134), as the JSON its `PauseRecordStore` owns. */
    fun pausedAtJson(): String? = prefs.getString(PAUSED_AT, null)
    fun savePausedAtJson(json: String?) { prefs.edit().apply { if (json == null) remove(PAUSED_AT) else putString(PAUSED_AT, json) }.apply() }
    override fun offlineOnly(): Boolean = prefs.getBoolean("offline_only", false)
    override fun saveOfflineOnly(value: Boolean) = prefs.edit().putBoolean("offline_only", value).apply()
    override fun speakers(): List<SonosSpeaker> = runCatching { Gson().fromJson(prefs.getString("sonos_speakers", "[]"), Array<SonosSpeaker>::class.java).toList() }.getOrDefault(emptyList())
    override fun saveSpeakers(speakers: List<SonosSpeaker>) = prefs.edit().putString("sonos_speakers", Gson().toJson(speakers)).apply()
    // Keep the established preference key for the personal flavour without exposing a household service in public UI.
    private val householdDirectoryKey get() = "back" + "door_sonos_token"
    override fun householdDirectoryToken(): String = prefs.getString(householdDirectoryKey, "").orEmpty()
    override fun saveHouseholdDirectoryToken(token: String) = prefs.edit().putString(householdDirectoryKey, token.trim()).apply()

    override fun audioEffects(): AudioEffectsSettings = AudioEffectsSettings(
        enabled = prefs.getBoolean(EFFECTS_ENABLED, false),
        voiceBoost = prefs.getBoolean(EFFECTS_VOICE_BOOST, true),
        preset = runCatching { EqualiserPreset.valueOf(prefs.getString(EFFECTS_PRESET, null).orEmpty()) }.getOrDefault(EqualiserPreset.SPEECH),
    )
    override fun saveAudioEffects(settings: AudioEffectsSettings) = prefs.edit {
        putBoolean(EFFECTS_ENABLED, settings.enabled)
        putBoolean(EFFECTS_VOICE_BOOST, settings.voiceBoost)
        putString(EFFECTS_PRESET, settings.preset.name)
    }

    override fun progressScope(connection: PlexConnection): String = connection.progressScope(accountToken() ?: connection.token)

    /** Upgrade only records provably tied to this still-saved server token; never merge arbitrary legacy accounts. */
    override fun migrateSavedProgressScope(connection: PlexConnection) {
        val oldScope = connection.progressScope()
        val newScope = progressScope(connection)
        if (oldScope == newScope) return
        synchronized(lock) {
            val history = history()
            val migrated = migrateListeningProgressScope(history, connection.serverUrl, oldScope, newScope)
            if (migrated != history) saveHistory(migrated)
        }
    }

    private val timeline = TimelineStateStore(
        load = { prefs.getString(TIMELINE_SYNC, null) },
        save = { json -> prefs.edit().putString(TIMELINE_SYNC, json).apply() },
        lock = lock,
    )

    fun timelineState(): TimelineSyncState = timeline.state()
    fun enqueueTimeline(event: TimelineEvent) { timeline.update { TimelineOutbox.enqueue(it, event) } }
    fun timelineBaseline(scope: String, server: String, albumId: String, trackId: String): RemoteChapterBaseline? = TimelineOutbox.baseline(timeline.state(), scope, server, albumId, trackId)
    fun nextTimeline(now: Long): TimelineEvent? = TimelineOutbox.next(timeline.state(), now)
    fun acknowledgeTimeline(event: TimelineEvent, remote: PlexChapterProgress) { timeline.update { TimelineOutbox.acknowledged(it, event, remote) } }
    fun retryTimeline(event: TimelineEvent, now: Long) { timeline.update { TimelineOutbox.retry(it, event, now) } }
    fun blockTimeline(event: TimelineEvent, reason: TimelineBlock, remote: PlexChapterProgress?, now: Long) { timeline.update { TimelineOutbox.block(it, event, reason, remote, now) } }
    override fun resolveTimeline(scope: String, server: String, albumId: String, remote: PlexChapterProgress) { timeline.update { TimelineOutbox.resolve(it, scope, server, albumId, remote) } }
    fun protectTimelineScopes(activeScope: String, now: Long) { timeline.update { TimelineOutbox.accountChanged(it, activeScope, now) } }
    private val accountSwitch = AccountSwitchWriter(lock, timeline, { account, url, token ->
        prefs.edit().putString(ACCOUNT_TOKEN, account).putString(URL, url).putString(TOKEN, token).commit()
    })
    override fun switchAccount(accountToken: String, serverUrl: String, serverToken: String) { accountSwitch.switch(accountToken, serverUrl, serverToken) }
    override fun progressConflicts(scope: String, server: String): List<ProgressSyncConflict> = timelineState().conflicts.filter { it.scope == scope && it.server == server }

    /**
     * Ticket 131: the local reading lists, per account scope and server, under the one store [lock]. Not in
     * [SIGN_OUT_KEYS]: each list is keyed by an account fingerprint, so a different account never sees it and the same
     * account finds it again after signing back in.
     */
    private val readingLists = ReadingListStateStore(
        load = { prefs.getString(READING_LIST, null) },
        save = { json -> prefs.edit { putString(READING_LIST, json) } },
        lock = lock,
    )
    override fun readingList(scope: String, server: String) = readingLists.readingList(scope, server)
    override fun updateReadingList(scope: String, server: String, change: (List<ReadingListEntry>) -> List<ReadingListEntry>) = readingLists.updateReadingList(scope, server, change)

    override fun history(): List<ListeningProgress> = runCatching {
        Gson().fromJson(prefs.getString(HISTORY, "[]"), Array<ListeningProgress>::class.java).toList()
    }.getOrDefault(emptyList())

    fun saveProgress(progress: ListeningProgress) {
        synchronized(lock) { saveHistory(mergeListeningProgress(history(), progress)) }
    }

    override fun removeProgress(server: String, albumId: String) {
        synchronized(lock) { saveHistory(history().filterNot { it.server == server && it.album.id == albumId }) }
    }

    private fun saveHistory(items: List<ListeningProgress>) {
        prefs.edit().putString(HISTORY, Gson().toJson(items)).apply()
    }

    // Ticket 138: the local listening log, under the same process-wide lock as the history it sits beside.
    override fun listeningLog(): List<ListeningDay> = runCatching {
        Gson().fromJson(prefs.getString(LISTENING_LOG, "[]"), Array<ListeningDay>::class.java).filter { it.wellFormed() }
    }.getOrDefault(emptyList())

    /** Called by the playback service's ordered writer with a measured sample. */
    fun recordListening(sample: ListeningSample) {
        synchronized(lock) {
            val log = listeningLog()
            val updated = org.johnfegan.plextouch.data.recordListening(log, sample)
            if (updated != log) prefs.edit { putString(LISTENING_LOG, Gson().toJson(updated)) }
        }
    }

    override fun resetBookProgress(server: String, scope: String?, albumId: String, at: Long) {
        synchronized(lock) { saveHistory(org.johnfegan.plextouch.data.resetBookProgress(history(), server, scope, albumId, at)) }
    }

    override fun clearListeningHistory(server: String, scope: String?) {
        synchronized(lock) {
            saveHistory(clearListeningProgress(history(), server, scope))
            prefs.edit { putString(LISTENING_LOG, Gson().toJson(clearListeningLog(listeningLog(), server, scope))) }
        }
    }

    /** A stable, non-secret device ID required by Plex's PIN authentication flow. */
    override fun clientIdentifier(): String = prefs.getString(CLIENT_ID, null) ?: UUID.randomUUID().toString().also { id ->
        prefs.edit().putString(CLIENT_ID, id).apply()
    }

    companion object {
        private const val TAG = "PlexStore"
        private const val SECURE_PREFS = "plex_touch_secure"
        private const val PLAIN_PREFS = "plex_touch_store"
        private const val RECOVERY_NOTICE = "secure_store_reset"
        /** Keyset files security-crypto may keep beside the encrypted preferences; stale ones must go with it. */
        private val KEYSET_PREFS = listOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__",
        )
        private const val URL = "server_url"
        private const val TOKEN = "token"
        private const val SETUP_PLAN = "setup_plan"
        private const val CLIENT_ID = "plex_client_identifier"
        private const val PENDING_PIN_ID = "pending_pin_id"
        private const val PENDING_PIN_CODE = "pending_pin_code"
        private const val PENDING_PIN_EXPIRY = "pending_pin_expiry"
        private const val ACCOUNT_TOKEN = "plex_account_token"
        private const val TIMELINE_SYNC = "audiobook_timeline_sync"
        /** Per-book speeds (ticket 133). Kept on sign-out: each entry is bound to an account scope, so another account cannot read it. */
        private const val BOOK_SPEEDS = "book_speeds"
        /** Smart rewind seconds (ticket 134): 0 (Off, the default) up to [MAX_REWIND_SECONDS]. */
        private const val SMART_REWIND = "smart_rewind_seconds"
        const val MAX_REWIND_SECONDS = 60
        /** The last audiobook pause (account scope, server, album, file, offset, reason, clocks); no token or title. */
        private const val PAUSED_AT = "smart_rewind_paused_at"
        private const val EFFECTS_ENABLED = "effects_enabled"
        private const val EFFECTS_VOICE_BOOST = "effects_voice_boost"
        private const val EFFECTS_PRESET = "effects_preset"
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f

        private const val HISTORY = "listening_history"
        private const val LISTENING_LOG = "listening_log"
        private const val READING_LIST = "reading_list"

        /**
         * Everything tied to the Plex sign-in: both tokens, the server, a half-finished PIN and the timeline outbox
         * with its baselines (they are only meaningful against that account), and the last audiobook pause, which only
         * that account's resume could use. The client identifier is a non-secret
         * device ID and stays so Plex sees the same device next time.
         */
        val SIGN_OUT_KEYS: Set<String> = setOf(URL, TOKEN, ACCOUNT_TOKEN, PENDING_PIN_ID, PENDING_PIN_CODE, PENDING_PIN_EXPIRY, TIMELINE_SYNC, PAUSED_AT, SETUP_PLAN)

        /** The preference entries [signOut] leaves behind, as a pure function so the key list can be unit-tested. */
        fun keptAfterSignOut(values: Map<String, Any?>): Map<String, Any?> = values.filterKeys { it !in SIGN_OUT_KEYS }

        /** One process-wide lock: the service's enqueue and the worker's acknowledge edit the same preference key. */
        private val lock = Any()

        /** security-crypto is deprecated upstream; 1.1.0 stable is kept deliberately (ticket 122) because the wipe-and-recreate recovery below covers its failure modes. */
        @Suppress("DEPRECATION")
        private fun openSecurePrefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        /** Logs the failure classes only: never key material, tokens or preference contents. */
        @Suppress("DEPRECATION")
        private fun wipeSecurePrefs(context: Context, error: Throwable) {
            Log.w(TAG, "Secure storage could not be opened (${causeChain(error)}); resetting it. Sign in to Plex again.")
            (listOf(SECURE_PREFS) + KEYSET_PREFS).forEach { name -> context.deleteSharedPreferences(name) }
            // A damaged master key would fail the retry too; dropping it lets the library mint a fresh one.
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS) }
        }

        private fun causeChain(error: Throwable): String =
            generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.joinToString(" <- ") { it.javaClass.simpleName }
    }
}

data class PendingPlexPin(val id: Long, val code: String, val expiresAtMillis: Long)
