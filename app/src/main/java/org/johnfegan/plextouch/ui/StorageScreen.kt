package org.johnfegan.plextouch.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.johnfegan.plextouch.PlexTouchApplication
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.ArtworkBudget
import org.johnfegan.plextouch.data.CoilArtworkCache
import org.johnfegan.plextouch.data.DownloadAlbum
import org.johnfegan.plextouch.data.DownloadStatus
import org.johnfegan.plextouch.data.OfflineDownloadManager
import org.johnfegan.plextouch.data.PlexCache
import org.johnfegan.plextouch.data.StorageControls
import org.johnfegan.plextouch.data.StorageReport
import org.johnfegan.plextouch.data.downloadSize
import java.io.File

/** Ticket 137. [report] is null until the first measurement finishes; [activeBudget] is the limit the running cache uses. */
@Immutable
data class StorageUiState(
    val report: StorageReport? = null,
    val budget: ArtworkBudget = ArtworkBudget.DEFAULT,
    val activeBudget: ArtworkBudget? = null,
    val busy: Boolean = false,
    val message: UiText? = null,
)

/**
 * The Storage screen's own state, kept apart from [PlexTouchViewModel]: measuring and clearing caches touches nothing the
 * library screens hold. Every scan, delete and preference read runs on `Dispatchers.IO` inside [StorageControls] or here.
 */
class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val controls = StorageControls(
        metadata = PlexCache(File(app.cacheDir, PlexCache.DIRECTORY)),
        artwork = CoilArtworkCache { app.imageLoader.diskCache },
        // Downloads, app files and preferences (credentials, history, timeline outbox, download catalogue).
        protectedRoots = { listOfNotNull(OfflineDownloadManager.offlineRoot(app), app.filesDir, File(app.applicationInfo.dataDir, "shared_prefs")) },
    )
    private val mutable = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = mutable.asStateFlow()
    private var downloads: List<DownloadAlbum> = emptyList()

    fun measure(downloads: List<DownloadAlbum>) {
        this.downloads = downloads
        viewModelScope.launch {
            val (budget, active) = withContext(Dispatchers.IO) {
                PlexTouchApplication.artworkBudget(app) to app.imageLoader.diskCache?.maxSize?.let { size -> ArtworkBudget.entries.firstOrNull { it.bytes == size } }
            }
            val report = controls.measure(downloads)
            mutable.update { it.copy(report = report, budget = budget, activeBudget = active) }
        }
    }

    fun chooseBudget(budget: ArtworkBudget) {
        mutable.update { it.copy(budget = budget) }
        viewModelScope.launch(Dispatchers.IO) { PlexTouchApplication.saveArtworkBudget(app, budget) }
    }

    fun clearArtwork() = clear(R.string.storage_artwork_cleared) { controls.clearArtwork() }
    fun clearMetadata() = clear(R.string.storage_metadata_cleared) { controls.clearMetadata() }
    fun dismissMessage() = mutable.update { it.copy(message = null) }

    private fun clear(done: Int, action: suspend () -> Boolean) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val cleared = action()
            val report = controls.measure(downloads)
            mutable.update { it.copy(busy = false, report = report, message = uiText(if (cleared) done else R.string.storage_clear_failed)) }
        }
    }
}

private enum class StorageClear { ARTWORK, METADATA }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun StorageScreen(downloads: List<DownloadStatus>, onBack: () -> Unit, onManageDownloads: () -> Unit) {
    val vm: StorageViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val records = remember(downloads) { downloads.map { it.record } }
    LaunchedEffect(records) { vm.measure(records) }
    BackHandler(onBack = onBack)
    var confirming by remember { mutableStateOf<StorageClear?>(null) }
    confirming?.let { clear ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(if (clear == StorageClear.ARTWORK) R.string.storage_clear_artwork_title else R.string.storage_clear_metadata_title)) },
            text = { Text(stringResource(if (clear == StorageClear.ARTWORK) R.string.storage_clear_artwork_body else R.string.storage_clear_metadata_body)) },
            confirmButton = {
                TextButton(onClick = { confirming = null; if (clear == StorageClear.ARTWORK) vm.clearArtwork() else vm.clearMetadata() }) {
                    Text(stringResource(R.string.storage_clear_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.storage_title), Modifier.asHeading()) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.action_back)) } }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Text(stringResource(R.string.storage_intro), color = PlexMuted, style = MaterialTheme.typography.bodyMedium) }
            state.message?.let { message -> item { InlineNotice(message.asString(), vm::dismissMessage) } }
            item {
                StorageRow(stringResource(R.string.storage_downloads), stringResource(R.string.storage_downloads_body), state.report?.downloadBytes) {
                    TextButton(onClick = onManageDownloads) { Text(stringResource(R.string.storage_manage_downloads), color = PlexHighlight) }
                }
            }
            item {
                StorageRow(stringResource(R.string.storage_artwork), stringResource(R.string.storage_artwork_body), state.report?.artworkBytes) {
                    Text(stringResource(R.string.storage_artwork_limit), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArtworkBudget.entries.forEach { budget ->
                            FilterChip(selected = state.budget == budget, onClick = { vm.chooseBudget(budget) }, label = { Text(stringResource(R.string.storage_artwork_limit_choice, budget.megabytes)) })
                        }
                    }
                    if (state.activeBudget != null && state.activeBudget != state.budget) {
                        Text(stringResource(R.string.storage_artwork_limit_next_start, stringResource(R.string.app_name)), color = PlexMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { confirming = StorageClear.ARTWORK }, enabled = !state.busy) { Text(stringResource(R.string.storage_clear_artwork), color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                StorageRow(stringResource(R.string.storage_metadata), stringResource(R.string.storage_metadata_body), state.report?.metadataBytes) {
                    TextButton(onClick = { confirming = StorageClear.METADATA }, enabled = !state.busy) { Text(stringResource(R.string.storage_clear_metadata), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun StorageRow(title: String, body: String, bytes: Long?, actions: @Composable ColumnScope.() -> Unit) {
    Surface(color = PlexPanel, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).asHeading(), fontWeight = FontWeight.SemiBold)
                Text(if (bytes == null) stringResource(R.string.storage_measuring) else downloadSize(bytes).asString(), Modifier.politeLiveRegion(), color = PlexHighlight)
            }
            Text(body, color = PlexMuted, style = MaterialTheme.typography.bodySmall)
            actions()
        }
    }
}
