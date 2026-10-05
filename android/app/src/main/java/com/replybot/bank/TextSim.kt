package com.replybot.bank

/**
 * Spelling-tolerant text features for short chat messages. No ML and no network:
 * words plus letter trigrams, so "elli idiya" still matches "yelli idiya?", and
 * stretched or shortened spellings ("okk", "gm", "where r u") match their usual forms.
 */
object TextSim {

    private val REPEATS = Regex("""(\p{L})\1+""")
    private val SPACES = Regex("""\s+""")

    /** "hiii" -> "hi", "okk" -> "ok". Applied to both sides, so "good"/"god" stay consistent. */
    private fun collapse(s: String) = REPEATS.replace(s, "$1")

    private val WORDS: Map<String, String> = mapOf(
        "u" to "you", "r" to "are", "ur" to "your", "thx" to "thanks", "thnx" to "thanks", "ty" to "thanks",
        "tq" to "thanks", "pls" to "please", "plz" to "please", "k" to "ok", "okay" to "ok", "tmrw" to "tomorrow",
        "tmr" to "tomorrow", "msg" to "message", "bday" to "birthday", "gud" to "good", "mrng" to "morning",
        "nyt" to "night", "wat" to "what", "wt" to "what",
    ).entries.associate { (from, to) -> collapse(from) to collapse(to) }

    private val PHRASES: List<Pair<Regex, String>> = listOf(
        "thank you" to "thanks", "good morning" to "gm", "good night" to "gn",
        "where are you" to "wru", "how are you" to "hru",
    ).map { (from, to) -> Regex("""\b${collapse(from)}\b""") to to }

    /** Lowercase words, shortcuts expanded, each emoji its own token ("e:😂"). */
    fun tokens(text: String): List<String> {
        val lower = text.lowercase()
        val sb = StringBuilder()
        var i = 0
        while (i < lower.length) {
            val cp = lower.codePointAt(i)
            when {
                Character.getType(cp) == Character.OTHER_SYMBOL.toInt() -> sb.append(" e:").appendCodePoint(cp).append(' ')
                Character.isLetterOrDigit(cp) || Character.isWhitespace(cp) -> sb.appendCodePoint(cp)
                else -> sb.append(' ')
            }
            i += Character.charCount(cp)
        }
        var s = collapse(sb.toString()).split(SPACES).filter { it.isNotEmpty() }.joinToString(" ") { WORDS[it] ?: it }
        PHRASES.forEach { (pattern, replacement) -> s = pattern.replace(s, replacement) }
        return s.split(' ').filter { it.isNotEmpty() }
    }

    /** Counts of word ("w:") and letter-trigram ("g:") features. */
    fun features(text: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (w in tokens(text)) {
            out.merge("w:$w", 1) { a, b -> a + b }
            if (w.startsWith("e:")) continue
            val padded = "^$w$"
            if (padded.length <= 3) {
                out.merge("g:$padded", 1) { a, b -> a + b }
            } else {
                for (j in 0..padded.length - 3) out.merge("g:" + padded.substring(j, j + 3), 1) { a, b -> a + b }
            }
        }
        return out
    }
}
