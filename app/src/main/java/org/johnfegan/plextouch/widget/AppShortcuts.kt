package org.johnfegan.plextouch.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.johnfegan.plextouch.MainActivity
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.ui.LibraryTab

/**
 * The launcher shortcuts (ticket 139). Each is only a name the activity maps to a screen: no token, server address or
 * media URL travels in the intent, so a launcher, backup or another app that sees it learns nothing private.
 */
enum class AppShortcut(val id: String) {
    RESUME_AUDIOBOOK("resume_audiobook"),
    OPEN_MUSIC("open_music"),
    DOWNLOADS("downloads"),
}

/** What the app does for a shortcut: switch mode and tab, then (for resume) continue the last audiobook if there is one. */
data class ShortcutPlan(val mode: LibraryMode?, val tab: LibraryTab?, val resume: Boolean)

const val ACTION_SHORTCUT = "org.johnfegan.plextouch.action.SHORTCUT"
const val EXTRA_SHORTCUT = "org.johnfegan.plextouch.extra.SHORTCUT"

/** The shortcut an incoming intent asks for; anything unexpected (another action, an unknown name) is ignored. */
fun shortcutFor(action: String?, name: String?): AppShortcut? =
    if (action != ACTION_SHORTCUT) null else AppShortcut.entries.firstOrNull { it.id == name }

/**
 * Signed out, every shortcut just opens the app (the setup screen); nothing is resumed and no screen is forced. Downloads
 * keeps the current mode; resume only plays when last-played audiobook history exists, which the caller checks.
 */
fun shortcutPlan(shortcut: AppShortcut, signedIn: Boolean): ShortcutPlan? = if (!signedIn) null else when (shortcut) {
    AppShortcut.RESUME_AUDIOBOOK -> ShortcutPlan(LibraryMode.AUDIOBOOK, LibraryTab.HOME, resume = true)
    AppShortcut.OPEN_MUSIC -> ShortcutPlan(LibraryMode.MUSIC, LibraryTab.LIBRARY, resume = false)
    AppShortcut.DOWNLOADS -> ShortcutPlan(null, LibraryTab.DOWNLOADS, resume = false)
}

fun shortcutIntent(context: Context, shortcut: AppShortcut): Intent =
    Intent(context, MainActivity::class.java).setAction(ACTION_SHORTCUT).putExtra(EXTRA_SHORTCUT, shortcut.id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Publishes the dynamic shortcuts with labels from the current locale; called when the activity starts in the foreground. */
object AppShortcuts {
    fun publish(context: Context) {
        val appName = context.getString(R.string.app_name)
        val shortcuts = listOf(
            Triple(AppShortcut.RESUME_AUDIOBOOK, R.string.shortcut_resume_short, R.drawable.ic_shortcut_resume),
            Triple(AppShortcut.OPEN_MUSIC, R.string.shortcut_music_short, R.drawable.ic_shortcut_music),
            Triple(AppShortcut.DOWNLOADS, R.string.shortcut_downloads_short, R.drawable.ic_shortcut_downloads),
        ).mapIndexed { rank, (shortcut, label, icon) ->
            ShortcutInfoCompat.Builder(context, shortcut.id)
                .setShortLabel(context.getString(label))
                .setLongLabel(context.getString(R.string.shortcut_long_label, context.getString(label), appName))
                .setIcon(IconCompat.createWithResource(context, icon))
                .setIntent(shortcutIntent(context, shortcut))
                .setRank(rank)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    /** Lets the launcher rank shortcuts by use. */
    fun reportUsed(context: Context, shortcut: AppShortcut) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, shortcut.id) }
    }
}
