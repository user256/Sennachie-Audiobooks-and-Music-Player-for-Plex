package org.johnfegan.plextouch.player

import org.johnfegan.plextouch.data.AudioEffectsSettings
import org.johnfegan.plextouch.data.LibraryMode
import org.johnfegan.plextouch.data.PlexAlbum
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexTrack
import org.johnfegan.plextouch.data.progressScope

/** The commands the app sends to the player; [PlexPlayer] forwards them to the media session. */
interface PlaybackController {
    fun play(
        album: PlexAlbum, tracks: List<PlexTrack>, connection: PlexConnection, mode: LibraryMode,
        accountScope: String = connection.progressScope(), index: Int = 0, positionMs: Long = 0,
        infinite: Boolean = false, shuffled: Boolean = false, speed: Float = 1f,
    )
    fun toggle()
    fun pause()
    fun stop()
    fun next()
    fun previous()
    fun seek(positionMs: Long)
    fun skip(deltaMs: Long)
    fun speed(value: Float)
    fun repeat()
    fun shuffle()
    /** Jumps to a queue entry; `positionMs` lands inside it, which is how embedded chapters of a single file are reached. */
    fun chapter(index: Int, positionMs: Long = 0)
    fun queue(): List<PlexTrack>
    fun sleep(minutes: Int)
    fun release()
    /** Phone-only sound processing (ticket 135); the service applies it to its own audio session. */
    fun effects(settings: AudioEffectsSettings)
}
