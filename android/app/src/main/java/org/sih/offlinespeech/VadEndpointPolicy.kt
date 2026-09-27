package org.sih.offlinespeech

/**
 * Decides when a pause has lasted long enough to end an utterance.
 *
 * Kept pure and free of ONNX/Android so it is exercised by JVM unit tests: [SileroVadEngine] needs a
 * real model file to instantiate, which previously made this logic untestable even though it is the
 * part most likely to be wrong.
 *
 * A single fixed silence window is a hard latency floor under every utterance: waiting 500 ms after
 * a speaker clearly finished a sentence adds 500 ms to every message. But shortening it for all
 * speech truncates a hesitant speaker who pauses mid-sentence. So the window adapts: long while the
 * utterance is still short and might be a false start, shorter once enough genuinely voiced audio
 * has accumulated that this is certainly a real utterance being finished.
 */
object VadEndpointPolicy {

    /**
     * Number of consecutive silent chunks required to close the current utterance.
     *
     * @param voicedChunks chunks so far whose probability cleared the speech threshold. Counted from
     *   voiced audio only, never from pre-roll or trailing silence, so padding cannot make a
     *   fragment look confident.
     * @param pushToTalkHeld true while the user physically holds the talk button. Intent is not in
     *   doubt then, so the shortest window applies and a phrase spoken mid-hold is sent without
     *   waiting out a full pause.
     */
    fun silenceChunksRequired(config: VadConfig, voicedChunks: Int, pushToTalkHeld: Boolean = false): Int = when {
        pushToTalkHeld -> config.minSilenceChunksHeld
        voicedChunks >= config.confidentSpeechChunks -> config.minSilenceChunksConfident
        else -> config.minSilenceChunks
    }

    /** True when [silenceChunks] of pause is enough to end an utterance with this much voiced audio. */
    fun shouldEndUtterance(
        config: VadConfig,
        voicedChunks: Int,
        silenceChunks: Int,
        pushToTalkHeld: Boolean = false,
    ): Boolean = silenceChunks >= silenceChunksRequired(config, voicedChunks, pushToTalkHeld)

    /**
     * Milliseconds of pause the current state is waiting for — for status text and telemetry, so the
     * chosen window is observable rather than an invisible constant.
     */
    fun silenceWindowMs(config: VadConfig, voicedChunks: Int, pushToTalkHeld: Boolean = false): Int =
        silenceChunksRequired(config, voicedChunks, pushToTalkHeld) * config.chunkSamples * 1000 / config.sampleRate
}
