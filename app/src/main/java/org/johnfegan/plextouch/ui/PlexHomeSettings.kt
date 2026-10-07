package org.johnfegan.plextouch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.PlexHome
import org.johnfegan.plextouch.data.PlexHomeUser
import org.johnfegan.plextouch.data.homeThumbUrl
import org.johnfegan.plextouch.player.PlexTokenHeaders

/** Settings' Plex Home card (ticket 136). The PIN lives only in the dialog's own state and the one switch call. */
@Immutable
data class PlexHomeState(
    val home: PlexHome? = null,
    val loading: Boolean = false,
    /** The uuid being switched to, while Plex and the server are asked. */
    val switchingTo: String? = null,
    /** The protected user whose PIN dialog is open. */
    val pinFor: PlexHomeUser? = null,
    val error: UiText? = null,
)

@Composable
internal fun PlexHomeSection(home: PlexHomeState, vm: PlexTouchViewModel) {
    home.pinFor?.let { user -> HomePinDialog(user, home.error, home.switchingTo != null, vm) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_title), Modifier.asHeading(), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.home_intro), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
        val users = home.home?.users.orEmpty()
        when {
            home.loading -> Text(stringResource(R.string.home_loading), Modifier.politeLiveRegion(), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            home.home == null -> TextButton(onClick = vm::loadHomeUsers) { Text(stringResource(R.string.home_load), color = PlexHighlight) }
            users.size <= 1 -> Text(stringResource(R.string.home_empty), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (users.size > 1) users.forEach { user ->
            HomeUserRow(user, current = user.uuid == home.home?.currentUuid, switching = home.switchingTo == user.uuid, busy = home.switchingTo != null, vm)
        }
        if (home.pinFor == null) home.error?.let { InlineNotice(it.asString(), vm::dismissHomeError) }
    }
}

@Composable
private fun HomeUserRow(user: PlexHomeUser, current: Boolean, switching: Boolean, busy: Boolean, vm: PlexTouchViewModel) {
    Surface(color = PlexPanel, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeAvatar(user, vm)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.title, style = MaterialTheme.typography.bodyLarge)
                val tags = listOfNotNull(
                    R.string.home_current.takeIf { current }, R.string.home_admin.takeIf { user.admin },
                    R.string.home_managed.takeIf { user.restricted }, R.string.home_protected.takeIf { user.protected },
                ).map { stringResource(it) }
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = if (current) PlexHighlight else PlexMuted, style = MaterialTheme.typography.bodySmall)
                if (switching) Text(stringResource(R.string.home_switching, user.title), Modifier.politeLiveRegion(), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            }
            // "Switch to Sam" rather than a bare "Switch" when TalkBack lands on the button alone.
            val switchLabel = stringResource(R.string.a11y_switch_to, user.title)
            if (!current) TextButton(onClick = { vm.requestHomeSwitch(user) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = switchLabel }) { Text(stringResource(R.string.home_switch)) }
        }
    }
}

/** plex.tv avatars only, over https, with the token as a request header; otherwise the user's initial. */
@Composable
private fun HomeAvatar(user: PlexHomeUser, vm: PlexTouchViewModel) {
    val context = LocalContext.current
    val request = remember(user.thumb) {
        homeThumbUrl(user.thumb)?.let { url ->
            ImageRequest.Builder(context).data(url).apply { vm.homeAvatarToken()?.let { addHeader(PlexTokenHeaders.HEADER, it) } }.build()
        }
    }
    Box(Modifier.size(36.dp).clip(CircleShape).background(PlexBackground), contentAlignment = Alignment.Center) {
        Text(user.title.take(1).uppercase(), color = PlexHighlight)
        if (request != null) AsyncImage(model = request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun HomePinDialog(user: PlexHomeUser, error: UiText?, busy: Boolean, vm: PlexTouchViewModel) {
    var pin by remember(user.uuid) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) vm.cancelHomeSwitch() },
        title = { Text(stringResource(R.string.home_pin_title, user.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.home_pin_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin, onValueChange = { value -> pin = value.filter(Char::isDigit).take(4) }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.home_pin_label)) }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let { Text(it.asString(), Modifier.politeLiveRegion(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.switchHomeUser(user, pin) }, enabled = !busy && pin.length == 4) { Text(stringResource(R.string.home_switch)) } },
        dismissButton = { TextButton(onClick = vm::cancelHomeSwitch, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}
