package org.johnfegan.plextouch

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import org.johnfegan.plextouch.data.ArtworkBudget

/**
 * Ticket 137: the one Coil image loader for the app (screens, Home preloading and the playback notification all use
 * `context.imageLoader`). Its disk cache keeps Coil's default folder, so covers cached before this change stay warm,
 * and is bounded by the budget chosen on the Storage screen. The budget is read once, when the cache first opens; a new
 * choice applies from the next app start, when Coil trims least-recently-used covers down to it.
 */
class PlexTouchApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve(ARTWORK_CACHE_DIRECTORY)).maxSizeBytes(artworkBudget(this).bytes).build() }
        .build()

    companion object {
        /** Coil's default disk-cache folder name. */
        const val ARTWORK_CACHE_DIRECTORY = "image_cache"
        private const val PREFS = "storage_settings"
        private const val ARTWORK_BUDGET = "artwork_budget_mb"

        /** Non-secret, so it lives in its own plain preference file rather than the encrypted store. */
        fun artworkBudget(context: Context): ArtworkBudget = ArtworkBudget.fromMegabytes(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(ARTWORK_BUDGET, ArtworkBudget.DEFAULT.megabytes))

        fun saveArtworkBudget(context: Context, budget: ArtworkBudget) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(ARTWORK_BUDGET, budget.megabytes) }
        }
    }
}
