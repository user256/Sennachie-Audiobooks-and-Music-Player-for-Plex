package org.johnfegan.plextouch.sonos

import org.junit.Assert.*
import org.junit.Test

class SonosAddressTest {
    @Test fun acceptsPrivateAddressesAndPinsTheSonosPortAndPath() {
        listOf("192.168.1.53", "10.1.2.3", "172.16.1.2", "172.31.1.2").forEach {
            assertEquals("http://$it:1400/xml/device_description.xml", speakerDescriptionUrl(" $it "))
        }
        assertEquals("http://192.168.1.53:1400/xml/device_description.xml", speakerDescriptionUrl("192.168.001.053"))
    }

    @Test fun rejectsPublicMalformedAndCredentialBearingAddresses() {
        listOf("", "8.8.8.8", "127.0.0.1", "172.15.1.2", "172.32.1.2", "192.168.1.256", "192.168.1.-1",
            "192.168.1.2:1400", "http://192.168.1.2", "user:secret@192.168.1.2", "192.168.1.2/path", "speaker.local").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { speakerDescriptionUrl(it) }
        }
    }

    @Test fun discoveryUsesRealCrLfAndQuotedSsdpHeader() {
        val request = sonosDiscoveryRequest().toString(Charsets.US_ASCII)
        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("\r\nMAN: \"ssdp:discover\"\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
        assertFalse(request.contains("\\r"))
        assertFalse(request.contains("\\\""))
    }
}
