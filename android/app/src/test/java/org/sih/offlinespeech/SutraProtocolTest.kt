package org.sih.offlinespeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SutraProtocolTest {
    @Test
    fun multilingualEnvelopeRoundTripsAndRejectsCorruption() {
        val original = SutraMessage(
            SutraMessageType.TEXT_FINAL,
            SutraLanguage.HINDI,
            "गेट चार पर मत जाना".toByteArray(),
            SutraId.random(),
            sourceId = 17,
            destinationId = 23,
            flags = SutraFlags.FINAL or SutraFlags.REQUIRES_ACK,
        )
        val encoded = SutraCodec.encode(original)
        val decoded = SutraCodec.decode(encoded)

        assertEquals(original.type, decoded.type)
        assertEquals(original.language, decoded.language)
        assertEquals(original.id, decoded.id)
        assertEquals(original.sourceId, decoded.sourceId)
        assertEquals("गेट चार पर मत जाना", SutraCodec.utf8(decoded.payload))
        assertEquals(original, decoded)
        assertEquals(original.hashCode(), decoded.hashCode())

        encoded[encoded.lastIndex - 2] = (encoded[encoded.lastIndex - 2].toInt() xor 1).toByte()
        val error = runCatching { SutraCodec.decode(encoded) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun alertSendsCoreThenTranscriptThenOnlyUncertainCriticalEvidence() {
        val layers = SutraSemanticEncoder.build(
            language = "english",
            text = "Do not send two ambulances to gate four",
            confidence = 0.91,
            alert = true,
            alertCategory = "Medical",
            sourceId = 99,
            wordConfidences = listOf(0.95, 0.20, 0.95, 0.30, 0.91, 0.95, 0.93, 0.88),
        )

        assertEquals(
            listOf(SutraMessageType.ALERT_CORE, SutraMessageType.TEXT_FINAL, SutraMessageType.REPAIR_EVIDENCE),
            layers.messages.map { it.type }
        )
        assertTrue(layers.fields.any { it.kind == CriticalKind.NEGATION && it.text == "not" })
        assertTrue(layers.fields.any { it.kind == CriticalKind.NUMBER && it.text == "two" })
        assertEquals("Medical", CriticalFieldCodec.decode(layers.messages.first().payload).first().text)
        val repair = CriticalFieldCodec.decode(layers.messages.last().payload)
        assertEquals(listOf("not", "two"), repair.map { it.text })
        assertTrue(layers.messages.all { SutraCodec.decode(SutraCodec.encode(it)).id == layers.id })
        val request = SutraSemanticEncoder.acknowledgement(
            layers.messages.last(), SutraMessageType.REPAIR_REQUEST, sourceId = 7
        )
        assertEquals(SutraMessageType.REPAIR_REQUEST, SutraCodec.decode(SutraCodec.encode(request)).type)
    }

    @Test
    fun detectsNativeScriptCriticalFields() {
        val english = CriticalFieldDetector.detect("Don't enter gate two")
        val hindi = CriticalFieldDetector.detect("मदद चाहिए, गेट चार पर मत जाना")
        val bengali = CriticalFieldDetector.detect("সাহায্য দরকার, গেট দুই এ না যান")
        val odia = CriticalFieldDetector.detect("ସାହାଯ୍ୟ ଦରକାର, ଗେଟ ଦୁଇ କୁ ନା ଯାଆନ୍ତୁ")

        assertTrue(english.any { it.kind == CriticalKind.NEGATION })
        assertTrue(hindi.any { it.kind == CriticalKind.EMERGENCY })
        assertTrue(hindi.any { it.kind == CriticalKind.NEGATION })
        assertTrue(bengali.any { it.kind == CriticalKind.NUMBER })
        assertTrue(odia.any { it.kind == CriticalKind.EMERGENCY })
    }
}
