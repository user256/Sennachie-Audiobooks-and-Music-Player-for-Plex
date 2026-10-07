package org.johnfegan.plextouch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.app.ConnectionDiagnostics
import org.johnfegan.plextouch.app.DiagnoseConnections
import org.johnfegan.plextouch.app.RouteDiagnosis
import org.johnfegan.plextouch.app.RouteResult
import org.johnfegan.plextouch.app.routeExplanation

/**
 * Settings' server connections card (ticket 136): every advertised route with its kind, security, last result and
 * latency, the one in use and why. It shows addresses only; tokens never reach this state.
 */
@Composable
internal fun ConnectionDiagnosticsSection(diagnostics: ConnectionDiagnostics, vm: PlexTouchViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(diagnostics.serverName ?: stringResource(R.string.diagnostics_title), Modifier.weight(1f).asHeading(), style = MaterialTheme.typography.titleMedium)
            if (diagnostics.running) TextButton(onClick = vm::cancelConnectionTests) { Text(stringResource(R.string.diagnostics_stop)) }
            else TextButton(onClick = vm::testConnections) { Text(stringResource(R.string.diagnostics_test), color = PlexHighlight) }
        }
        Text(stringResource(R.string.diagnostics_intro), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
        diagnostics.notice?.let { Text(it.asString(), Modifier.politeLiveRegion(), color = PlexMuted, style = MaterialTheme.typography.bodySmall) }
        diagnostics.routes.forEach { RouteRow(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteRow(diagnosis: RouteDiagnosis) {
    val route = diagnosis.route
    Surface(color = PlexPanel, shape = RoundedCornerShape(12.dp)) {
        // One TalkBack stop per route: kind, security, address, result and why it is (not) in use.
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(verticalArrangement = Arrangement.Center) {
                Icon(if (route.secure) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, Modifier.size(16.dp), tint = if (route.secure) PlexHighlight else PlexMuted)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(DiagnoseConnections.kindLabel(route.kind)), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge, color = if (diagnosis.active) PlexHighlight else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (route.secure) R.string.diagnostics_secure else R.string.diagnostics_insecure), Modifier.align(Alignment.CenterVertically), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text(route.address, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (diagnosis.result) {
                    is RouteResult.Reachable -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = PlexHighlight)
                    is RouteResult.Failed -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                Spacer(Modifier.width(6.dp))
                Text(resultLabel(diagnosis.result), Modifier.politeLiveRegion(), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            }
            routeExplanation(diagnosis)?.let { Text(it.asString(), color = if (diagnosis.active) PlexHighlight else PlexMuted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun resultLabel(result: RouteResult): String = when (result) {
    RouteResult.Untested -> stringResource(R.string.diagnostics_untested)
    RouteResult.Testing -> stringResource(R.string.diagnostics_testing)
    is RouteResult.Reachable -> stringResource(R.string.diagnostics_reachable, result.latencyMs.toInt())
    is RouteResult.Failed -> result.reason.asString()
}
