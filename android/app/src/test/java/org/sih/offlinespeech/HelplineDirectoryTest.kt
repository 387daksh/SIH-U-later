package org.sih.offlinespeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelplineDirectoryTest {
    @Test fun indiaWideEmergencyFallbackIsAlwaysPresent() {
        assertTrue(HelplineDirectory.contacts.any {
            it.number == HelplineDirectory.emergencyNumber && it.scope == "India-wide"
        })
    }

    @Test fun nationalPortalContactsAreOfflineAndNumbersAreDialableIndividually() {
        assertEquals(38, HelplineDirectory.contacts.size)
        assertTrue(HelplineDirectory.contacts.all { it.number.matches(Regex("[0-9-]+")) })
        assertTrue(HelplineDirectory.contacts.any { it.number == "1933" && it.name.contains("Narcotics") })
        assertTrue(HelplineDirectory.contacts.any { it.number == "1070" })
        assertTrue(HelplineDirectory.contacts.any { it.number == "1077" })
        assertTrue(HelplineDirectory.districtControlSourceUrl.contains("mha.gov.in"))
        assertTrue(HelplineDirectory.contacts.any { it.number == "040-66720567" && it.scope == "Jammu & Kashmir" })
        assertEquals("2026-09-26", HelplineDirectory.lastChecked)
    }

    @Test fun nearestResultsOnlyIncludeFacilitiesWithCoordinates() {
        assertTrue(HelplineDirectory.nearestFacilities(28.6139, 77.2090).isEmpty())
    }

    @Test fun invalidCoordinatesDoNotReturnNearbyFacilities() {
        assertEquals(emptyList<Pair<HelplineContact, Double>>(), HelplineDirectory.nearestFacilities(95.0, 200.0))
    }
}
