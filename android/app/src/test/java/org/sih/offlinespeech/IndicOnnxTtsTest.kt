package org.sih.offlinespeech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicOnnxTtsTest {
    private val processor = IndicTtsTextProcessor(
        listOf("<PAD>", " ", "a", "b", "म", "द", "।", "<BLNK>"),
        maxChunkChars = 5,
    )

    @Test
    fun normalisesFiltersAndTokenisesSupportedScripts() {
        assertArrayEquals(longArrayOf(2, 3, 1, 4, 5, 5, 6), processor.tokens("AB  मदद। x"))
    }

    @Test
    fun splitsLongUnpunctuatedTextAtWords() {
        assertEquals(listOf("ab ab", "ab"), processor.chunks("AB AB AB"))
    }

    @Test
    fun buildsOneWordPitchBiasAndTerminalPause() {
        val withPeriod = IndicTtsTextProcessor(listOf("<PAD>", " ", ".", "a", "b"), 20)
        val (tokens, pitch, duration) = withPeriod.prosodyInputs("ab ab", "ab", 0.4f)
        assertArrayEquals(longArrayOf(3, 4, 1, 3, 4, 2), tokens)
        assertArrayEquals(floatArrayOf(0.4f, 0.4f, 0f, 0f, 0f, 0f), pitch, 0f)
        assertTrue(duration.all { it == 1f })
    }

    @Test
    fun alignsOddSampleRateBufferToFloatFrames() {
        assertEquals(22_052, pcmFloatBufferBytes(-2, 22_050))
        assertEquals(32_768, pcmFloatBufferBytes(32_768, 22_050))
    }

    @Test
    fun duplexGateStaysMutedUntilEveryPlaybackFinishes() {
        AudioDuplexGate.reset()
        AudioDuplexGate.beginPlayback()
        AudioDuplexGate.beginPlayback()
        AudioDuplexGate.endPlayback()
        assertEquals(true, AudioDuplexGate.isPlaybackActive)
        AudioDuplexGate.endPlayback()
        assertEquals(true, AudioDuplexGate.isPlaybackActive)
        AudioDuplexGate.reset()
        AudioDuplexGate.endPlayback()
        assertEquals(false, AudioDuplexGate.isPlaybackActive)
    }

    @Test
    fun normalizesAll10SutraLanguagesCorrectly() {
        assertEquals("en-hi", IndicOnnxTts.normalizeLanguage("english"))
        assertEquals("en-hi", IndicOnnxTts.normalizeLanguage("hindi"))
        assertEquals("en-hi", IndicOnnxTts.normalizeLanguage("en"))
        assertEquals("en-hi", IndicOnnxTts.normalizeLanguage("hi"))
        assertEquals("gu", IndicOnnxTts.normalizeLanguage("gujarati"))
        assertEquals("mr", IndicOnnxTts.normalizeLanguage("marathi"))
        assertEquals("kn", IndicOnnxTts.normalizeLanguage("kannada"))
        assertEquals("ml", IndicOnnxTts.normalizeLanguage("malayalam"))
        assertEquals("ta", IndicOnnxTts.normalizeLanguage("tamil"))
        assertEquals("te", IndicOnnxTts.normalizeLanguage("telugu"))
        assertEquals("or", IndicOnnxTts.normalizeLanguage("odia"))
        assertEquals("bn", IndicOnnxTts.normalizeLanguage("bengali"))
    }

    @Test
    fun processesDravidianAndIndicScriptsWithDistinctProcessors() {
        // Tamil processor test
        val tamilProc = IndicTtsTextProcessor(
            listOf("<PAD>", " ", "உ", "த", "வ", "ி"),
            maxChunkChars = 10,
        )
        assertArrayEquals(longArrayOf(2, 3, 4, 5), tamilProc.tokens("உதவி"))

        // Bengali processor test
        val bengaliProc = IndicTtsTextProcessor(
            listOf("<PAD>", " ", "স", "া", "হ", "য", "্"),
            maxChunkChars = 10,
        )
        assertArrayEquals(longArrayOf(2, 3, 4, 3, 5, 6, 5), bengaliProc.tokens("সাহায্য"))
    }
}
