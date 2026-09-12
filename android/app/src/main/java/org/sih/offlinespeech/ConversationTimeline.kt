package org.sih.offlinespeech

internal enum class MessageProgress { QUEUED, SENDING, RECEIVED, PLAYING, HEARD }

internal data class ConversationEntry(
    val id: String, val text: String, val language: String, val outgoing: Boolean,
    val alert: Boolean, val progress: MessageProgress,
    val heardBy: Set<Long> = emptySet(), val playbackFailed: Boolean = false,
) {
    val key get() = "${if (outgoing) "out" else "in"}:$id"
    val status: String get() = when {
        playbackFailed -> "Playback unavailable · message text is safe"
        progress == MessageProgress.HEARD && outgoing ->
            "Heard · playback confirmed on ${heardBy.size} ${if (heardBy.size == 1) "phone" else "phones"}"
        progress == MessageProgress.HEARD -> "Played on this phone"
        progress == MessageProgress.PLAYING -> if (outgoing) "Playing on a nearby phone" else "Playing on this phone"
        progress == MessageProgress.RECEIVED -> if (outgoing) "Received by a nearby phone" else "Received · waiting for playback"
        progress == MessageProgress.SENDING -> "Sending · waiting for a receipt"
        else -> "Saved · will send when connected"
    }
}

/** Session UI history; the existing durable outbox still owns message delivery. */
internal class ConversationTimeline {
    private val entries = LinkedHashMap<String, ConversationEntry>()
    @Synchronized fun snapshot(): List<ConversationEntry> = entries.values.toList()
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun add(entry: ConversationEntry) {
        entries.putIfAbsent(entry.key, entry)
        // Keep the foreground session bounded; this is not a persistent chat archive.
        while (entries.size > 32) entries.remove(entries.keys.first())
    }
    @Synchronized fun advance(id: String, outgoing: Boolean, progress: MessageProgress, peer: Long = 0): Boolean {
        val key = "${if (outgoing) "out" else "in"}:$id"
        val old = entries[key] ?: return false
        val updated = old.copy(
            progress = maxOf(old.progress, progress),
            heardBy = if (outgoing && progress == MessageProgress.HEARD) old.heardBy + peer else old.heardBy,
            playbackFailed = if (progress >= MessageProgress.PLAYING) false else old.playbackFailed,
        )
        entries[key] = updated
        return updated != old
    }
    @Synchronized fun playbackFailed(id: String) {
        val key = "in:$id"
        entries[key]?.let { entries[key] = it.copy(playbackFailed = true) }
    }
}

internal fun emphasisWords(text: String): List<String> =
    Regex("[\\p{L}\\p{M}]+").findAll(text).map { it.value }.distinct().toList()
