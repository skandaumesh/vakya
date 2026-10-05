package com.replybot

import com.replybot.offline.QuickReplies
import com.replybot.offline.QuickReplies.Register
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickRepliesTest {

    @Test
    fun intentsInEnglishKanglishAndHinglish() {
        val cases = mapOf(
            "gm maga" to "good_morning",
            "Good morninggg ☀️" to "good_morning",
            "gn" to "good_night",
            "maga yelli idiya?" to "where",
            "where r u bro" to "where",
            "saturday party ide barthiya?" to "invitation",
            "bhai kal movie chalein?" to "invitation",
            "party tonight?" to "invitation",
            "are you coming to the match" to "invitation",
            "thanks a lot da" to "thanks",
            "sorry I forgot to call" to "sorry",
            "call me when free" to "call_me",
            "can you send the notes pls" to "request",
            "I got the job!!" to "congrats",
            "happy birthday maga 🎂" to "wished_me",
            "😂😂😂" to "laugh",
            "[sticker]" to "laugh",
            "[photo]" to "photo",
            "hegiddiya maga" to "how_are_you",
            "urgent, website is down" to "urgent",
            "ok" to "ok",
            "is the class today?" to "question",
        )
        cases.forEach { (msg, intent) -> assertEquals(msg, intent, QuickReplies.intentOf(msg)) }
        assertNull(QuickReplies.intentOf("I watched the new movie yesterday it was nice"))
    }

    @Test
    fun registerFollowsLanguageAndRelationship() {
        assertEquals(Register.KANGLISH, QuickReplies.registerFor("maga yelli idiya?", emptyList(), formal = false))
        assertEquals(Register.KANGLISH, QuickReplies.registerFor("where are you?", listOf("sari da bartini"), formal = false))
        assertEquals(Register.ENGLISH, QuickReplies.registerFor("where are you?", listOf("on my way"), formal = false))
        assertEquals(Register.POLITE, QuickReplies.registerFor("maga yelli idiya?", emptyList(), formal = true))
    }

    @Test
    fun alwaysThreeDifferentActions() {
        listOf("saturday party ide barthiya?", "random words here", "😂").forEach { msg ->
            Register.values().forEach { r ->
                val s = QuickReplies.suggest(msg, r)
                assertEquals(3, s.size)
                assertEquals(3, s.map { it.label }.toSet().size)
            }
        }
        assertEquals(
            listOf("bartini maga 🔥", "nodona, confirm madtini", "sorry maga, aagalla this time"),
            QuickReplies.suggest("party ide barthiya", Register.KANGLISH).map { it.text },
        )
        assertEquals("Sure, I'll be there.", QuickReplies.suggest("party ide barthiya", Register.POLITE).first().text)
    }
}
