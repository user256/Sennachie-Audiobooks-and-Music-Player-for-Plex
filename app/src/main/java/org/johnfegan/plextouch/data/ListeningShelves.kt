package org.johnfegan.plextouch.data

internal fun ListeningProgress.wasListened() = hasListened || elapsedMs > 0 || positionMs > 0 || finished
fun ListeningProgress.belongsToScope(scope: String?): Boolean = accountScope == scope

/** A scope migration is safe only for the current server's immediately preceding token fingerprint. */
fun migrateListeningProgressScope(history: List<ListeningProgress>, server: String, previousScope: String, currentScope: String): List<ListeningProgress> =
    if (previousScope == currentScope) history else history.map { progress ->
        if (progress.server == server && progress.accountScope == previousScope) progress.copy(accountScope = currentScope) else progress
    }

/** Keep audiobook history durably; a busy music library must not evict books. */
fun mergeListeningProgress(history: List<ListeningProgress>, progress: ListeningProgress): List<ListeningProgress> {
    fun matches(other: ListeningProgress) = other.server == progress.server && other.mode == progress.mode && other.album.id == progress.album.id && other.accountScope == progress.accountScope
    val previous = history.firstOrNull(::matches)
    // A completion date survives repeated end-of-book saves and a later replay; a playback save always ends a manual reset.
    val completedAt = if (progress.finished) previous?.takeIf { it.finished }?.completedAt ?: progress.completedAt ?: progress.updatedAt
        else progress.completedAt ?: previous?.completedAt
    val updated = progress.copy(hasListened = progress.wasListened() || history.any { matches(it) && it.wasListened() }, completedAt = completedAt, resetAt = null)
    val items = listOf(updated) + history.filterNot(::matches)
    return (items.filter { it.mode == LibraryMode.AUDIOBOOK } + items.filter { it.mode == LibraryMode.MUSIC }.take(40))
        .sortedByDescending { it.updatedAt }
}

fun listenAgain(history: List<ListeningProgress>, server: String?, scope: String? = null): List<ListeningProgress> =
    history.filter { it.server == server && it.mode == LibraryMode.AUDIOBOOK && it.belongsToScope(scope) && it.wasListened() }
        .sortedByDescending { it.updatedAt }.distinctBy { it.album.id }

/** Prefer fresh/local artwork; in offline mode expose only verified downloads. */
fun shelfAlbums(saved: List<PlexAlbum>, available: List<PlexAlbum>, offlineOnly: Boolean): List<PlexAlbum> {
    val byId = available.associateBy { it.id }
    return saved.mapNotNull { byId[it.id] ?: it.takeUnless { offlineOnly } }
}
