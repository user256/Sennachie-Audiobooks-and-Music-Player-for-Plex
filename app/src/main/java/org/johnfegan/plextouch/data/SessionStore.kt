package org.johnfegan.plextouch.data

import org.johnfegan.plextouch.sonos.SonosSpeaker

/** The listening-progress slice of [PlexStore]: history, account scope, timeline conflicts and playback speed. */
interface ProgressStore {
    fun history(): List<ListeningProgress>
    fun progressScope(connection: PlexConnection): String
    fun migrateSavedProgressScope(connection: PlexConnection)
    fun removeProgress(server: String, albumId: String)
    fun resolveTimeline(scope: String, server: String, albumId: String, remote: PlexChapterProgress)
    fun progressConflicts(scope: String, server: String): List<ProgressSyncConflict>
    fun playbackSpeed(mode: LibraryMode): Float
    fun savePlaybackSpeed(mode: LibraryMode, value: Float)
    /** A book's own speed (ticket 133), keyed by account scope, server and album; null when it follows the default. */
    fun bookSpeed(scope: String, server: String, albumId: String): Float?
    /** Saves a book's speed; null resets it to the default. */
    fun saveBookSpeed(scope: String, server: String, albumId: String, speed: Float?)
}

/** Ticket 138: the local listening log and the history controls. Nothing here writes to Plex or the timeline outbox. */
interface ListeningHistoryStore {
    fun history(): List<ListeningProgress>
    fun listeningLog(): List<ListeningDay>
    /** Starts one audiobook over on this phone (see [resetBookProgress]). */
    fun resetBookProgress(server: String, scope: String?, albumId: String, at: Long)
    /** Removes this account's progress and listening log on this server only. */
    fun clearListeningHistory(server: String, scope: String?)
}

/** The speed `album` starts or resumes at: its override when it is an audiobook with one, else the mode's default. */
fun ProgressStore.effectiveSpeed(scope: String, server: String, albumId: String, mode: LibraryMode): Float =
    BookSpeeds.effective(mode, if (mode == LibraryMode.AUDIOBOOK) bookSpeed(scope, server, albumId) else null, playbackSpeed(mode))

/** Everything else the app's orchestration reads or writes in [PlexStore]: the connection, preferences and speakers. */
interface SessionStore : PlexLoginStore, ProgressStore, ListeningHistoryStore {
    fun consumeRecoveryNotice(): Boolean
    fun connection(): PlexConnection?
    fun saveConnection(url: String, token: String)
    fun signOut()
    /**
     * A Plex Home switch (ticket 136): quarantines queued timeline events of other accounts, then saves the switched user's
     * token and server route together under the store lock. Throws (leaving the old sign-in) if the write fails.
     */
    fun switchAccount(accountToken: String, serverUrl: String, serverToken: String)
    fun library(mode: LibraryMode): String?
    fun saveLibrary(mode: LibraryMode, id: String)
    /** A non-secret, temporary first-run choice. Null means setup is complete (or this is a pre-wizard install). */
    fun setupPlan(): SetupPlan? = null
    fun saveSetupPlan(plan: SetupPlan?) {}
    fun mode(): LibraryMode
    fun saveMode(mode: LibraryMode)
    fun offlineOnly(): Boolean
    /** Smart rewind after an audiobook pause, in seconds; 0 is Off. */
    fun smartRewindSeconds(): Int
    fun saveSmartRewindSeconds(seconds: Int)
    fun saveOfflineOnly(value: Boolean)
    fun speakers(): List<SonosSpeaker>
    fun saveSpeakers(speakers: List<SonosSpeaker>)
    fun householdDirectoryToken(): String
    fun saveHouseholdDirectoryToken(token: String)
    fun clientIdentifier(): String
    /** Phone sound processing (ticket 135); global, read by the playback service when it starts. */
    fun audioEffects(): AudioEffectsSettings
    fun saveAudioEffects(settings: AudioEffectsSettings)
}
