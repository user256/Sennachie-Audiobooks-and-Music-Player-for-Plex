package org.johnfegan.plextouch.data

/** The deliberately small first-run choice. It is kept until each requested library is selected. */
enum class SetupPlan(val includesMusic: Boolean, val includesAudiobooks: Boolean) {
    MUSIC(true, false),
    AUDIOBOOKS(false, true),
    BOTH(true, true),
    ;

    val destination: LibraryMode get() = if (includesMusic) LibraryMode.MUSIC else LibraryMode.AUDIOBOOK
}

enum class SetupStep {
    WHAT_TO_SET_UP,
    SIGN_IN,
    SERVER,
    MUSIC_LIBRARY,
    AUDIOBOOK_LIBRARY,
}

/** One source of truth for both the on-screen wizard and resuming an interrupted setup. */
fun setupStep(
    plan: SetupPlan?,
    connected: Boolean,
    hasServers: Boolean,
    musicLibraryId: String?,
    audiobookLibraryId: String?,
): SetupStep? = when {
    plan == null -> null
    !connected && hasServers -> SetupStep.SERVER
    !connected -> SetupStep.SIGN_IN
    plan.includesMusic && musicLibraryId == null -> SetupStep.MUSIC_LIBRARY
    plan.includesAudiobooks && audiobookLibraryId == null -> SetupStep.AUDIOBOOK_LIBRARY
    else -> null
}
