package org.sih.offlinespeech

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.CRC32

enum class SutraMessageType(val wire: Int) {
    HELLO(1), TEXT_FINAL(2), ALERT_CORE(3), REPAIR_EVIDENCE(4),
    RECEIVED_ACK(5), PLAYBACK_STARTED(6), PLAYBACK_FINISHED(7), REPAIR_REQUEST(8);

    companion object {
        fun fromWire(value: Int) = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown SUTRA message type: $value")
    }
}

enum class SutraLanguage(val wire: Int, val pipelineName: String) {
    ENGLISH(0, "english"), HINDI(1, "hindi"), GUJARATI(2, "gujarati"),
    MARATHI(3, "marathi"), KANNADA(4, "kannada"), MALAYALAM(5, "malayalam"),
    TAMIL(6, "tamil"), TELUGU(7, "telugu"), ODIA(8, "odia"),
    BENGALI(9, "bengali"), UNDETERMINED(255, "undetermined");

    companion object {
        fun fromWire(value: Int) = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown SUTRA language: $value")

        fun fromPipeline(value: String) = entries.firstOrNull { it.pipelineName == value.lowercase() }
            ?: UNDETERMINED
    }
}

data class SutraId(val mostSignificantBits: Long, val leastSignificantBits: Long) {
    override fun toString(): String = UUID(mostSignificantBits, leastSignificantBits).toString()

    companion object {
        fun random(): SutraId = UUID.randomUUID().let { SutraId(it.mostSignificantBits, it.leastSignificantBits) }
    }
}

data class SutraMessage(
    val type: SutraMessageType,
    val language: SutraLanguage,
    val payload: ByteArray,
    val id: SutraId = SutraId.random(),
    val sourceId: Long = 0,
    val destinationId: Long = 0,
    val flags: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SutraMessage) return false
        return type == other.type &&
            language == other.language &&
            payload.contentEquals(other.payload) &&
            id == other.id &&
            sourceId == other.sourceId &&
            destinationId == other.destinationId &&
            flags == other.flags
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + destinationId.hashCode()
        result = 31 * result + flags
        return result
    }
}

object SutraFlags {
    const val FINAL = 1
    const val ALERT = 1 shl 1
    const val REQUIRES_ACK = 1 shl 2
    const val HAS_REPAIR = 1 shl 3
}

internal fun SutraMessage.shouldRelayLive(fresh: Boolean): Boolean =
    fresh && type == SutraMessageType.TEXT_FINAL && destinationId == 0L

object SutraCodec {
    private const val MAGIC = 0x5354
    private const val VERSION = 1
    private const val HEADER_BYTES = 44
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    fun encode(message: SutraMessage): ByteArray {
        require(message.flags in 0..0xffff) { "Flags exceed uint16" }
        require(message.payload.size <= MAX_PAYLOAD_BYTES) { "SUTRA payload is too large" }
        val output = ByteBuffer.allocate(HEADER_BYTES + message.payload.size + 4).order(ByteOrder.BIG_ENDIAN)
        output.putShort(MAGIC.toShort())
        output.put(VERSION.toByte())
        output.put(message.type.wire.toByte())
        output.putShort(message.flags.toShort())
        output.put(message.language.wire.toByte())
        output.put(HEADER_BYTES.toByte())
        output.putLong(message.id.mostSignificantBits)
        output.putLong(message.id.leastSignificantBits)
        output.putLong(message.sourceId)
        output.putLong(message.destinationId)
        output.putInt(message.payload.size)
        output.put(message.payload)
        val withoutCrc = output.position()
        val crc = CRC32().apply { update(output.array(), 0, withoutCrc) }.value
        output.putInt(crc.toInt())
        return output.array()
    }

