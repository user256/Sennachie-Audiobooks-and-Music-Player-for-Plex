package org.johnfegan.plextouch.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.uiText
import org.johnfegan.plextouch.ui.UserFacing

class PlexSignInTest {
    private class Store : PlexLoginStore {
        var pin: PendingPlexPin? = null
        var token: String? = null
        override fun pendingPin() = pin
        override fun savePendingPin(pin: PendingPlexPin) { this.pin = pin }
        override fun clearPendingPin() { pin = null }
        override fun accountToken() = token
        override fun saveAccountToken(token: String) { this.token = token; pin = null }
    }

    @Test fun transientNetworkErrorsAndBlankTokensKeepSameCode() = runBlocking {
        val store = Store()
        var clock = 0L
        var calls = 0
        var creates = 0
        val signIn = PlexSignIn(store, {
            creates++
            PendingPlexPin(1, "CODE", 900_000)
        }, { pin ->
            assertEquals(store.pin, pin)
            when (calls++) {
                0 -> throw IOException("DNS temporarily unavailable")
                1 -> "  "
                2 -> null
                else -> "account-token"
            }
        }, { clock }, { clock += it })
        assertEquals("account-token", signIn.signIn { _, _ -> })
        assertEquals(1, creates)
        assertEquals(4, calls)
        assertNull(store.pin)
        assertEquals("account-token", store.token)
    }

    @Test fun cancellationAndRecreationPreserveCodeAndDeadline() = runBlocking {
        val store = Store()
        val pin = PendingPlexPin(2, "SAME", 900_000)
        val first = PlexSignIn(store, { pin }, { throw CancellationException() }, { 0 })
        try {
            first.signIn { _, _ -> }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(pin, store.pin)
        // Resume after the old two-minute timeout; the original code is still valid.
        val resumed = PlexSignIn(store, { error("Must not create a second code") }, {
            assertEquals(pin, it)
            "linked"
        }, { 180_000 })
        assertEquals("linked", resumed.signIn { _, _ -> })
    }

    @Test fun savedAccountSurvivesDiscoveryFailureAndRecreation() = runBlocking {
        val store = Store()
        PlexSignIn(store, { PendingPlexPin(1, "CODE", 900_000) }, { "linked" }, { 0 })
            .signIn { _, _ -> }
        // Discovery is downstream and may fail; the next attempt must reuse the account.
        val recreated = PlexSignIn(store, { error("Already linked") }, { error("Already linked") })
        assertEquals("linked", recreated.signIn { _, _ -> })
    }

    @Test fun refusedStatusStopsPollingWhileTransientStatusKeepsTheCode() = runBlocking {
        val store = Store()
        var clock = 0L
        var calls = 0
        val signIn = PlexSignIn(store, { PendingPlexPin(1, "CODE", 900_000) }, { pin ->
            when (calls++) {
                0 -> throw PlexHttpException(503, R.string.operation_sign_in)
                1 -> throw PlexHttpException(429, R.string.operation_sign_in)
                else -> throw PlexHttpException(401, R.string.operation_sign_in)
            }
        }, { clock }, { clock += it })
        try {
            signIn.signIn { _, _ -> }
            fail("A refused sign-in must not loop until the code expires")
        } catch (error: PlexHttpException) {
            assertEquals(401, error.status)
            assertEquals(uiText(R.string.http_failed, uiText(R.string.operation_sign_in), 401), error.text)
        }
        assertEquals(3, calls)
        assertTrue("the transient failures were retried, not fatal", clock in 9_000..10_500)
        // The code survives: the account was refused this time, not expired.
        assertEquals(PendingPlexPin(1, "CODE", 900_000), store.pin)
        assertNull(store.token)
    }

    @Test fun expiryIsAbsoluteAndClearsOnlyExpiredPin() = runBlocking {
        val store = Store()
        var clock = 0L
        val signIn = PlexSignIn(store, { PendingPlexPin(1, "CODE", 3_000) }, { null }, { clock }, { clock += it })
        try {
            signIn.signIn { _, _ -> }
            fail("Expired code must stop polling")
        } catch (error: IllegalStateException) {
            assertEquals(uiText(R.string.signin_code_expired), (error as UserFacing).text)
        }
        assertNull(store.pin)
        assertNull(store.token)
        assertEquals(3_000, clock)
    }
}
