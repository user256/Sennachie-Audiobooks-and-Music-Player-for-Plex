package org.johnfegan.plextouch.data

import java.io.IOException
import androidx.annotation.StringRes
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.UiText
import org.johnfegan.plextouch.ui.UserFacing
import org.johnfegan.plextouch.ui.uiText

/**
 * A non-2xx reply from Plex, typed so callers branch on the status rather than on message text.
 *
 * It extends [IOException] because transient statuses (429, 5xx) belong with network interruptions in
 * retry loops; [retryable] tells those loops when to stop. [text] keeps the long-standing
 * "<operation> returned HTTP <status>" wording the UI surfaces; the exception message is only a diagnostic.
 */
class PlexHttpException(val status: Int, @StringRes operation: Int = R.string.operation_plex) : IOException("HTTP $status"), UserFacing {
    override val text: UiText = uiText(R.string.http_failed, uiText(operation), status)

    /** Rate limiting and server errors are worth another attempt; everything else is a definite answer. */
    val retryable: Boolean get() = status == 429 || status in 500..599

    /** The token was refused: retrying with the same credentials cannot succeed. */
    val authentication: Boolean get() = status == 401 || status == 403
}
