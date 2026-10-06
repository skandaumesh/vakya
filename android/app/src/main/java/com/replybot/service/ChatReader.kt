package com.replybot.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.replybot.data.ChatMsg

/** Reads the open chat (title + visible messages) from the accessibility tree. */
object ChatReader {

    /** [rects] holds each message's position on screen, for cropping photos and stickers. */
    data class Snapshot(val title: String?, val messages: List<ChatMsg>, val rects: List<Rect>)

    private const val MAX_NODES = 800
    private const val MAX_MESSAGES = 15

    private val TIME = Regex("""^\d{1,2}[:.]\d{2}(\s?[APap]\.?[Mm]\.?)?$""")
    private const val MONTH = """(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\p{L}*"""
    private val DATE = Regex(
        """^(\d{1,2} $MONTH( \d{4})?|$MONTH \d{1,2}(, \d{4})?|\d{1,2}/\d{1,2}/\d{2,4})$""",
        RegexOption.IGNORE_CASE,
    )
    private val NOISE = setOf(
        "today", "yesterday", "this message was deleted", "you deleted this message",
        "typing…", "typing...", "online", "edited",
    )

    /** Timestamps, date separators and status lines that aren't messages. */
    fun isNoise(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val low = t.lowercase()
        return low in NOISE || TIME.matches(t) || DATE.matches(t) ||
            low.startsWith("messages and calls are end-to-end encrypted") ||
            low.startsWith("last seen") || low.endsWith(" unread messages") || low.endsWith(" unread message")
    }

    /** Outgoing bubbles hug the right edge of the screen, incoming ones the left. */
    fun isFromMe(left: Int, right: Int, screenWidth: Int): Boolean = left > screenWidth - right

    /** The message box we attach to. In WhatsApp only the chat's own box counts, not search fields. */
    fun isChatInput(node: AccessibilityNodeInfo, pkg: String): Boolean {
        if (!node.isEditable) return false
        if (pkg.startsWith("com.whatsapp")) return node.viewIdResourceName == "$pkg:id/entry"
        return true
    }