    fun decode(bytes: ByteArray): SutraMessage {
        require(bytes.size >= HEADER_BYTES + 4) { "Truncated SUTRA envelope" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(input.short.toInt() and 0xffff == MAGIC) { "Invalid SUTRA magic" }
        require(input.get().toInt() and 0xff == VERSION) { "Unsupported SUTRA version" }
        val type = SutraMessageType.fromWire(input.get().toInt() and 0xff)
        val flags = input.short.toInt() and 0xffff
        val language = SutraLanguage.fromWire(input.get().toInt() and 0xff)
        val headerBytes = input.get().toInt() and 0xff
        require(headerBytes == HEADER_BYTES) { "Unsupported SUTRA header length: $headerBytes" }
        val id = SutraId(input.long, input.long)
        val sourceId = input.long
        val destinationId = input.long
        val payloadBytes = input.int
        require(payloadBytes in 0..MAX_PAYLOAD_BYTES) { "Invalid SUTRA payload length: $payloadBytes" }
        require(bytes.size == headerBytes + payloadBytes + 4) { "SUTRA envelope length mismatch" }
        val expectedCrc = ByteBuffer.wrap(bytes, bytes.size - 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val actualCrc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()
        require(actualCrc == expectedCrc) { "SUTRA CRC mismatch" }
        val payload = ByteArray(payloadBytes)
        input.get(payload)
        return SutraMessage(type, language, payload, id, sourceId, destinationId, flags)
    }

    fun utf8(payload: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(payload)).toString()
}

enum class CriticalKind(val wire: Int) {
    NEGATION(1), NUMBER(2), EMERGENCY(3), LOCATION_HINT(4);

    companion object {
        fun fromWire(value: Int) = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown critical-field kind: $value")
    }
}

data class CriticalField(val text: String, val kind: CriticalKind, val confidence: Double)

object CriticalFieldCodec {
    fun encode(fields: List<CriticalField>): ByteArray {
        require(fields.size <= 255)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(fields.size)
            fields.forEach { field ->
                val text = field.text.toByteArray(Charsets.UTF_8)
                require(text.size <= 255) { "Critical field is too long" }
                output.writeByte(field.kind.wire)
                output.writeByte((field.confidence.coerceIn(0.0, 1.0) * 255).toInt())
                output.writeByte(text.size)
                output.write(text)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<CriticalField> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(input.hasRemaining()) { "Missing critical-field count" }
        val count = input.get().toInt() and 0xff
        return List(count) {
            require(input.remaining() >= 3) { "Truncated critical field" }
            val kind = CriticalKind.fromWire(input.get().toInt() and 0xff)
            val confidence = (input.get().toInt() and 0xff) / 255.0
            val length = input.get().toInt() and 0xff
            require(input.remaining() >= length) { "Truncated critical-field text" }
            val text = ByteArray(length).also(input::get)
            CriticalField(SutraCodec.utf8(text), kind, confidence)
        }.also { require(!input.hasRemaining()) { "Unexpected critical-field bytes" } }
    }
}

object CriticalFieldDetector {
    private val token = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}\\p{N}]+)*")
    private val negations = words(
        "no not never don't dont", "नहीं नही मत ना", "નથી નહિ ના", "नाही नको",
        "ಇಲ್ಲ ಬೇಡ ಅಲ್ಲ", "ഇല്ല വേണ്ട അല്ല", "இல்லை வேண்டாம் அல்ல", "లేదు వద్దు కాదు",
        "ନାହିଁ ନା ନୁହେଁ", "না নয় নেই"
    )
    private val numberWords = words(
        "zero one two three four five six seven eight nine ten hundred thousand",
        "शून्य एक दो तीन चार पाँच पांच छह सात आठ नौ दस सौ हजार",
        "શૂન્ય એક બે ત્રણ ચાર પાંચ છ સાત આઠ નવ દસ",
        "शून्य एक दोन तीन चार पाच सहा सात आठ नऊ दहा",
        "ಸೊನ್ನೆ ಒಂದು ಎರಡು ಮೂರು ನಾಲ್ಕು ಐದು ಆರು ಏಳು ಎಂಟು ಒಂಬತ್ತು ಹತ್ತು",
        "പൂജ്യം ഒന്ന് രണ്ട് മൂന്ന് നാല് അഞ്ച് ആറ് ഏഴ് എട്ട് ഒമ്പത് പത്ത്",
        "பூஜ்ஜியம் ஒன்று இரண்டு மூன்று நான்கு ஐந்து ஆறு ஏழு எட்டு ஒன்பது பத்து",
        "సున్నా ఒకటి రెండు మూడు నాలుగు ఐదు ఆరు ఏడు ఎనిమిది తొమ్మిది పది",
        "ଶୂନ ଏକ ଦୁଇ ତିନି ଚାରି ପାଞ୍ଚ ଛଅ ସାତ ଆଠ ନଅ ଦଶ",
        "শূন্য এক দুই তিন চার পাঁচ ছয় সাত আট নয় দশ"
    )
    private val emergencies = words(
        "help emergency fire ambulance police doctor hospital trapped evacuate danger medicine blood injury bleeding accident attack casualty oxygen earthquake flood collapse wound hurt",
        "मदद आपातकाल आग एम्बुलेंस पुलिस डॉक्टर अस्पताल फँसा खतरा दवा खून चोट घायल दुर्घटना हमला बचाव",
        "મદદ આગ એમ્બ્યુલન્સ પોલીસ ડોક્ટર હોસ્પિટલ જોખમ ઇજા ઘાયલ અકસ્માત બચાવ",
        "मदत आग रुग्णवाहिका पोलीस डॉक्टर रुग्णालय धोका जखमी अपघात जखम",
        "ಸಹಾಯ ಬೆಂಕಿ ಆಂಬ್ಯುಲೆನ್ಸ್ ಪೊಲೀಸ್ ವೈದ್ಯ ಆಸ್ಪತ್ರೆ ಅಪಾಯ ಗಾಯ ಅಪಘಾತ",
        "സഹായം തീ ആംബുലൻസ് പോലീസ് ഡോക്ടർ ആശുപത്രി അപകടം പരുക്ക് രക്തസ്രാവം",
        "உதவி தீ ஆம்புலன்ஸ் காவல் மருத்துவர் மருத்துவமனை ஆபத்து காயம் விபத்து",
        "సహాయం మంట అంబులెన్స్ పోలీసు వైద్యుడు ఆసుపత్రి ప్రమాదం గాయం రక్తస్రావం",
        "ସାହାଯ୍ୟ ନିଆଁ ଆମ୍ବୁଲାନ୍ସ ପୋଲିସ ଡାକ୍ତର ଡାକ୍ତରଖାନା ବିପଦ ଆଘାତ ଦୁର୍ଘଟଣା",
        "সাহায্য আগুন অ্যাম্বুলেন্স পুলিশ ডাক্তার হাসপাতাল বিপদ আঘাত দুর্ঘটনা"
    )
    private val locationHints = words(
        "gate room floor building road street bridge village station coordinates location",
        "गेट कमरा मंजिल इमारत सड़क पुल गाँव स्टेशन स्थान",
        "ગેટ રૂમ માળ મકાન રસ્તો પુલ ગામ સ્ટેશન સ્થળ",
        "गेट खोली मजला इमारत रस्ता पूल गाव स्थानक ठिकाण",
        "ಗೇಟ್ ಕೊಠಡಿ ಮಹಡಿ ಕಟ್ಟಡ ರಸ್ತೆ ಸೇತುವೆ ಗ್ರಾಮ ನಿಲ್ದಾಣ ಸ್ಥಳ",
        "ഗേറ്റ് മുറി നില കെട്ടിടം റോഡ് പാലം ഗ്രാമം സ്റ്റേഷൻ സ്ഥലം",
        "வாசல் அறை மாடி கட்டிடம் சாலை பாலம் கிராமம் நிலையம் இடம்",
        "గేటు గది అంతస్తు భవనం రోడ్డు వంతెన గ్రామం స్టేషన్ స్థలం",
        "ଗେଟ କୋଠରୀ ମହଲା କୋଠା ରାସ୍ତା ସେତୁ ଗାଁ ଷ୍ଟେସନ ସ୍ଥାନ",
        "গেট ঘর তলা ভবন রাস্তা সেতু গ্রাম স্টেশন স্থান"
    )

