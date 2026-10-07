package org.johnfegan.plextouch.data

import java.io.IOException
import kotlinx.coroutines.delay
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.InvalidStateException
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText

interface PlexLoginStore {
    fun pendingPin(): PendingPlexPin?
    fun savePendingPin(pin: PendingPlexPin)
    fun clearPendingPin()
    fun accountToken(): String?
    fun saveAccountToken(token: String)
}

/** Durable device linking. Cancellation never invalidates a code or a linked account. */
class PlexSignIn(
    private val store: PlexLoginStore,
    private val create: suspend () -> PendingPlexPin,
    private val poll: suspend (PendingPlexPin) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun signIn(status: (PendingPlexPin?, UiText) -> Unit): String {
        store.accountToken()?.takeIf { it.isNotBlank() }?.let { return it }
        val saved = store.pendingPin()
        val pin = if (saved != null && saved.expiresAtMillis > now()) saved else {
            store.clearPendingPin()
            create().also(store::savePendingPin)
        }
        while (now() < pin.expiresAtMillis) {
            status(pin, uiText(R.string.signin_enter_code))
            try {
                val token = poll(pin)?.trim()?.takeIf { it.isNotEmpty() }
                if (token != null) {
                    // Store the account before discovery; a server/network error must not lose login.
                    store.saveAccountToken(token)
                    return token
                }
            } catch (error: IOException) {
                // A definite refusal (4xx other than 429) must surface, not spin until the code expires.
                if (error is PlexHttpException && !error.retryable) throw error
                status(pin, uiText(R.string.signin_retrying))
                pause(3_000)
            }
            pause(1_500)
        }
        store.clearPendingPin()
        throw InvalidStateException(uiText(R.string.signin_code_expired))
    }
}
