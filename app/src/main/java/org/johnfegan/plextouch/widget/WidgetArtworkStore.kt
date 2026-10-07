package org.johnfegan.plextouch.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.security.MessageDigest

/**
 * Covers for the widget, always handed to the host as a small bitmap the app decoded itself, never as a URL: a Plex image
 * URL needs the token, and the widget host is another app. Sources are a download's saved cover or the JPEG bytes the
 * playback service already fetched (with the token header) for its notification, kept here in the app's private cache.
 */
object WidgetArtworkStore {
    private const val DIR = "widget_artwork"
    private const val KEEP = 8
    private const val SIZE_PX = 192

    private fun dir(context: Context) = File(context.cacheDir, DIR)
    private fun file(context: Context, key: String) = File(dir(context), MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg")

    /** Keeps a downscaled copy of bytes the service fetched; runs on the service's IO dispatcher. */
    fun remember(context: Context, key: String, bytes: ByteArray) {
        runCatching {
            val target = file(context, key)
            if (target.isFile) { target.setLastModified(System.currentTimeMillis()); return }
            val bitmap = decode(bytes.size.toLong()) { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) } ?: return
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, target.name + ".part")
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            partial.renameTo(target)
            dir(context).listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        }
    }

    /** Forgets every cached cover, for example once nobody is signed in. */
    fun clear(context: Context) { dir(context).takeIf { it.isDirectory }?.deleteRecursively() }

    /** The widget's bitmap for `artwork`, or null for the placeholder. A download's cover must live in the app's own storage. */
    fun load(context: Context, artwork: WidgetArtwork?): Bitmap? = runCatching {
        val source = when (artwork) {
            null -> null
            is WidgetArtwork.Fetched -> file(context, artwork.key).takeIf { it.isFile }
            is WidgetArtwork.Downloaded -> artwork.uri.toUri().takeIf { it.scheme == "file" }?.toFile()?.canonicalFile
                ?.takeIf { file -> file.isFile && ownedRoots(context).any { file.path.startsWith(it + File.separator) } }
        } ?: return@runCatching null
        decode(source.length()) { options -> BitmapFactory.decodeFile(source.path, options) }
    }.getOrNull()

    private fun ownedRoots(context: Context): List<String> =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir, context.cacheDir).map { it.canonicalPath }

    /** Decodes at most [SIZE_PX] on the long edge, sampling first so a large cover never needs a full-size bitmap. */
    private fun decode(length: Long, read: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        if (length <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE_PX) sample *= 2
        val bitmap = read(BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= SIZE_PX) return bitmap
        val ratio = SIZE_PX.toFloat() / longest
        return bitmap.scale((bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1))
    }
}
