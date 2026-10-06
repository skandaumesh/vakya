package com.replybot.data

/**
 * One item in the chat. A photo or sticker has [media] set ("photo", "sticker", ...),
 * [text] holds its caption if any, and [image] a small base64 JPEG when captured.
 */
data class ChatMsg(
    val fromMe: Boolean,
    val text: String,
    val media: String? = null,
    val image: String? = null,
)

data class Suggestion(val label: String, val text: String)

data class SuggestResult(
    /** What their message says, in plain English: shows the user what Vakya understood. */
    val meaning: String,
    val intent: String,
    val relationshipGuess: String,
    val language: String,
    val suggestions: List<Suggestion>,
    val memoryAdd: List<String>,
    val memoryResolve: List<String>,
    val latencyMs: Int,
)

/**
 * "Write it for me": what the user typed in the box (an idea in their own words),
 * written out once per style. Each variant's label is the style's name.
 */
data class ComposeResult(
    /** What Vakya understood the user wants to say (or asked for), in plain English. */
    val meaning: String,
    /** True when the user asked for something to send ("pickup line", "roast him"):
     *  then [variants] are 4 different ideas, each labelled with its flavour. */
    val ideas: Boolean,
    val language: String,
    val variants: List<Suggestion>,
    val latencyMs: Int,
)

/** What Vakya knows about one chat. Lives only on the phone. */
class ContactRecord(
    /** Set by the user; wins over the model's guess. */
    var relationship: String? = null,
    var guessedRelationship: String? = null,
    val memory: MutableList<String> = mutableListOf(),
    /** The user's own recent messages in this chat, used as style examples. */
    val examples: MutableList<String> = mutableListOf(),
    /** Reply language picked for this chat (see [LANGUAGES]); null means auto. */
    var language: String? = null,
)

val RELATIONSHIPS = listOf("friend", "client", "professor", "partner", "family", "colleague")

/**
 * Identity slurs Vakya never suggests, even from the user's own past replies:
 * one wrong tap sends it to the wrong person. Same list as the backend's SLURS.
 */
val SLURS = Regex(
    """\b(?:nigg(?:a|as|az|er|ers)|fag(?:got)?s?|trann(?:y|ies)|chinks?|retard(?:s|ed)?|chamar|bhangi)\b""",
    RegexOption.IGNORE_CASE,
)

/** People who must never get replies (or slang) learned from friend chats. */
val NON_FRIEND = setOf("client", "professor", "colleague", "family", "partner")

/** Relationship choice meaning "let Vakya guess". */
const val AUTO = "auto"

/** Chat apps Vakya can work in (package name to name), in menu order. Keep in sync with accessibility_config.xml. */
val CHAT_APPS = listOf(
    "com.whatsapp" to "WhatsApp",
    "com.whatsapp.w4b" to "WhatsApp Business",
    "org.telegram.messenger" to "Telegram",
    "com.instagram.android" to "Instagram",
    "com.google.android.apps.messaging" to "Messages",
)

/** Until the user picks: WhatsApp only. */
val DEFAULT_APPS = setOf("com.whatsapp", "com.whatsapp.w4b")

/** The language menu in the chat panel, remembered per chat. Auto follows the chat. */
val LANGUAGES = listOf(
    "auto" to "Auto",
    "english" to "English",
    "kanglish" to "Kanglish",
    "kannada" to "ಕನ್ನಡ",
    "hinglish" to "Hinglish",
)

/** The style menu in the chat panel. */
val STYLES = listOf(
    "mine" to "My style",
    "professional" to "Professional",
    "short" to "Short",
    "genz" to "Gen Z",
)
