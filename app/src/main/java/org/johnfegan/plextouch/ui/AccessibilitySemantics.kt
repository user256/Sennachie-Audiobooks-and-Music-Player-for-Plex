package org.johnfegan.plextouch.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/* Ticket 140: the semantics the screens share. Labels come from resources at the call site (see Accessibility.kt). */

/** A screen title or section heading: TalkBack's headings navigation stops here. */
fun Modifier.asHeading(): Modifier = semantics { heading() }

/** Text shown abbreviated ("7h 3m", "10 s") that TalkBack should read as [spoken] words instead. */
fun Modifier.spokenAs(spoken: String): Modifier = semantics { contentDescription = spoken }

/** Transient or changing text (a notice, a download's progress, the sign-in status) read out politely when it changes. */
fun Modifier.politeLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** One TalkBack action per label; a disabled action is left out rather than offered and refused. */
@androidx.compose.runtime.Immutable
class RowAction(val label: String, val enabled: Boolean = true, val run: () -> Unit)

/**
 * A row's secondary actions (move up, move down, remove, start over) on its TalkBack actions menu, so they are reachable
 * without finding small buttons; the buttons stay for touch.
 */
fun Modifier.rowActions(vararg actions: RowAction): Modifier = semantics {
    customActions = actions.filter { it.enabled }.map { action -> CustomAccessibilityAction(action.label) { action.run(); true } }
}
