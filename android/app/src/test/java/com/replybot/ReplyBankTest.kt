package com.replybot

import com.replybot.bank.ReplyBank
import com.replybot.bank.ReplyBank.Entry
import com.replybot.bank.TextSim
import com.replybot.importer.ChatExportParser
import com.replybot.importer.ChatExportParser.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyBankTest {

    private fun bank(): ReplyBank {
        val b = ReplyBank(file = null)  // in memory for tests
        b.add(
            listOf(
                Entry("maga yelli idiya?", "on the way maga 🏃", "wa|Vinay"),
                Entry("where are you", "just leaving home", "wa|Vinay"),
                Entry("good morning", "gm da ☀️", "wa|Vinay"),
                Entry("thanks a lot", "anytime 🙌", "wa|Vinay"),
                Entry("call me when free", "sari, will call in 10", "wa|Vinay"),
                Entry("oota aytha?", "haa amma aytu", "wa|Amma"),
                Entry("can you send the files by tonight?", "yes will send tonight 👍", "wa|Rahul"),
                Entry("saturday party ide barthiya", "bartini maga 🔥", "wa|Vinay"),
                Entry("😂😂", "😂", "wa|Vinay"),
                Entry("ok", "👍", "wa|Vinay"),
            ) + (0 until 40).map { Entry("random filler message number $it about stuff", "hmm", "wa|Other") },
        )
        return b
    }

    @Test
    fun shortcutsAndStretchedSpellingsMatch() {
        assertEquals(TextSim.tokens("where r u"), TextSim.tokens("Where are you??"))
        assertEquals(TextSim.tokens("gm"), TextSim.tokens("Good morninggg"))
        assertEquals(TextSim.tokens("okk"), TextSim.tokens("k"))
        assertEquals(listOf("e:😂", "e:😂"), TextSim.tokens("😂😂"))
    }

    @Test
    fun closeMessagesGetTheUsersOwnReply() {
        val b = bank()
        assertEquals("on the way maga 🏃", b.search("maga elli idiya", "wa|Vinay").first().reply)
        assertEquals("gm da ☀️", b.search("gm", null).first().reply)
        assertEquals("👍", b.search("okk", null).first().reply)
        val strong = b.search("where r u", null).first()
        assertEquals("just leaving home", strong.reply)
        assertTrue(strong.score >= ReplyBank.STRONG_SCORE)
    }

    @Test
    fun unrelatedMessagesGetNothing() {
        val b = bank()
        listOf("what is the capital of france", "i failed my exam", "send the invoice", "are you coming to the party")
            .forEach { assertTrue(it, b.search(it, null).isEmpty()) }
    }

    @Test
    fun clientsOnlyGetRepliesFromTheirOwnChat() {
        val b = bank()
        assertTrue(b.search("good morning", "wa|Rahul", onlyThisChat = true).isEmpty())
        assertEquals("yes will send tonight 👍", b.search("can you send the files tonight?", "wa|Rahul", onlyThisChat = true).first().reply)
    }

    @Test
    fun repliesWithSlursAreNeverSuggested() {
        val b = ReplyBank(file = null)
        b.add(listOf(Entry("wassup", "yo nigga 😂", "wa|X"), Entry("wassup bro", "all good blud 💀", "wa|X")))
        assertEquals(listOf("all good blud 💀"), b.search("wassup", null).map { it.reply })
    }

    @Test
    fun duplicatesAreIgnoredAndClearWorks() {
        val b = bank()
        val before = b.size()
        b.add(listOf(Entry("ok", "👍", "wa|Vinay")))
        assertEquals(before, b.size())
        b.clear()
        assertEquals(0, b.size())
        assertTrue(b.search("ok", null).isEmpty())
    }

    @Test
    fun longMessagesNeedACloserMatch() {
        assertEquals(0.8, ReplyBank.strongScoreFor("where are you"), 0.0)
        assertEquals(0.9, ReplyBank.strongScoreFor("so I looked at the new design and I like it but a few things need to change"), 0.0)
    }

    @Test
    fun replyPairsFromAChatExport() {
        val messages = listOf(
            Message("Vinay", "maga"),
            Message("Vinay", "saturday party ide barthiya"),
            Message("Skanda", "bartini maga 🔥"),
            Message("Skanda", "what time?"),
            Message("Vinay", "8 ge"),
            Message("Skanda", "sari"),
        )
        assertEquals(
            listOf("maga saturday party ide barthiya" to "bartini maga 🔥", "8 ge" to "sari"),
            ChatExportParser.replyPairs(messages, me = "Skanda"),
        )
    }
}
