package org.johnfegan.plextouch.app

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexCache
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexHome
import org.johnfegan.plextouch.data.PlexHomeGateway
import org.johnfegan.plextouch.data.PlexHomeUser
import org.johnfegan.plextouch.data.PlexHttpException
import org.johnfegan.plextouch.data.PlexServer
import org.johnfegan.plextouch.data.PlexTimelineState
import org.johnfegan.plextouch.data.TimelineBlock
import org.johnfegan.plextouch.data.TimelineEvent
import org.johnfegan.plextouch.data.TimelineOutbox
import org.johnfegan.plextouch.data.belongsToScope
import org.johnfegan.plextouch.data.listenAgain
import org.johnfegan.plextouch.data.progressScope
import org.johnfegan.plextouch.ui.ArtistIndexScope
import org.johnfegan.plextouch.ui.PlexTouchUiState
import org.johnfegan.plextouch.ui.UserFacing
import org.johnfegan.plextouch.ui.searchInput
import org.johnfegan.plextouch.ui.uiText
import org.junit.Assert.*
import org.junit.Test

/** plex.tv Home fake: each user's token, an optional PIN, and every switch call as "uuid:pin". */
private class FakeHome(private val users: Map<String, Pair<String, String?>>) : PlexHomeGateway {
    val switches = mutableListOf<String>()
    override suspend fun home(accountToken: String) = PlexHome(emptyList(), null)
    override suspend fun switchUser(accountToken: String, uuid: String, pin: String?): String {
        switches += "$uuid:$pin"
        val (token, required) = users.getValue(uuid)
        if (required != null && pin != required) throw PlexHttpException(403, R.string.operation_home_switch)
        return token
    }
}

class SwitchHomeUserTest {
    private val adminAccount = "admin-account-token"
    private val kidAccount = "kid-account-token"
    private val lan = PlexConnection("https://plex.lan:32400", "admin-server-token")
    private val virtual = "https://10.0.0.1:32400"
    private val kid = PlexHomeUser(12, "kid-uuid", "Sam", restricted = true, protected = true)
    private val guest = PlexHomeUser(13, "guest-uuid", "Guest")
    private val home = FakeHome(mapOf("kid-uuid" to (kidAccount to "1234"), "guest-uuid" to ("guest-account-token" to null)))
    private val kidServer = PlexServer("Home", listOf(PlexConnection(virtual, "kid-server-token"), PlexConnection(lan.serverUrl, "kid-server-token")))
    private val account = FakeAccount({ token -> if (token == kidAccount || token == "guest-account-token") listOf(kidServer) else emptyList() })
    private val gateways = FakeGateways().apply { probe = { if (it.serverUrl == virtual) throw IOException("No route") else listOf(section) } }
    private val dune = album("10", "Dune")
    private val adminScope = lan.progressScope(adminAccount)
    private val store = FakeStore().apply {
        saved = lan
        account = adminAccount
        historyList = listOf(progress(dune, 0, 30_000, trackId = "11", scope = adminScope).copy(hasListened = true))
        saveBookSpeed(adminScope, lan.serverUrl, dune.id, 1.5f)
        timeline.update { TimelineOutbox.enqueue(it, TimelineEvent("event-0001", adminScope, lan.serverUrl, dune.id, "11", 0, 30_000, 120_000,
            PlexTimelineState.PAUSED, 1, "session-0001", false)) }
    }
    private val switch = SwitchHomeUser(home, account, gateways, store)

    private fun assertUnchanged() {
        assertEquals("the last working server and token stay saved", lan, store.saved)
        assertEquals(adminAccount, store.account)
        assertNull("nothing was quarantined", store.timeline.state().events.single().blocked)
    }

