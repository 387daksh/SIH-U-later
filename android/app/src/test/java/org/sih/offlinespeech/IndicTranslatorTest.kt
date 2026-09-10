package org.sih.offlinespeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicTranslatorTest {

    @Test
    fun translatesEmergencyVocabularyAcrossLanguages() {
        val helpInHindi = IndicTranslator.translate("help", "english", "hindi")
        assertEquals("मदद", helpInHindi)

        val helpInGujarati = IndicTranslator.translate("help", "english", "gujarati")
        assertEquals("મદદ", helpInGujarati)

        val ambulanceInMarathi = IndicTranslator.translate("ambulance", "english", "marathi")
        assertEquals("रुग्णवाहिका", ambulanceInMarathi)

        val fireInTamil = IndicTranslator.translate("fire", "english", "tamil")
        assertEquals("தீ", fireInTamil)

        val hospitalInBengali = IndicTranslator.translate("hospital", "english", "bengali")
        assertEquals("হাসপাতাল", hospitalInBengali)

        val injuryInTelugu = IndicTranslator.translate("injury", "english", "telugu")
        assertEquals("గాయం", injuryInTelugu)
    }

    @Test
    fun transliteratesBrahmicBetweenScripts() {
        // "मदद" in Devanagari transliterated into Gujarati script
        val gujarati = IndicTranslator.translate("मदद", "hindi", "gujarati")
        assertEquals("મદદ", gujarati)

        // "આગ" in Gujarati transliterated into Devanagari (Hindi)
        val hindi = IndicTranslator.translate("આગ", "gujarati", "hindi")
        assertEquals("आग", hindi)
    }

    @Test
    fun emergencyKeywordDetectionAccuratelyGatesAlerts() {
        // Emergency phrases in various languages must pass
        assertTrue(CriticalFieldDetector.containsEmergencyKeyword("I have an injury and need an ambulance"))
        assertTrue(CriticalFieldDetector.containsEmergencyKeyword("severe bleeding please help"))
        assertTrue(CriticalFieldDetector.containsEmergencyKeyword("मदद चाहिए बहुत चोट लगी है"))
        assertTrue(CriticalFieldDetector.containsEmergencyKeyword("અહીં આગ લાગી છે મદદ કરો"))
        assertTrue(CriticalFieldDetector.containsEmergencyKeyword("আক্রান্ত ও আহত মানুষ আছে সাহায্য দরকার"))

        // Casual conversation must NOT pass emergency check
        assertFalse(CriticalFieldDetector.containsEmergencyKeyword("Hello how are you doing today"))
        assertFalse(CriticalFieldDetector.containsEmergencyKeyword("The meeting is scheduled at gate five"))
        assertFalse(CriticalFieldDetector.containsEmergencyKeyword("हम कल मिलेंगे"))
        assertFalse(CriticalFieldDetector.containsEmergencyKeyword("તમે ક્યાં છો"))
    }
}