    fun title(root: AccessibilityNodeInfo, pkg: String, screen: Rect): String? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/conversation_contact_name")
            .firstNotNullOfOrNull { it.text?.toString()?.takeIf(String::isNotBlank) }
            ?.let { return it }
        // Other apps: the first real text in the toolbar area.
        val toolbarBottom = screen.top + screen.height() * 0.15
        return collectTexts(root)
            .filter { (r, t) -> r.bottom <= toolbarBottom && !isNoise(t) && t.length <= 60 }
            .minByOrNull { it.first.top }
            ?.second
    }

    fun read(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo, pkg: String, screen: Rect): Snapshot {
        val inputRect = bounds(input)
        val title = title(root, pkg, screen)
        val chatTop = (screen.top + screen.height() * 0.12).toInt()

        val known = root.findAccessibilityNodeInfosByViewId("$pkg:id/message_text")
        val texts = if (known.isNotEmpty()) {
            known.mapNotNull { n -> n.text?.toString()?.let { bounds(n) to it } }
        } else {
            collectTexts(root).filter { (r, _) -> r.top > chatTop }
        }
        val textItems = texts
            .filter { (r, t) -> r.bottom <= inputRect.top && !isNoise(t) && t != title }
            .map { (r, t) -> r to ChatMsg(isFromMe(r.left, r.right, screen.width()), t.trim()) }
        val mediaItems = collectMedia(root, chatTop, inputRect.top, screen.width())
            .map { (r, kind) -> r to ChatMsg(isFromMe(r.left, r.right, screen.width()), "", media = kind) }

        val items = (textItems + mediaItems).sortedBy { it.first.top }.takeLast(MAX_MESSAGES)
        return Snapshot(title, items.map { it.second }, items.map { it.first })
    }

    private val MEDIA_WORDS = Regex("""\b(photo|image|picture|sticker|gif|video|voice message|audio|document)\b""", RegexOption.IGNORE_CASE)
    private val MEDIA_ID = Regex("(image|sticker|thumb|photo|gif|video|media)", RegexOption.IGNORE_CASE)

    fun mediaKind(desc: String?, viewId: String?): String {
        val s = "${desc.orEmpty()} ${viewId.orEmpty()}".lowercase()
        return when {
            "sticker" in s -> "sticker"
            "gif" in s -> "gif"
            "video" in s -> "video"
            "voice" in s || "audio" in s -> "voice"
            "document" in s || "pdf" in s -> "document"
            else -> "photo"
        }
    }

    /**
     * A photo, sticker, GIF... in the chat: something described as media, or an image
     * big enough to be content rather than an icon (ticks, avatars, buttons are small).
     */
    fun isMediaNode(className: String?, desc: String?, viewId: String?, width: Int, height: Int, screenWidth: Int): Boolean {
        if (desc != null && MEDIA_WORDS.containsMatchIn(desc)) return true
        val big = width >= screenWidth * 0.2 && height >= screenWidth * 0.12
        val imageLike = className?.endsWith("ImageView") == true ||
            (viewId != null && MEDIA_ID.containsMatchIn(viewId.substringAfter('/')))
        return big && imageLike
    }

    private fun collectMedia(root: AccessibilityNodeInfo, top: Int, bottom: Int, screenWidth: Int): List<Pair<Rect, String>> {
        val out = mutableListOf<Pair<Rect, String>>()
        val stack = arrayListOf(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val node = stack.removeAt(stack.lastIndex)
            seen++
            if (!node.isVisibleToUser) continue
            val r = bounds(node)
            val desc = node.contentDescription?.toString()
            val inChat = r.top >= top && r.bottom <= bottom && !node.isEditable && node.text.isNullOrBlank()
            if (inChat && isMediaNode(node.className?.toString(), desc, node.viewIdResourceName, r.width(), r.height(), screenWidth)) {
                // Nested views of one photo (frame, image, overlay) count once.
                if (out.none { (o, _) -> Rect(o).let { it.intersect(r) && it.width() * it.height() * 2 > r.width() * r.height() } }) {
                    out += r to mediaKind(desc, node.viewIdResourceName)
                }
                continue
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    private val PLACEHOLDERS = setOf(
        "message", "type a message", "write a message", "send a message", "text message",
        "rcs message", "sms message", "chat message",
    )

    /** Grey placeholder text that some apps report as the box's content. */
    fun isPlaceholder(text: String): Boolean = text.trim().lowercase().trimEnd('.', '…') in PLACEHOLDERS

    /** What the user has already typed, or "" when the box only shows its placeholder. */
    fun draftText(input: AccessibilityNodeInfo): String {
        if (input.isShowingHintText) return ""
        val text = input.text?.toString() ?: return ""
        if (text == input.hintText?.toString() || isPlaceholder(text)) return ""
        // WhatsApp reports its placeholder (in any language) as text, but the cursor
        // then sits at 0; after real typing it is at the end.
        if (input.textSelectionStart == 0 && input.textSelectionEnd == 0) return ""
        return text
    }

    fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private val SCROLL_DOWN = Regex("scroll to (the )?bottom|jump to (the )?(bottom|latest)", RegexOption.IGNORE_CASE)

    /**
     * WhatsApp opens a chat at the first unread message, so the newest ones can be
     * below the screen. Taps its "scroll to bottom" button if it's showing; returns
     * true if it did (read the chat again after the list settles).
     */
    fun scrollToNewest(root: AccessibilityNodeInfo, pkg: String): Boolean {
        val button = root.findAccessibilityNodeInfosByViewId("$pkg:id/scroll_bottom").firstOrNull { it.isVisibleToUser }
            ?: findNode(root) { it.isVisibleToUser && it.contentDescription?.let(SCROLL_DOWN::containsMatchIn) == true }
            ?: return false
        var node: AccessibilityNodeInfo? = button
        while (node != null && !node.isClickable) node = node.parent
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findNode(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val stack = arrayListOf(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val node = stack.removeAt(stack.lastIndex)
            seen++
            if (match(node)) return node
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun collectTexts(root: AccessibilityNodeInfo): List<Pair<Rect, String>> {
        val out = mutableListOf<Pair<Rect, String>>()
        val stack = arrayListOf(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val node = stack.removeAt(stack.lastIndex)
            seen++
            if (!node.isVisibleToUser) continue
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && !node.isEditable && node.className?.toString() == "android.widget.TextView") {
                out += bounds(node) to text
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.add(it) }
        }
        return out
    }
}
