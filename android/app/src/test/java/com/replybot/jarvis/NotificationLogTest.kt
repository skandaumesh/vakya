package com.replybot.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLogTest {

    @Test
    fun codesAndPasswordsAreNeverKept() {
        listOf(
            "483920 is your OTP for HDFC Bank. Do not share it with anyone.",
            "Your WhatsApp code: 123-456",
            "Use 7731 as your login code",
            "Your Instagram code is 552901",
            "Your verification code is 9921",
            "Your PIN 4821 has been changed",
            "Never share your password",
        ).forEach { assertTrue(it, NotificationLog.isSecret(it)) }
    }

    @Test
    fun ordinaryMessagesAreKept() {
        listOf(
            "bro movie at 6?",
            "send me the location pin",
            "Amma: call me when you're free",
            "Rs 2,000 credited to your account",
            "The code review is done, ship it",
            "Meeting moved to 4:30",
        ).forEach { assertFalse(it, NotificationLog.isSecret(it)) }
    }

    @Test
    fun whenIsSaidLikeAPerson() {
        val now = 1_760_000_000_000L
        assertEquals("just now", NotificationLog.whenSaid(now - 20_000, now))
        assertEquals("5 min ago", NotificationLog.whenSaid(now - 5 * 60_000, now))
    }
}
