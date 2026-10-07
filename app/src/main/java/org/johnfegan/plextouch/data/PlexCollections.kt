package org.johnfegan.plextouch.data

import androidx.compose.runtime.Immutable

/**
 * A Plex collection in an audiobook library (ticket 131). `childCount` is Plex's own count, which may include items of
 * other media types; the books the app shows are filtered when the children load (see [PlexGateway.collectionBooks]).
 * Membership and smart rules are read-only here: the app never edits a collection.
 */
@Immutable
data class PlexCollection(
    val id: String,
    val title: String,
    val childCount: Int,
    val thumb: String? = null,
    val smart: Boolean = false,
    val subtype: String? = null,
)

/** A parsed collection list from the browsing cache; `fresh` means it may be shown without asking Plex again. */
data class CachedCollectionList(val collections: List<PlexCollection>, val fresh: Boolean)
