package org.johnfegan.plextouch.app

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.PendingPlexPin
import org.johnfegan.plextouch.data.PlexAccountGateway
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexRoute
import org.johnfegan.plextouch.data.PlexServer
import org.johnfegan.plextouch.data.RouteProbe
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.uiText
import org.junit.Assert.*
import org.junit.Test

/** A plex.tv account fake: discovery answers per token; sign-in is not exercised here. */
internal class FakeAccount(var servers: suspend (String) -> List<PlexServer> = { emptyList() }) : PlexAccountGateway {
    val asked = mutableListOf<String>()
    override suspend fun createPin(): PendingPlexPin = throw UnsupportedOperationException()
    override suspend fun pollPin(pin: PendingPlexPin): String? = throw UnsupportedOperationException()
    override suspend fun servers(accountToken: String): List<PlexServer> { asked += accountToken; return servers.invoke(accountToken) }
    override fun linkUrl() = "https://plex.tv/link"
}

class DiagnoseConnectionsTest {
    private val serverToken = "server-token-SECRET"
    private val accountToken = "account-token-SECRET"
    private val localHttps = PlexRoute.of("https://192-168-1-10.plex.direct:32400", local = true)
    private val localHttp = PlexRoute.of("http://192.168.1.10:32400", local = true)
    private val remoteHttps = PlexRoute.of("https://203-0-113-5.plex.direct:32400")
    private val relay = PlexRoute.of("https://relay.plex.direct:8443", relay = true)
    private val advertised = listOf(relay, remoteHttps, localHttp, localHttps)
    private val server = PlexServer("Home server", advertised.map { PlexConnection(it.uri, serverToken) }, advertised)
    private val saved = PlexConnection(remoteHttps.uri, serverToken)

    @Test fun preferenceOrderNotLatencyPicksThePreferredRouteAndExplainsTheActiveOne() = runBlocking {
        val probe = RouteProbe { route ->
            when (route) {
                localHttps -> { delay(20); throw SocketTimeoutException("timed out with $serverToken") }
                localHttp -> { delay(80); 80 }
                remoteHttps -> { delay(5); 5 }
                else -> { delay(1); 1 }
            }
        }
        val store = FakeStore().apply { this.saved = this@DiagnoseConnectionsTest.saved; account = accountToken }
        val diagnose = DiagnoseConnections(FakeAccount({ listOf(server) }), probe)
        var diagnostics = diagnose.routes(saved, store.accountToken())
        assertEquals("Home server", diagnostics.serverName)
        assertEquals("ranked: local https, local http, remote, relay", listOf(localHttps, localHttp, remoteHttps, relay), diagnostics.routes.map { it.route })
        assertEquals(listOf(remoteHttps), diagnostics.routes.filter { it.active }.map { it.route })
        val started = System.nanoTime()
        diagnose.test(diagnostics.routes.map { it.route }) { route, result -> diagnostics = diagnostics.with(route, result) }
        assertTrue("probes run in parallel", (System.nanoTime() - started) / 1_000_000 < 80 + 20 + 5 + 1 + 50)

        val byRoute = diagnostics.routes.associateBy { it.route }
        assertEquals(RouteResult.Failed(uiText(R.string.route_failed_timeout)), byRoute.getValue(localHttps).result)
        assertEquals(RouteResult.Reachable(80), byRoute.getValue(localHttp).result)
        assertEquals("the slower local route is still preferred over faster remote and relay", localHttp, preferredRoute(diagnostics.routes))
        assertEquals(listOf(localHttp), diagnostics.routes.filter { it.preferred }.map { it.route })
        assertEquals(uiText(R.string.route_active_not_preferred), routeExplanation(byRoute.getValue(remoteHttps)))
        assertEquals(uiText(R.string.route_preferred_not_active), routeExplanation(byRoute.getValue(localHttp)))
        assertNull(routeExplanation(byRoute.getValue(relay)))
        assertEquals("diagnostics never touch the saved connection or tokens", saved, store.saved)
        assertEquals(accountToken, store.account)
        assertNoToken(diagnostics)
    }

    @Test fun activePreferredFailingAndUntestedRoutesAreExplained() {
        val preferredActive = RouteDiagnosis(localHttps, active = true, result = RouteResult.Reachable(3), preferred = true)
        assertEquals(uiText(R.string.route_active_preferred), routeExplanation(preferredActive))
        assertEquals(uiText(R.string.route_active_failing), routeExplanation(preferredActive.copy(result = RouteResult.Failed(uiText(R.string.route_failed_other)), preferred = false)))
        assertEquals(uiText(R.string.route_active_saved), routeExplanation(RouteDiagnosis(localHttps, active = true)))
        val allFailed = ConnectionDiagnostics(routes = listOf(RouteDiagnosis(localHttps, true), RouteDiagnosis(relay, false)))
            .with(localHttps, RouteResult.Failed(uiText(R.string.route_failed_other))).with(relay, RouteResult.Failed(uiText(R.string.route_failed_other)))
        assertNull("no route is preferred when none answers", preferredRoute(allFailed.routes))
    }

    @Test fun withoutPlexDiscoveryOnlyTheSavedAddressIsListedAndNothingIsLost() = runBlocking {
        val account = FakeAccount({ throw IOException("plex.tv down") })
        val failed = DiagnoseConnections(account, { 1 }).routes(saved, accountToken)
        assertEquals(listOf(remoteHttps), failed.routes.map { it.route })
        assertTrue(failed.routes.single().active)
        assertEquals(uiText(R.string.diagnostics_discovery_failed, UiText.Raw("plex.tv down")), failed.notice)

        val manualAccount = FakeAccount({ error("must not ask plex.tv") })
        val manual = DiagnoseConnections(manualAccount, { 1 }).routes(PlexConnection("http://10.0.0.2:32400/", "manual"), null)
        assertEquals(listOf("http://10.0.0.2:32400"), manual.routes.map { it.route.uri })
        assertEquals(uiText(R.string.diagnostics_manual_only), manual.notice)
        assertTrue(manualAccount.asked.isEmpty())

        val moved = DiagnoseConnections(FakeAccount({ listOf(server.copy(connections = server.connections.take(1), routes = listOf(relay))) }), { 1 })
            .routes(saved, accountToken)
        assertEquals("the saved address is listed even when Plex no longer advertises it", listOf(remoteHttps), moved.routes.map { it.route })
        assertEquals(uiText(R.string.diagnostics_not_advertised), moved.notice)
    }

    @Test fun cancellingATestCancelsEveryProbe() = runBlocking {
        val cancelled = mutableListOf<PlexRoute>()
        val diagnose = DiagnoseConnections(FakeAccount(), RouteProbe { route ->
            try { delay(10_000); 1 } catch (stop: CancellationException) { synchronized(cancelled) { cancelled += route }; throw stop }
        })
        val reported = mutableListOf<PlexRoute>()
        withTimeout(2_000) {
            val job = launch { diagnose.test(advertised) { route, _ -> reported += route } }
            delay(50)
            job.cancel()
            job.join()
        }
        assertEquals(advertised.toSet(), cancelled.toSet())
        assertTrue("a cancelled test reports nothing", reported.isEmpty())
    }

    private fun assertNoToken(diagnostics: ConnectionDiagnostics) {
        val text = diagnostics.toString() + diagnostics.routes.joinToString { it.route.address + routeExplanation(it) }
        listOf(serverToken, accountToken, "SECRET").forEach { assertFalse("no token in diagnostics: $it", text.contains(it)) }
    }
}
