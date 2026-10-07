package org.johnfegan.plextouch.sonos

import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.requireText
import org.johnfegan.plextouch.ui.uiText

/** A direct private IPv4 address avoids multicast discovery over routed VPNs. */
fun speakerDescriptionUrl(address: String): String {
    val parts = address.trim().split('.')
    requireText(parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }) { uiText(R.string.speaker_ip_invalid) }
    val octets = parts.map(String::toInt)
    requireText(octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && octets[1] in 16..31)) { uiText(R.string.speaker_ip_private) }
    return "http://${octets.joinToString(".")}:1400/xml/device_description.xml"
}

fun sonosDiscoveryRequest(): ByteArray = listOf(
    "M-SEARCH * HTTP/1.1", "HOST: 239.255.255.250:1900", "MAN: \"ssdp:discover\"", "MX: 2",
    "ST: urn:schemas-upnp-org:device:ZonePlayer:1", "", "",
).joinToString("\r\n").toByteArray(Charsets.US_ASCII)
