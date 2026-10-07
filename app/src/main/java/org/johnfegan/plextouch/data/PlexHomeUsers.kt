package org.johnfegan.plextouch.data

import androidx.compose.runtime.Immutable
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.checkText
import org.johnfegan.plextouch.ui.uiText

/**
 * One member of the signed-in Plex Home (ticket 136), as `GET plex.tv/api/v2/home/users` lists them. No token: Plex only
 * issues one for a user through [PlexHomeGateway.switchUser], and only that user's token is ever kept.
 */
@Immutable
data class PlexHomeUser(
    val id: Long,
    val uuid: String,
    val title: String,
    val thumb: String? = null,
    val restricted: Boolean = false,
    /** The user has a Home PIN; switching needs it, entered by the person each time and never saved. */
    val protected: Boolean = false,
    val admin: Boolean = false,
)

/** The Home's members and which of them the saved account token belongs to (null when Plex did not say). */
@Immutable
data class PlexHome(val users: List<PlexHomeUser>, val currentUuid: String?)

/** plex.tv's supported Home switching flow; [PlexAuthClient] is the real one. */
interface PlexHomeGateway {
    /** The Home's users plus the current user's uuid (`GET /api/v2/user`). */
    suspend fun home(accountToken: String): PlexHome

    /**
     * `POST /api/v2/home/users/{uuid}/switch`, with `pin` as a form field for a protected user. Returns the switched user's
     * own `authToken`. A wrong PIN is a non-retryable [PlexHttpException] (Plex answers 401, 403 or 422).
     */
    suspend fun switchUser(accountToken: String, uuid: String, pin: String?): String
}

/** Pure response parsing, so the JVM tests cover the shapes Plex returns without a network. */
object PlexHomeParser {
    /** `/api/v2/home/users` answers `{ ..., "users": [...] }`; a bare array is accepted too. Users without a uuid are dropped. */
    fun users(json: String): List<PlexHomeUser> {
        val root = JsonParser.parseString(json)
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.get("users")?.isJsonArray == true -> root.asJsonObject.getAsJsonArray("users")
            else -> return emptyList()
        }
        return array.filter { it.isJsonObject }.mapNotNull { element ->
            val user = element.asJsonObject
            val uuid = user.text("uuid") ?: return@mapNotNull null
            PlexHomeUser(
                id = user.text("id")?.toLongOrNull() ?: 0L,
                uuid = uuid,
                title = user.text("title") ?: user.text("username") ?: uuid,
                thumb = user.text("thumb"),
                restricted = user.flag("restricted"),
                protected = user.flag("protected"),
                admin = user.flag("admin"),
            )
        }
    }

    /** `/api/v2/user`: the uuid of the account the token belongs to. */
    fun currentUuid(json: String): String? = runCatching { JsonParser.parseString(json).asJsonObject.text("uuid") }.getOrNull()

    /** The switch response is the switched user, carrying their `authToken`. */
    fun switchedToken(json: String): String {
        val token = runCatching { JsonParser.parseString(json).asJsonObject.text("authToken") }.getOrNull()
        checkText(!token.isNullOrBlank()) { uiText(R.string.home_switch_no_token) }
        return token
    }

    private fun JsonObject.text(name: String): String? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.flag(name: String): Boolean {
        val value = get(name)?.takeUnless(JsonElement::isJsonNull)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return false
        return if (value.isBoolean) value.asBoolean else value.asString == "1" || value.asString.equals("true", ignoreCase = true)
    }
}

/**
 * The store half of a Home switch, platform-free so the JVM tests run exactly what [PlexStore] runs. Under the store's
 * one process-wide lock it first quarantines every queued timeline event that is not the new user's (they can never be
 * sent as the new user; they surface for review instead), then writes the user's token and the server route together.
 * [write] must commit synchronously and report success; on failure the previous sign-in stays saved and the error
 * surfaces (events already quarantined stay quarantined, which is the safe side).
 */
class AccountSwitchWriter(
    private val lock: Any,
    private val timeline: TimelineStateStore,
    private val write: (accountToken: String, serverUrl: String, serverToken: String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun switch(accountToken: String, serverUrl: String, serverToken: String): PlexConnection = synchronized(lock) {
        val account = accountToken.trim()
        val connection = PlexConnection(serverUrl.trim().trimEnd('/'), serverToken.trim())
        checkText(account.isNotEmpty() && connection.serverUrl.isNotEmpty() && connection.token.isNotEmpty()) { uiText(R.string.connection_not_saved) }
        timeline.update { TimelineOutbox.accountChanged(it, connection.progressScope(account), now()) }
        checkText(write(account, connection.serverUrl, connection.token)) { uiText(R.string.connection_not_saved) }
        connection
    }
}

/** A Home avatar is drawn only from plex.tv over https, with the token as a header; anything else shows initials. */
fun homeThumbUrl(thumb: String?): String? {
    if (thumb == null) return null
    val uri = runCatching { java.net.URI(thumb) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return thumb.takeIf { uri.scheme == "https" && uri.rawUserInfo == null && (host == "plex.tv" || host.endsWith(".plex.tv")) }
}
