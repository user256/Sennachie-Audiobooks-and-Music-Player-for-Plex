package org.johnfegan.plextouch.data

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.app.FakePlexServer
import org.johnfegan.plextouch.ui.UserFacing
import org.johnfegan.plextouch.ui.uiText
import org.junit.Assert.*
import org.junit.Test

/** Ticket 136: Plex Home response parsing, route ranking and the unauthenticated route probe. */
class PlexHomeAndRoutesTest {
    @Test fun homeUsersParseFromTheV2ObjectAndABareArray() {
        val json = """{"id":1,"name":"Home","users":[
            {"id":11,"uuid":"admin-uuid","title":"Alex","thumb":"https://plex.tv/users/a/avatar","admin":true,"restricted":false,"protected":false},
            {"id":12,"uuid":"kid-uuid","title":"Sam","restricted":true,"protected":true,"admin":false},
            {"id":13,"title":"No uuid"}]}"""
        val users = PlexHomeParser.users(json)
        assertEquals(listOf("admin-uuid", "kid-uuid"), users.map { it.uuid })
        assertTrue(users[0].admin && !users[0].protected)
        assertTrue(users[1].restricted && users[1].protected && !users[1].admin)
        assertEquals(users, PlexHomeParser.users("""[${json.substringAfter("[").substringBeforeLast("]")}]"""))
        assertEquals(emptyList<PlexHomeUser>(), PlexHomeParser.users("""{"error":"nope"}"""))
        assertEquals("admin-uuid", PlexHomeParser.currentUuid("""{"id":11,"uuid":"admin-uuid","authToken":"x"}"""))
    }

    @Test fun switchResponseYieldsOnlyTheUsersOwnTokenOrAUserFacingError() {
        assertEquals("user-token", PlexHomeParser.switchedToken("""{"uuid":"kid-uuid","authToken":"user-token"}"""))
        val missing = runCatching { PlexHomeParser.switchedToken("""{"uuid":"kid-uuid"}""") }.exceptionOrNull()
        assertEquals(uiText(R.string.home_switch_no_token), (missing as UserFacing).text)
    }

    @Test fun avatarsAreOnlyLoadedFromPlexTvOverHttps() {
        assertEquals("https://plex.tv/users/a/avatar?c=1", homeThumbUrl("https://plex.tv/users/a/avatar?c=1"))
        assertNotNull(homeThumbUrl("https://assets.plex.tv/a.png"))
        assertNull(homeThumbUrl("http://plex.tv/users/a/avatar"))
        assertNull(homeThumbUrl("https://evil.example/plex.tv"))
        assertNull(homeThumbUrl("https://user:pw@plex.tv/a"))
        assertNull(homeThumbUrl(null))
    }

    @Test fun routesRankLocalThenRemoteThenRelayAndSecureFirst() {
        val relay = PlexRoute.of("https://relay.plex.direct:8443", relay = true)
        val remoteHttp = PlexRoute.of("http://203.0.113.5:32400")
        val remoteHttps = PlexRoute.of("https://203-0-113-5.plex.direct:32400")
        val localHttp = PlexRoute.of("http://192.168.1.10:32400", local = true)
        val localHttps = PlexRoute.of("https://192-168-1-10.plex.direct:32400/", local = true)
        assertEquals(listOf(localHttps, localHttp, remoteHttps, remoteHttp, relay), rankRoutes(listOf(relay, remoteHttp, localHttp, remoteHttps, localHttps, relay)))
        assertEquals("trailing slash trimmed", "https://192-168-1-10.plex.direct:32400", localHttps.uri)
        assertTrue(localHttps.secure); assertFalse(localHttp.secure)
        assertEquals(RouteKind.RELAY, relay.kind)
    }

    @Test fun displayedAddressDropsUserInfoAndQuery() {
        assertEquals("https://host.example:32400", displayAddress("https://user:secret@host.example:32400/path?X-Plex-Token=secret"))
        assertEquals("http://10.0.0.2", displayAddress("http://10.0.0.2/"))
        assertFalse(PlexRoute.of("https://a:secret@host.example?X-Plex-Token=secret").address.contains("secret"))
    }

    @Test fun failuresMapToFixedReasonsWithoutTheExceptionMessage() {
        val secret = "secret-token"
        val reasons = listOf(SocketTimeoutException(secret), UnknownHostException(secret), SSLHandshakeException(secret),
            java.net.ConnectException(secret), PlexHttpException(401), IllegalStateException(secret)).map(::routeFailure)
        assertEquals(uiText(R.string.route_failed_timeout), reasons[0])
        assertEquals(uiText(R.string.route_failed_unknown_host), reasons[1])
        assertEquals(uiText(R.string.route_failed_tls), reasons[2])
        assertEquals(uiText(R.string.route_failed_refused), reasons[3])
        assertEquals(uiText(R.string.route_failed_http, 401), reasons[4])
        assertEquals(uiText(R.string.route_failed_other), reasons[5])
        reasons.forEach { assertFalse(it.toString().contains(secret)) }
    }

    @Test fun identityProbeMeasuresReachabilityWithoutSendingAToken() = runBlocking {
        FakePlexServer { _, path -> if (path == "/identity") 200 to """{"MediaContainer":{"machineIdentifier":"m"}}""" else 404 to "{}" }.use { server ->
            val latency = IdentityProbe(timeoutMs = 2_000).latencyMs(PlexRoute.of(server.connection.serverUrl + "/"))
            assertTrue(latency >= 0)
            assertEquals("only /identity, with no token in the URL", listOf("GET /identity"), server.calls.toList())
        }
        FakePlexServer { _, _ -> 503 to "{}" }.use { server ->
            val failure = runCatching { IdentityProbe(timeoutMs = 2_000).latencyMs(PlexRoute.of(server.connection.serverUrl)) }.exceptionOrNull()
            assertEquals(503, (failure as PlexHttpException).status)
            assertEquals(uiText(R.string.route_failed_http, 503), routeFailure(failure))
        }
    }

    @Test fun accountSwitchQuarantinesOtherScopesBeforeSavingAndKeepsTheOldSignInOnFailure() {
        val prefs = mutableMapOf<String, String>()
        val timeline = TimelineStateStore({ prefs["t"] }, { prefs["t"] = it })
        val old = PlexConnection("https://server", "old-server-token").progressScope("old-account")
        timeline.update { TimelineOutbox.enqueue(it, TimelineEvent("event-0001", old, "https://server", "10", "11", 0, 30_000, 100_000,
            PlexTimelineState.PLAYING, 1, "session-0001", false)) }
        var saved: Triple<String, String, String>? = null
        var fail = true
        val writer = AccountSwitchWriter(Any(), timeline, { a, u, t -> if (!fail) saved = Triple(a, u, t); !fail }, now = { 7 })
        val refused = runCatching { writer.switch("new-account", "https://server/", "new-server-token") }.exceptionOrNull()
        assertTrue(refused is UserFacing)
        assertNull("a failed write saves nothing", saved)
        fail = false
        val connection = writer.switch(" new-account ", "https://server/", "new-server-token")
        assertEquals(PlexConnection("https://server", "new-server-token"), connection)
        assertEquals(Triple("new-account", "https://server", "new-server-token"), saved)
        val state = timeline.state()
        assertEquals("the old user's queued event is never sendable again", TimelineBlock.AUTHORITY_CHANGED, state.events.single().blocked)
        assertNull(TimelineOutbox.next(state, Long.MAX_VALUE))
        assertEquals(old, state.conflicts.single().scope)
    }
}
