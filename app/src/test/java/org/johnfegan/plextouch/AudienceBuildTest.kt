package org.johnfegan.plextouch

import org.junit.Assert.*
import org.junit.Test

class AudienceBuildTest {
    @Test fun publicBuildHasTheExpectedIdentityAndNoHouseholdIntegration() {
        assertEquals("org.johnfegan.musicbooks", BuildConfig.APPLICATION_ID)
        assertEquals("Sennachie for Plex", BuildConfig.PRODUCT_NAME)
        assertFalse(BuildConfig.HOUSEHOLD_INTEGRATION)
    }
}
