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
}
