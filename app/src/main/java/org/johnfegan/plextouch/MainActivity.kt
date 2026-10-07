package org.johnfegan.plextouch

import android.os.Bundle
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexSection
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.data.SetupPlan
import org.johnfegan.plextouch.data.SetupStep
import org.johnfegan.plextouch.sonos.SonosSpeaker
import org.johnfegan.plextouch.ui.PlexTouchUiState
import org.johnfegan.plextouch.ui.PlexTouchViewModel
import org.johnfegan.plextouch.ui.PlexTheme
import org.johnfegan.plextouch.ui.LibraryShell
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.johnfegan.plextouch.ui.asHeading
import org.johnfegan.plextouch.ui.politeLiveRegion
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.asString
import org.johnfegan.plextouch.ui.serverName
import org.johnfegan.plextouch.widget.AppShortcuts
import org.johnfegan.plextouch.widget.EXTRA_SHORTCUT
import org.johnfegan.plextouch.widget.shortcutFor

class MainActivity : ComponentActivity() {
    private val plexViewModel: PlexTouchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            PlexTheme {
                PlexTouchApp(plexViewModel)
            }
        }
        // Ticket 139: launcher shortcuts carry only a shortcut name; a recreated activity must not repeat the action.
        if (savedInstanceState == null) {
            AppShortcuts.publish(this)
            openShortcut(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openShortcut(intent)
    }

    private fun openShortcut(intent: Intent?) {
        val shortcut = shortcutFor(intent?.action, intent?.getStringExtra(EXTRA_SHORTCUT)) ?: return
        AppShortcuts.reportUsed(this, shortcut)
        plexViewModel.openShortcut(shortcut)
    }
}

@Composable
private fun PlexTouchApp(viewModel: PlexTouchViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize()) {
        if (state.connection == null || state.setupPlan != null) {
            SetupScreen(state, viewModel)
        } else {
            LibraryShell(state, viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupScreen(state: PlexTouchUiState, viewModel: PlexTouchViewModel, manageConnection: Boolean = false) {
    val context = LocalContext.current
    LaunchedEffect(state.plexAuthUrl) {
        state.plexAuthUrl?.let { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                viewModel.authPageOpened()
            } catch (_: ActivityNotFoundException) {
                viewModel.browserUnavailable()
            }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name), Modifier.asHeading()) }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (manageConnection) {
                Text(stringResource(R.string.setup_headline), Modifier.asHeading(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.setup_intro))
                PlexLinkPanel(state, viewModel, context)
                HorizontalDivider()
                ManualConnectionFields(state, viewModel)
                OutlinedButton(onClick = { viewModel.openSettings(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
            } else {
                SetupWizard(state, viewModel, context)
            }
        }
    }
}

@Composable
private fun SetupWizard(state: PlexTouchUiState, viewModel: PlexTouchViewModel, context: android.content.Context) {
    when (state.setupStep ?: SetupStep.WHAT_TO_SET_UP) {
        SetupStep.WHAT_TO_SET_UP -> {
            Text(stringResource(R.string.setup_choose_headline), Modifier.asHeading(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.setup_choose_intro))
            Button(onClick = { viewModel.chooseSetupPlan(SetupPlan.MUSIC) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_music_only)) }
            Button(onClick = { viewModel.chooseSetupPlan(SetupPlan.AUDIOBOOKS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_audiobooks_only)) }
            Button(onClick = { viewModel.chooseSetupPlan(SetupPlan.BOTH) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_both)) }
            StatusMessage(state.setupMessage)
        }
        SetupStep.SIGN_IN -> {
            Text(stringResource(R.string.setup_signin_headline), Modifier.asHeading(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.setup_signin_intro))
            StatusMessage(state.setupMessage)
            Button(onClick = viewModel::beginPlexSignIn, modifier = Modifier.fillMaxWidth(), enabled = !state.signingIn) {
                Text(stringResource(if (state.signingIn) R.string.setup_connecting else if (state.setupMessage != null) R.string.setup_retry else R.string.setup_link))
            }
            PlexLinkPanel(state, viewModel, context, showActionWhenEmpty = false)
            ManualConnectionFallback(state, viewModel)
        }
        SetupStep.SERVER -> {
            Text(stringResource(R.string.setup_server_headline), Modifier.asHeading(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.setup_server_intro))
            StatusMessage(state.setupMessage)
            state.discoveredServers.forEach { server ->
                Button(onClick = { viewModel.chooseServer(server) }, modifier = Modifier.fillMaxWidth(), enabled = !state.loading) {
                    Text(stringResource(R.string.setup_use_server, serverName(server).asString()))
                }
            }
        }
        SetupStep.MUSIC_LIBRARY, SetupStep.AUDIOBOOK_LIBRARY -> {
            val music = state.setupStep == SetupStep.MUSIC_LIBRARY
            Text(stringResource(if (music) R.string.setup_music_library_headline else R.string.setup_audiobook_library_headline), Modifier.asHeading(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(if (music) R.string.setup_music_library_intro else R.string.setup_audiobook_library_intro))
            StatusMessage(state.libraryError ?: state.setupMessage)
            state.sections.forEach { section ->
                OutlinedButton(onClick = { viewModel.chooseSetupLibrary(section) }, modifier = Modifier.fillMaxWidth(), enabled = !state.loading) { Text(section.title) }
            }
        }
    }
}

@Composable
private fun PlexLinkPanel(state: PlexTouchUiState, viewModel: PlexTouchViewModel, context: android.content.Context, showActionWhenEmpty: Boolean = true) {
    val code = state.plexLinkCode
    if (code != null) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.setup_code_hint), style = MaterialTheme.typography.labelLarge)
                val spelled = stringResource(R.string.a11y_sign_in_code, code.toList().joinToString(" "))
                Text(code, Modifier.semantics { contentDescription = spelled }.politeLiveRegion(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.setup_code_hint), code))
                }) { Text(stringResource(R.string.setup_copy_code)) }
                Button(onClick = viewModel::openPlexLink, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_open_link)) }
                Text(stringResource(R.string.setup_return_after_link), style = MaterialTheme.typography.bodySmall)
            }
        }
    } else if (showActionWhenEmpty) {
        Button(onClick = viewModel::beginPlexSignIn, modifier = Modifier.fillMaxWidth(), enabled = !state.signingIn) {
            Text(stringResource(if (state.signingIn) R.string.setup_connecting else R.string.setup_link))
        }
    }
}

@Composable
private fun ManualConnectionFallback(state: PlexTouchUiState, viewModel: PlexTouchViewModel) {
    var showManual by rememberSaveable { mutableStateOf(false) }
    if (!showManual) {
        TextButton(onClick = { showManual = true }) { Text(stringResource(R.string.setup_manual_fallback)) }
    } else {
        HorizontalDivider()
        ManualConnectionFields(state, viewModel)
    }
}

@Composable
private fun ManualConnectionFields(state: PlexTouchUiState, viewModel: PlexTouchViewModel) {
    Text(stringResource(R.string.setup_manual_title), Modifier.asHeading(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.setup_manual_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = state.serverUrl, onValueChange = viewModel::updateUrl, label = { Text(stringResource(R.string.setup_server_address)) }, placeholder = { Text(stringResource(R.string.setup_server_placeholder)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(value = state.token, onValueChange = viewModel::updateToken, label = { Text(stringResource(R.string.setup_token)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
    Button(onClick = viewModel::saveConnection, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_connect)) }
}

@Composable
private fun StatusMessage(message: UiText?) {
    if (message != null) Text(message.asString(), Modifier.politeLiveRegion(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
}
