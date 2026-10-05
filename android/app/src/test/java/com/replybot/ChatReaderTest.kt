package com.replybot

import com.replybot.service.ChatReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReaderTest {

    @Test
    fun timestampsDatesAndStatusLinesAreNoise() {
        listOf("9:15 PM", "21:15", "9:15 pm", "Today", "Yesterday", "12 March 2025", "March 12, 2025",
            "12/03/2025", "online", "typing…", "last seen today at 9:00 PM", "3 unread messages", "  ")
            .forEach { assertTrue(it, ChatReader.isNoise(it)) }
    }

    @Test
    fun realMessagesAreNotNoise() {
        listOf("ok da", "Can you send the files by 9:15?", "see you at 10", "12 bananas", "room 12", "👍")
            .forEach { assertFalse(it, ChatReader.isNoise(it)) }
    }

    @Test
    fun placeholdersAreNotDrafts() {
        listOf("Message", " message ", "Message…", "Type a message", "RCS message")
            .forEach { assertTrue(it, ChatReader.isPlaceholder(it)) }
        listOf("Message me later", "yes will send", "ok")
            .forEach { assertFalse(it, ChatReader.isPlaceholder(it)) }
    }

    @Test
    fun photosAndStickersAreFoundButIconsAreNot() {
        val w = 1080
        // Described as media: always counts, even small (voice note play button).
        assertTrue(ChatReader.isMediaNode("android.widget.ImageButton", "Voice message, 0:12", null, 100, 100, w))
        // Big images in the chat: photos, stickers.
        assertTrue(ChatReader.isMediaNode("android.widget.ImageView", null, "com.whatsapp:id/image", 650, 500, w))
        assertTrue(ChatReader.isMediaNode("android.view.View", null, "com.whatsapp:id/sticker_image", 380, 380, w))
        // Icons and avatars are too small; big containers that aren't images don't count.
        assertFalse(ChatReader.isMediaNode("android.widget.ImageView", "Delivered", "com.whatsapp:id/status", 55, 33, w))
        assertFalse(ChatReader.isMediaNode("android.widget.ImageView", null, "com.whatsapp:id/avatar", 120, 120, w))
        assertFalse(ChatReader.isMediaNode("android.widget.FrameLayout", null, "com.whatsapp:id/main_layout", 900, 300, w))
    }

    @Test
    fun mediaKindFromDescriptionOrId() {
        assertEquals("sticker", ChatReader.mediaKind(null, "com.whatsapp:id/sticker_image"))
        assertEquals("voice", ChatReader.mediaKind("Voice message, 0:12", null))
        assertEquals("gif", ChatReader.mediaKind("GIF", null))
        assertEquals("photo", ChatReader.mediaKind(null, "com.whatsapp:id/image"))
    }

    @Test
    fun bubbleSideDecidesSender() {
        val width = 1080
        assertTrue(ChatReader.isFromMe(left = 800, right = 1040, screenWidth = width))   // short outgoing
        assertFalse(ChatReader.isFromMe(left = 40, right = 300, screenWidth = width))    // short incoming
        assertTrue(ChatReader.isFromMe(left = 190, right = 1040, screenWidth = width))   // long outgoing
        assertFalse(ChatReader.isFromMe(left = 40, right = 890, screenWidth = width))    // long incoming
    }
}
