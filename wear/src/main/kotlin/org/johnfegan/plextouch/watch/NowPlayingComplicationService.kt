package org.johnfegan.plextouch.watch

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import org.johnfegan.plextouch.wear.shared.PhoneStatus

/**
 * Audiobook progress on the watch face (ticket 141): the percentage listened of the phone's current or last audiobook,
 * with the title in the content description so a screen reader says what the number means. It is refreshed when the
 * phone publishes, never polled, and shows nothing when the phone is signed out or has no audiobook.
 */
class NowPlayingComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, getString(R.string.complication_preview_title), 420)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        WatchRepository.load(this)
        val state = WatchRepository.phone.value
        val book = state?.nowPlaying?.takeIf { state.status != PhoneStatus.SIGNED_OUT && it.audiobook }
            ?: return NoDataComplicationData()
        return data(request.complicationType, book.title, book.bookPermille)
    }

    private fun data(type: ComplicationType, title: String, permille: Int): ComplicationData? {
        val percent = (permille / 10).coerceIn(0, 100)
        val text = PlainComplicationText.Builder(getString(R.string.complication_percent, percent)).build()
        val description = PlainComplicationText.Builder(getString(R.string.complication_description, title, percent)).build()
        val tap = PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, description).setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(percent.toFloat(), 0f, 100f, description)
                .setText(text).setTapAction(tap).build()
            else -> null
        }
    }
}
