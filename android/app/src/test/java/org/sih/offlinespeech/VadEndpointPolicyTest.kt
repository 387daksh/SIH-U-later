package org.sih.offlinespeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadEndpointPolicyTest {

    private val config = VadConfig()

    @Test
    fun shortUtteranceWaitsTheFullWindow() {
        // A hesitant speaker who has barely started must not be cut off early.
        val required = VadEndpointPolicy.silenceChunksRequired(config, voicedChunks = 1)
        assertEquals(config.minSilenceChunks, required)
        assertFalse(VadEndpointPolicy.shouldEndUtterance(config, 1, config.minSilenceChunks - 1))
        assertTrue(VadEndpointPolicy.shouldEndUtterance(config, 1, config.minSilenceChunks))
    }

    @Test
    fun confidentUtteranceEndsOnTheShorterWindow() {
        val required = VadEndpointPolicy.silenceChunksRequired(config, config.confidentSpeechChunks)
        assertEquals(config.minSilenceChunksConfident, required)
        assertTrue(required < config.minSilenceChunks)
        assertTrue(VadEndpointPolicy.shouldEndUtterance(config, config.confidentSpeechChunks, required))
    }

    @Test
    fun heldTalkButtonUsesTheShortestWindow() {
        val held = VadEndpointPolicy.silenceChunksRequired(config, voicedChunks = 1, pushToTalkHeld = true)
        assertEquals(config.minSilenceChunksHeld, held)
        // Holding the button overrides the short-utterance caution: intent is not in doubt.
        assertTrue(held <= VadEndpointPolicy.silenceChunksRequired(config, 1, pushToTalkHeld = false))
    }

    @Test
    fun theShorterWindowsAreNeverLongerThanTheBaseWindow() {
        // Guards a misconfiguration where a "shorter" window is set above the default and would
        // silently make endpointing slower than before.
        val odd = VadConfig(minSilenceDurationMs = 300, minSilenceDurationConfidentMs = 900, minSilenceDurationHeldMs = 800)
        assertTrue(odd.minSilenceChunksConfident <= odd.minSilenceChunks)
        assertTrue(odd.minSilenceChunksHeld <= odd.minSilenceChunks)
    }

    @Test
    fun windowShortensAsVoicedAudioAccumulates() {
        val cold = VadEndpointPolicy.silenceWindowMs(config, voicedChunks = 2)
        val warm = VadEndpointPolicy.silenceWindowMs(config, voicedChunks = config.confidentSpeechChunks)
        assertTrue("confident window should be shorter: cold=$cold warm=$warm", warm < cold)
    }

    @Test
    fun preRollIsAtLeastTheTrailingPadAndCoversUnvoicedOnsets() {
        // Pre-roll exists to recover /k/ /t/ /p/ bursts that precede the VAD trigger, so it must be
        // at least the trailing pad and long enough to hold a plosive onset.
        assertTrue(config.preRollChunks >= config.speechPadChunks)
        val preRollMs = config.preRollChunks * config.chunkSamples * 1000 / config.sampleRate
        assertTrue("pre-roll too short for a plosive onset: ${preRollMs}ms", preRollMs >= 128)
    }

    @Test
    fun derivedChunkCountsAreNeverZero() {
        val tiny = VadConfig(minSpeechDurationMs = 1, minSilenceDurationMs = 1, speechPadMs = 1, preRollMs = 1, confidentSpeechMs = 1)
        assertTrue(tiny.minSpeechChunks >= 1)
        assertTrue(tiny.minSilenceChunks >= 1)
        assertTrue(tiny.speechPadChunks >= 1)
        assertTrue(tiny.preRollChunks >= 1)
        assertTrue(tiny.confidentSpeechChunks >= 1)
    }
}
