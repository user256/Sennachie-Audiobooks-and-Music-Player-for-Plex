package org.johnfegan.plextouch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.johnfegan.plextouch.data.*
import androidx.compose.ui.res.stringResource
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.player.PlexTokenHeaders
import androidx.compose.runtime.LaunchedEffect
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A cover. Ticket 140: inside a labelled row or tile the title is already read, so the cover is decorative unless
 * [labelled] (the album and player screens, where it stands alone and TalkBack can explore it as "Cover of …").
 */
@Composable
fun Artwork(album: PlexAlbum, connection: PlexConnection?, modifier: Modifier = Modifier, book: Boolean = false, labelled: Boolean = false) {
    val context = LocalContext.current
    val request = remember(album.localThumb, album.thumb, connection) {
        album.localThumb?.let { ImageRequest.Builder(context).data(it).build() } ?: connection?.let { plex ->
            plexArtworkUrl(album.thumb, plex)?.let { url -> ImageRequest.Builder(context).data(url).addHeader(PlexTokenHeaders.HEADER, plex.token).crossfade(180).build() }
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(0xFF124A68), Color(0xFF202C42)))), contentAlignment = Alignment.Center) {
        Icon(if (book) Icons.Rounded.Headphones else Icons.Rounded.Album, null, Modifier.fillMaxSize(.35f), tint = Color.White.copy(alpha = .38f))
        if (request != null) AsyncImage(model = request, contentDescription = if (labelled) stringResource(R.string.cover_of, album.title) else null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * Warms the image loader's disk cache with the first covers of Home's shelves for the library on screen, so they draw at
 * once after a mode switch or on returning Home. It waits a moment so the covers already on screen are requested first,
 * picks the URLs off the main thread and only enqueues requests. A new mode, library, catalogue or connection cancels
 * the pass, as does leaving the library; offline-only mode has no artwork connection, so nothing reaches the network.
 */
@Composable
internal fun PreloadHomeArtwork(state: PlexTouchUiState) {
    val context = LocalContext.current
    val connection = state.artworkConnection
    LaunchedEffect(connection, state.mode, state.selectedLibraryId, state.albums, state.history, state.accountScope) {
        if (connection == null || state.albums.isEmpty()) return@LaunchedEffect
        delay(PRELOAD_DELAY_MS)
        val urls = withContext(Dispatchers.Default) {
            val saved = if (state.mode == LibraryMode.AUDIOBOOK) state.listenAgainShelf else state.favouriteShelf
            artworkPreloadUrls(homeShelves(state.albums, state.history, saved, connection.serverUrl, state.mode, state.accountScope), connection)
        }
        val loader = context.imageLoader
        val requests: List<Disposable> = urls.map { url ->
            // Disk only, decoded tiny: the tile's own request decodes at its size and fills the memory cache.
            loader.enqueue(ImageRequest.Builder(context).data(url).addHeader(PlexTokenHeaders.HEADER, connection.token)
                .memoryCachePolicy(CachePolicy.DISABLED).size(PRELOAD_DECODE_PX).build())
        }
        try { awaitCancellation() } finally { requests.forEach(Disposable::dispose) }
    }
}

private const val PRELOAD_DELAY_MS = 350L
private const val PRELOAD_DECODE_PX = 16