    fun containsEmergencyKeyword(text: String): Boolean =
        detect(text).any { it.kind == CriticalKind.EMERGENCY }

    fun detect(text: String, wordConfidences: List<Double> = emptyList()): List<CriticalField> =
        token.findAll(text).mapIndexedNotNull { index, match ->
            val normalized = match.value.lowercase()
            val kind = when {
                normalized.any(Char::isDigit) || normalized in numberWords -> CriticalKind.NUMBER
                normalized in negations -> CriticalKind.NEGATION
                normalized in emergencies -> CriticalKind.EMERGENCY
                normalized in locationHints -> CriticalKind.LOCATION_HINT
                else -> null
            }
            kind?.let { CriticalField(match.value, it, wordConfidences.getOrNull(index) ?: 0.5) }
        }.toList()

    private fun words(vararg groups: String) = groups.flatMap { it.split(' ') }.filter(String::isNotBlank).toSet()
}

data class SutraLayerSet(val id: SutraId, val fields: List<CriticalField>, val messages: List<SutraMessage>)

object SutraSemanticEncoder {
    const val REPAIR_THRESHOLD = 0.72

    fun build(
        language: String,
        text: String,
        confidence: Double,
        alert: Boolean,
        alertCategory: String? = null,
        sourceId: Long,
        destinationId: Long = 0,
        id: SutraId = SutraId.random(),
        wordConfidences: List<Double> = emptyList(),
        coordinates: Pair<Double, Double>? = null,
    ): SutraLayerSet {
        require(text.isNotBlank()) { "Cannot transmit an empty transcript" }
        val languageCode = SutraLanguage.fromPipeline(language)
        val fallbackConfidences = if (wordConfidences.isEmpty()) {
            List(Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}\\p{N}]+)*").findAll(text).count()) { confidence }
        } else wordConfidences
        val detectedFields = CriticalFieldDetector.detect(text, fallbackConfidences)
        val withAlertField = if (alert && !alertCategory.isNullOrBlank()) {
            listOf(CriticalField(alertCategory, CriticalKind.EMERGENCY, 1.0)) + detectedFields
        } else detectedFields
        val fields = if (coordinates != null) {
            val geoField = CriticalField("geo:%.5f,%.5f".format(java.util.Locale.US, coordinates.first, coordinates.second), CriticalKind.LOCATION_HINT, 1.0)
            listOf(geoField) + withAlertField
        } else withAlertField
        val uncertain = fields.filter { it.confidence < REPAIR_THRESHOLD }
        val commonFlags = SutraFlags.FINAL or SutraFlags.REQUIRES_ACK or
            (if (alert) SutraFlags.ALERT else 0) or (if (uncertain.isNotEmpty()) SutraFlags.HAS_REPAIR else 0)
        val messages = ArrayList<SutraMessage>(3)
        if (alert) messages += SutraMessage(
            SutraMessageType.ALERT_CORE, languageCode, CriticalFieldCodec.encode(fields), id,
            sourceId, destinationId, commonFlags
        )
        messages += SutraMessage(
            SutraMessageType.TEXT_FINAL, languageCode, text.toByteArray(Charsets.UTF_8), id,
            sourceId, destinationId, commonFlags
        )
        if (uncertain.isNotEmpty()) messages += SutraMessage(
            SutraMessageType.REPAIR_EVIDENCE, languageCode, CriticalFieldCodec.encode(uncertain), id,
            sourceId, destinationId, commonFlags
        )
        return SutraLayerSet(id, fields, messages)
    }

    fun acknowledgement(original: SutraMessage, type: SutraMessageType, sourceId: Long): SutraMessage {
        require(type in setOf(SutraMessageType.RECEIVED_ACK, SutraMessageType.PLAYBACK_STARTED,
            SutraMessageType.PLAYBACK_FINISHED, SutraMessageType.REPAIR_REQUEST))
        return SutraMessage(type, original.language, ByteArray(0), original.id,
            sourceId, original.sourceId, SutraFlags.FINAL)
    }
}
