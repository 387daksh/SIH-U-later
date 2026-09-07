package org.sih.offlinespeech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class AudioPipelineTest {
    @Test
    fun mergesPushToTalkChunksInOrder() {
        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f),
            mergeAudioChunks(listOf(floatArrayOf(1f, 2f), floatArrayOf(3f))),
            0f,
        )
    }

    @Test
    fun streamingResampleMatchesOneShot() {
        val input = FloatArray(4_800) { sin(it / 17.0).toFloat() }
        val expected = LinearResampler(48_000).resample(input, input.size)
        val streaming = LinearResampler(48_000)
        val chunks = ArrayList<Float>()
        var offset = 0
        while (offset < input.size) {
            val count = minOf(333, input.size - offset)
            streaming.resample(input.copyOfRange(offset, offset + count), count).forEach { chunks.add(it) }
            offset += count
        }

        assertEquals(1_600, expected.size)
        assertArrayEquals(expected, chunks.toFloatArray(), 0.000001f)
    }
}
