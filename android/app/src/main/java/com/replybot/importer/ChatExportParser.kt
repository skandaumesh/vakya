package com.replybot.importer

/**
 * Parser for WhatsApp "Export chat" .txt files (Android and iOS formats).
 * Port of backend/app/chat_export.py; keep the two in sync.
 */
object ChatExportParser {

    data class Message(val sender: String, val text: String)

    // 12/31/23, 9:15 PM - Name: text   |   31/12/2023, 21:15 - Name: text   |   [31/12/23, 9:15:23 PM] Name: text
    private val LINE = Regex(
        """^\[?(\d{1,4}[./-]\d{1,2}[./-]\d{1,4}),?\s+(\d{1,2}[:.]\d{2}(?:[:.]\d{2})?(?:\s?[APap]\.?\s?[Mm]\.?)?)\]?\s*(?:-\s*)?(.*)$"""
    )
    private val CONTACT_FROM_FILE = Regex(
        """WhatsApp Chat (?:with|-)\s*(.+?)(?:\s*\(\d+\))?\.txt$""",
        RegexOption.IGNORE_CASE,
    )
    private val SKIP = setOf(
        "<media omitted>", "this message was deleted", "you deleted this message",
        "null", "missed voice call", "missed video call",
    )
    private const val EDITED = "<This message was edited>"

    fun parse(raw: String): List<Message> {
        val parsed = mutableListOf<Pair<String, StringBuilder>>()
        var current: StringBuilder? = null

        for (rawLine in raw.lines()) {
            val line = normalise(rawLine)
            val match = LINE.matchEntire(line)
            if (match == null) {
                // Continuation of a multi-line message.
                current?.append('\n')?.append(line)
                continue
            }
            val rest = match.groupValues[3]
            val sep = rest.indexOf(": ")
            if (sep < 0) {
                // System line (encryption notice, group events).
                current = null
                continue
            }
            current = StringBuilder(rest.substring(sep + 2))
            parsed += rest.substring(0, sep).trimStart('~', ' ').trim() to current
        }

        return parsed.mapNotNull { (sender, sb) ->
            val text = sb.toString().replace(EDITED, "").trim()
            val low = text.lowercase()
            val skip = text.isEmpty() || low in SKIP || (low.endsWith(" omitted") && low.split(' ').size <= 2)
            if (skip) null else Message(sender, text)
        }
    }

    /**
     * (what they said, what I answered) for every time I replied to them. Their side is
     * their last few messages before my reply; my side is the first message I sent back.
     */
    fun replyPairs(messages: List<Message>, me: String): List<Pair<String, String>> {
        val turns = mutableListOf<Pair<Boolean, MutableList<String>>>()
        for (m in messages) {
            val mine = m.sender == me
            if (turns.isNotEmpty() && turns.last().first == mine) turns.last().second += m.text
            else turns += mine to mutableListOf(m.text)
        }
        return turns.zipWithNext().mapNotNull { (theirs, mine) ->
            if (theirs.first || !mine.first) return@mapNotNull null
            val prompt = theirs.second.takeLast(3).joinToString(" ")
            val reply = mine.second.first()
            if (prompt.length > 300 || reply.length > 200 || reply.startsWith("http")) null else prompt to reply
        }
    }

    /** Senders ordered by message count, most active first. */
    fun senders(messages: List<Message>): List<String> =
        messages.groupingBy { it.sender }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    /** In a 1:1 export, "me" is the sender who isn't the contact the file is named after. */
    fun guessMe(messages: List<Message>, contactName: String?): String? {
        val names = senders(messages)
        if (names.size != 2 || contactName.isNullOrBlank()) return null
        val others = names.filter { !it.equals(contactName, ignoreCase = true) }
        return others.singleOrNull()
    }

    /** "WhatsApp Chat with Rahul.txt" -> "Rahul". */
    fun contactFromFilename(filename: String): String? =
        CONTACT_FROM_FILE.find(filename)?.groupValues?.get(1)?.trim()

    private fun normalise(line: String): String = line
        .replace("‎", "").replace("‏", "").replace("﻿", "")
        .replace(' ', ' ').replace(' ', ' ')
        .trimEnd('\r')
}
