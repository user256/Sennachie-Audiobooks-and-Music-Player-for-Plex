package org.johnfegan.plextouch.sonos

import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.johnfegan.plextouch.data.PlexConnection
import org.johnfegan.plextouch.data.PlexTrack
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.InvalidStateException
import org.johnfegan.plextouch.ui.requireText
import org.johnfegan.plextouch.ui.uiText

@androidx.compose.runtime.Immutable
data class SonosSpeaker(val name: String, val location: String, val model: String? = null) {
    val host: String get() = URL(location).host
}

/** Local-network Sonos control. Plex URLs include the token because the speaker fetches them. */
class SonosClient(private val wifi: WifiManager) : SpeakerGateway {
    override suspend fun discover(directoryToken: String, preferDirectory: Boolean): SpeakerDiscovery = withContext(Dispatchers.IO) {
        discoverSpeakers(::discoverLocal, fallback = null, preferFallback = false)
    }

    private fun discoverLocal(): List<SonosSpeaker> {
        val lock = wifi.createMulticastLock("plex-touch-sonos").apply { setReferenceCounted(false); acquire() }
        return try {
            val payload = sonosDiscoveryRequest()
            DatagramSocket().use { socket ->
                socket.soTimeout = 2_600
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("239.255.255.250"), 1900))
                val locations = linkedSetOf<String>()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (System.nanoTime() < deadline) {
                    val bytes = ByteArray(4_096)
                    try {
                        val packet = DatagramPacket(bytes, bytes.size)
                        socket.receive(packet)
                        String(packet.data, 0, packet.length).lineSequence()
                            .firstOrNull { it.startsWith("location:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.let(locations::add)
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                }
                locations.mapNotNull { location ->
                    runCatching {
                        identify(speakerDescriptionUrl(URL(location).host))
                    }.getOrNull()
                }
            }
        } finally {
            lock.release()
        }
    }

    override suspend fun connect(address: String): SonosSpeaker = withContext(Dispatchers.IO) {
        identify(speakerDescriptionUrl(address))
    }

    private fun identify(location: String): SonosSpeaker {
        val xml = get(location)
        // Android's DOM factory doesn't support the Xerces security feature flags.
        // Reject DTDs before using Android's non-DTD-processing pull parser.
        requireText(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { uiText(R.string.speaker_unsupported_description) }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(StringReader(xml))
        }
        val tags = mutableMapOf<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name in setOf("manufacturer", "roomName", "friendlyName", "modelName")) {
                val name = parser.name
                tags.putIfAbsent(name, parser.nextText())
            }
        }
        fun tag(name: String) = tags[name]
        requireText(tag("manufacturer")?.contains("Sonos", ignoreCase = true) == true) { uiText(R.string.speaker_not_sonos) }
        return SonosSpeaker(tag("roomName") ?: tag("friendlyName") ?: URL(location).host, location, tag("modelName"))
    }

    override suspend fun play(speaker: SonosSpeaker, track: PlexTrack, connection: PlexConnection) = withContext(Dispatchers.IO) {
        val stream = connection.streamUrl(track.streamPath)
        val metadata = didl(track, stream)
        val endpoint = "http://${speaker.host}:1400/MediaRenderer/AVTransport/Control"
        soap(endpoint, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${escape(stream)}</CurrentURI><CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>")
        soap(endpoint, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    private fun get(location: String): String = (URL(location).openConnection() as HttpURLConnection).run {
        connectTimeout = 5_000
        readTimeout = 5_000
        try { inputStream.bufferedReader().use { it.readText() } } finally { disconnect() }
    }

    private fun soap(endpoint: String, action: String, args: String) {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$args</u:$action></s:Body></s:Envelope>"""
        (URL(endpoint).openConnection() as HttpURLConnection).run {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            outputStream.use { it.write(body.toByteArray()) }
            if (responseCode !in 200..299) throw InvalidStateException(uiText(R.string.sonos_http_failed, responseCode))
            disconnect()
        }
    }

    private fun didl(track: PlexTrack, stream: String) = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="plex:${track.id}" parentID="-1" restricted="true"><dc:title>${escape(track.title)}</dc:title><dc:creator>${escape(track.artist)}</dc:creator><upnp:album>${escape(track.album)}</upnp:album><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:audio/${track.container ?: "mpeg"}:*">${escape(stream)}</res></item></DIDL-Lite>"""

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
