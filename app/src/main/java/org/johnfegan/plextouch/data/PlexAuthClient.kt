package org.johnfegan.plextouch.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.johnfegan.plextouch.BuildConfig
import androidx.annotation.StringRes
import org.johnfegan.plextouch.R

/** `routes` (ticket 136) are the same addresses as `connections`, with Plex's local/relay flags, for diagnostics. */
@androidx.compose.runtime.Immutable
data class PlexServer(
    val name: String,
    val connections: List<PlexConnection>,
    val routes: List<PlexRoute> = connections.map { PlexRoute.of(it.serverUrl) },
)

/** Plex device-link login, account-owned server discovery and Home switching; no password enters this app. */
class PlexAuthClient(private val clientId: String) : PlexAccountGateway, PlexHomeGateway {
    private val gson = Gson()

    override suspend fun createPin(): PendingPlexPin = withContext(Dispatchers.IO) {
        val connection = (URL("https://plex.tv/api/v2/pins?strong=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            headers(this)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            requireSuccess(connection, R.string.operation_sign_in)
            gson.fromJson(connection.inputStream.reader(), PinResponse::class.java).toPin()
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun pollPin(pin: PendingPlexPin): String? = withContext(Dispatchers.IO) {
        val encodedCode = URLEncoder.encode(pin.code, Charsets.UTF_8.name())
        val connection = request("https://plex.tv/api/v2/pins/${pin.id}?code=$encodedCode")
        try {
            requireSuccess(connection, R.string.operation_sign_in)
            gson.fromJson(connection.inputStream.reader(), PinResponse::class.java).authToken
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun servers(accountToken: String): List<PlexServer> = withContext(Dispatchers.IO) {
        val connection = request("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", accountToken)
        try {
            requireSuccess(connection, R.string.operation_server_discovery)
            gson.fromJson(connection.inputStream.reader(), Array<DeviceResponse>::class.java).mapNotNull { device ->
                if (!device.provides.orEmpty().split(',').contains("server")) return@mapNotNull null
                val routes = device.connections
                    .sortedWith(compareByDescending<ConnectionResponse> { it.local == true }.thenBy { it.relay == true })
                    .mapNotNull { c -> c.uri?.takeIf(String::isNotBlank)?.let { PlexRoute.of(it, c.local == true, c.relay == true) } }
                    .distinctBy { it.uri }
                if (routes.isEmpty()) return@mapNotNull null
                val token = device.accessToken ?: accountToken
                PlexServer(device.name.orEmpty(), routes.map { PlexConnection(it.uri, token) }, routes)
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun linkUrl(): String = "https://plex.tv/link"

    override suspend fun home(accountToken: String): PlexHome = withContext(Dispatchers.IO) {
        val users = read("https://plex.tv/api/v2/home/users", accountToken, R.string.operation_home_users, PlexHomeParser::users)
        // Only marks the current user; a failure here still lists the Home.
        val current = runCatching { read("https://plex.tv/api/v2/user", accountToken, R.string.operation_home_users, PlexHomeParser::currentUuid) }.getOrNull()
        PlexHome(users, current)
    }

    override suspend fun switchUser(accountToken: String, uuid: String, pin: String?): String = withContext(Dispatchers.IO) {
        val path = URLEncoder.encode(uuid, Charsets.UTF_8.name())
        val connection = (URL("https://plex.tv/api/v2/home/users/$path/switch").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            headers(this)
            setRequestProperty("X-Plex-Token", accountToken)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            // The PIN travels once, in the body (never the URL), and is not kept anywhere.
            val body = pin?.let { "pin=" + URLEncoder.encode(it, Charsets.UTF_8.name()) }.orEmpty()
            connection.outputStream.use { it.write(body.toByteArray()) }
            requireSuccess(connection, R.string.operation_home_switch)
            PlexHomeParser.switchedToken(connection.inputStream.reader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> read(url: String, token: String, @StringRes operation: Int, parse: (String) -> T): T {
        val connection = request(url, token)
        try {
            requireSuccess(connection, operation)
            return parse(connection.inputStream.reader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private fun request(url: String, token: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            headers(this)
            token?.let { setRequestProperty("X-Plex-Token", it) }
        }

    private fun headers(connection: HttpURLConnection) {
        connection.setRequestProperty("X-Plex-Product", BuildConfig.PRODUCT_NAME)
        connection.setRequestProperty("X-Plex-Version", BuildConfig.VERSION_NAME)
        connection.setRequestProperty("X-Plex-Model", "Android")
        connection.setRequestProperty("X-Plex-Platform", "Android")
        connection.setRequestProperty("X-Plex-Device", "Android")
        connection.setRequestProperty("X-Plex-Provides", "controller")
        connection.setRequestProperty("X-Plex-Client-Identifier", clientId)
    }

    /** 429 and 5xx stay retryable through [PlexHttpException.retryable]; every other failure is final. */
    private fun requireSuccess(connection: HttpURLConnection, @StringRes operation: Int) {
        val status = connection.responseCode
        if (status !in 200..299) throw PlexHttpException(status, operation)
    }

    private data class PinResponse(val id: Long, val code: String, val expiresIn: Long?, @SerializedName("authToken") val authToken: String?) {
        fun toPin() = PendingPlexPin(id, code, System.currentTimeMillis() + (expiresIn ?: 900L) * 1_000L)
    }
    private data class DeviceResponse(
        val name: String?,
        val provides: String?,
        @SerializedName("accessToken") val accessToken: String?,
        @SerializedName("connections") val connections: List<ConnectionResponse> = emptyList(),
    )
    private data class ConnectionResponse(val uri: String?, val local: Boolean?, val relay: Boolean?)
}
