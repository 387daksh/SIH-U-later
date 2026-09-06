package org.sih.offlinespeech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleFrameCodecTest {
    @Test
    fun restoresFrameAcrossSmallGattFragments() {
        val original = ByteArray(700) { (it * 31).toByte() }
        val assembler = BleFrameAssembler(1_024)
        var rebuilt: ByteArray? = null
        BleFrameCodec.fragment(original, 20).forEach { fragment ->
            val next = assembler.offer(fragment)
            if (next != null) rebuilt = next
        }
        assertArrayEquals(original, rebuilt)
    }

    @Test
    fun doesNotEmitAnIncompleteFrame() {
        val assembler = BleFrameAssembler(100)
        val parts = BleFrameCodec.fragment(ByteArray(50), 20)
        assertNull(assembler.offer(parts.first()))
    }
}
