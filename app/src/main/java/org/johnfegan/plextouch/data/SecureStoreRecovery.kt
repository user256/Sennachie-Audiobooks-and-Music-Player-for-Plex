package org.johnfegan.plextouch.data

/**
 * Open a keystore-backed store, resetting it once when the saved keyset no longer matches this device
 * (a restored backup, a rotated or damaged keystore). A failure on the second, empty attempt propagates:
 * that is a real fault rather than stale data, and hiding it would leave the app silently credential-less.
 *
 * @return the opened store and whether it had to be wiped to get there.
 */
fun <T> secureStoreRecovery(create: () -> T, wipe: (Throwable) -> Unit): Pair<T, Boolean> =
    try {
        create() to false
    } catch (error: Exception) {
        wipe(error)
        create() to true
    }
