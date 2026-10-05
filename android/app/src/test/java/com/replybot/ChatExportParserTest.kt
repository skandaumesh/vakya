package com.replybot

import com.replybot.importer.ChatExportParser
import com.replybot.importer.ChatExportParser.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same fixtures as backend/tests/test_chat_export.py, so the two parsers stay in sync. */
class ChatExportParserTest {

    private val android = """
        12/31/23, 9:15${' '}PM - Messages and calls are end-to-end encrypted. No one outside of this chat can read them.
        12/31/23, 9:15${' '}PM - Rahul: Hi, can you send the files?
        12/31/23, 9:16${' '}PM - Skanda: ok da will do
        12/31/23, 9:17${' '}PM - Rahul: Also the logo
        in PNG please
        12/31/23, 9:18${' '}PM - Skanda: <Media omitted>
        12/31/23, 9:19${' '}PM - Skanda: sent 👍 <This message was edited>
        12/31/23, 9:20${' '}PM - Rahul: This message was deleted
    """.trimIndent()

    @Test
    fun androidFormat() {
        assertEquals(
            listOf(
                Message("Rahul", "Hi, can you send the files?"),
                Message("Skanda", "ok da will do"),
                Message("Rahul", "Also the logo\nin PNG please"),
                Message("Skanda", "sent 👍"),
            ),
            ChatExportParser.parse(android),
        )
    }

    @Test
    fun twentyFourHourFormatAndTildeSender() {
        val raw = "31/12/2023, 21:15 - ~ Kiran: standup at 10:30\n31/12/2023, 21:16 - Skanda: sari"
        assertEquals(
            listOf(Message("Kiran", "standup at 10:30"), Message("Skanda", "sari")),
            ChatExportParser.parse(raw),
        )
    }

    @Test
    fun iosFormatDropsMedia() {
        val raw = "‎[31/12/23, 9:15:23 PM] Rahul: Hi there\n" +
            "[31/12/23, 9:16:01 PM] Skanda: haha nice 😂\n" +
            "‎[31/12/23, 9:16:30 PM] Skanda: ‎image omitted"
        assertEquals(
            listOf(Message("Rahul", "Hi there"), Message("Skanda", "haha nice 😂")),
            ChatExportParser.parse(raw),
        )
    }

    @Test
    fun guessMeFromFilename() {
        val messages = ChatExportParser.parse(android)
        val contact = ChatExportParser.contactFromFilename("WhatsApp Chat with Rahul.txt")
        assertEquals("Rahul", contact)
        assertEquals("Skanda", ChatExportParser.guessMe(messages, contact))
        assertNull(ChatExportParser.guessMe(messages, null))
    }

    @Test
    fun filenameWithCopySuffix() {
        assertEquals("Priya Bakery", ChatExportParser.contactFromFilename("WhatsApp Chat with Priya Bakery (2).txt"))
        assertNull(ChatExportParser.contactFromFilename("notes.txt"))
    }
}