    @Test fun protectedUserSwitchesWithTheirPinAndEverythingIsRescoped() = runBlocking {
        val ready = switch.prepare(kid, " 1234 ") as SwitchResult.Ready
        assertUnchanged()
        assertEquals("the PIN is sent to Plex once", listOf("kid-uuid:1234"), home.switches)
        assertEquals("servers are discovered as the switched user", listOf(kidAccount), account.asked)
        assertEquals(PlexConnection(lan.serverUrl, "kid-server-token"), ready.switch.connection)
        assertFalse("a prepared switch never prints a token", ready.switch.toString().contains("token"))

        val session = switch.commit(ready.switch)
        assertEquals(PlexConnection(lan.serverUrl, "kid-server-token"), store.saved)
        assertEquals(kidAccount, store.account)
        assertNotEquals(adminScope, session.accountScope)
        assertEquals(lan.progressScope(kidAccount), session.accountScope)

        val outbox = store.timeline.state()
        assertEquals("the admin's queued progress is quarantined, never sent as Sam", TimelineBlock.AUTHORITY_CHANGED, outbox.events.single().blocked)
        assertNull(TimelineOutbox.next(outbox, Long.MAX_VALUE))
        assertTrue("the admin's history does not belong to the new scope", session.history.none { it.belongsToScope(session.accountScope) })
        assertTrue(listenAgain(session.history, lan.serverUrl, session.accountScope).isEmpty())
        assertNull("book speeds are per account", store.bookSpeed(session.accountScope, lan.serverUrl, dune.id))
        assertEquals(1.5f, store.bookSpeed(adminScope, lan.serverUrl, dune.id))

        val before = PlexTouchUiState(connection = lan, accountScope = adminScope, mode = LibraryMode.MUSIC, selectedLibraryId = "1")
        val after = before.copy(connection = session.connection, accountScope = session.accountScope)
        assertNotEquals("the artist index is rebuilt for the new user", ArtistIndexScope.of(before), ArtistIndexScope.of(after))
        assertNotEquals("search never reuses the old user's catalogue", searchInput(before).scope, searchInput(after).scope)

        val cache = PlexCache(Files.createTempDirectory("plex-cache").toFile())
        cache.write(lan, "/library/sections", "admin")
        assertNull("metadata cache keys are fingerprinted per user", cache.read(session.connection, "/library/sections"))
        assertEquals("admin", cache.read(lan, "/library/sections"))
    }

    @Test fun wrongOrMalformedPinChangesNothing() = runBlocking {
        val wrong = switch.prepare(kid, "0000")
        assertEquals(SwitchResult.Failed(uiText(R.string.home_pin_wrong), wrongPin = true), wrong)
        assertTrue("no discovery after a refused PIN", account.asked.isEmpty())
        val malformed = switch.prepare(kid, "12a")
        assertEquals(SwitchResult.Failed(uiText(R.string.home_pin_invalid), wrongPin = true), malformed)
        assertEquals("a malformed PIN is never sent", listOf("kid-uuid:0000"), home.switches)
        assertTrue(switch.prepare(kid, null) is SwitchResult.Failed)
        assertUnchanged()
    }

    @Test fun anUnprotectedUserNeverSendsAPin() = runBlocking {
        assertTrue(switch.prepare(guest, "9999") is SwitchResult.Ready)
        assertEquals(listOf("guest-uuid:null"), home.switches)
        assertUnchanged()
    }

    @Test fun noMatchingServerOrNoReachableRouteChangesNothing() = runBlocking {
        account.servers = { listOf(PlexServer("Elsewhere", listOf(PlexConnection("https://a.example", "t"))), PlexServer("Other", listOf(PlexConnection("https://b.example", "t")))) }
        assertEquals(SwitchResult.Failed(uiText(R.string.home_switch_no_server, "Sam")), switch.prepare(kid, "1234"))
        account.servers = { listOf(kidServer) }
        gateways.probe = { throw IOException("offline") }
        val unreachable = switch.prepare(kid, "1234") as SwitchResult.Failed
        assertEquals(uiText(R.string.home_switch_failed, uiText(R.string.server_unreachable)), unreachable.error)
        assertUnchanged()
    }

    @Test fun aFailedSaveKeepsThePreviousSignIn() = runBlocking {
        val ready = switch.prepare(kid, "1234") as SwitchResult.Ready
        store.failSwitchWrite = true
        val error = runCatching { switch.commit(ready.switch) }.exceptionOrNull()
        assertEquals(uiText(R.string.connection_not_saved), (error as UserFacing).text)
        assertEquals(lan, store.saved)
        assertEquals(adminAccount, store.account)
    }

    @Test fun matchingPrefersTheSavedAddressThenASingleServer() {
        val other = PlexServer("Other", listOf(PlexConnection("https://b.example", "t")))
        assertEquals(kidServer, SwitchHomeUser.matchingServer(listOf(other, kidServer), lan))
        assertEquals(other, SwitchHomeUser.matchingServer(listOf(other), lan))
        assertNull(SwitchHomeUser.matchingServer(emptyList(), lan))
    }
}
