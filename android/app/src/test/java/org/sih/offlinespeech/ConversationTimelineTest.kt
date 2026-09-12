package org.sih.offlinespeech

import org.junit.Assert.*
import org.junit.Test

class ConversationTimelineTest {
    private fun message(id: String, outgoing: Boolean = true) = ConversationEntry(
        id, "Please bring water", "english", outgoing, false, MessageProgress.QUEUED)

    @Test fun delayedDuplicateAndMultiPeerReceiptsStayOnTheirOwnMessage() {
        val timeline = ConversationTimeline()
        timeline.add(message("first")); timeline.add(message("second"))
        assertFalse(timeline.advance("unknown", true, MessageProgress.HEARD, 9))
        timeline.advance("first", true, MessageProgress.HEARD, 10)
        assertFalse(timeline.advance("first", true, MessageProgress.HEARD, 10))
        timeline.advance("first", true, MessageProgress.RECEIVED, 11)
        timeline.advance("first", true, MessageProgress.SENDING)
        assertEquals(MessageProgress.HEARD, timeline.snapshot()[0].progress)
        assertEquals(setOf(10L), timeline.snapshot()[0].heardBy)
        assertEquals(MessageProgress.QUEUED, timeline.snapshot()[1].progress)
        timeline.advance("first", true, MessageProgress.HEARD, 11)
        assertEquals(setOf(10L, 11L), timeline.snapshot()[0].heardBy)
    }

    @Test fun incomingFailureAndDuplicateCannotEraseProgressOrBecomeAnOutgoingReceipt() {
        val timeline = ConversationTimeline()
        timeline.add(message("same", false))
        timeline.advance("same", false, MessageProgress.PLAYING)
        timeline.playbackFailed("same")
        timeline.add(message("same", false))
        assertTrue(timeline.snapshot().single().playbackFailed)
        assertFalse(timeline.advance("same", true, MessageProgress.HEARD, 12))
        timeline.advance("same", false, MessageProgress.HEARD)
        assertFalse(timeline.snapshot().single().playbackFailed)
        assertEquals("Played on this phone", timeline.snapshot().single().status)
    }

    @Test fun historyIsBoundedAndHindiWordsKeepCombiningMarks() {
        val timeline = ConversationTimeline()
        repeat(40) { timeline.add(message(it.toString())) }
        assertEquals(32, timeline.snapshot().size)
        assertEquals("8", timeline.snapshot().first().id)
        assertEquals(listOf("कृपया", "पानी", "जल्दी", "भेजिए"), emphasisWords("कृपया पानी जल्दी भेजिए। पानी"))
    }
}
