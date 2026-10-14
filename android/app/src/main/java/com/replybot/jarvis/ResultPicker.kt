package com.replybot.jarvis

/**
 * "Play X": which search result row to tap. The row whose words match X, spelled loosely (speech
 * to text writes "Kesaria", Spotify "Kesariya"), songs before albums or artists, then the higher one.
 */
object ResultPicker {

    /** One tappable result: where it starts on screen and all the words in it. */
    data class Row(val top: Int, val text: String)

    private val SPLIT = Regex("[^\\p{L}0-9]+")
    // Words that say what kind of thing, not which one.
    private val FILLER = setOf(
        "song", "songs", "track", "music", "video", "full", "lyrics", "official", "audio",
        "the", "by", "from", "on", "spotify", "youtube",
    )
    private val SONG = Regex("(^|\\s)song\\s*[•·]", RegexOption.IGNORE_CASE)

    /**
     * The row to tap, or null to wait for the results. [waited]: the results have had time to load,
     * so when no row's words match (a name misheard), trust the app's own top song.
     */
    fun pick(rows: List<Row>, query: String, waited: Boolean): Row? {
        val want = words(query).filter { it !in FILLER }.distinct()
        val best = rows
            .map { row -> row to words(row.text).let { have -> want.count { w -> have.any { similar(w, it) } } } }
            .filter { (_, score) -> score > 0 && score * 2 >= want.size }
            .sortedWith(
                compareByDescending<Pair<Row, Int>> { it.second }
                    .thenByDescending { isSong(it.first) }
                    .thenBy { it.first.top },
            )
            .firstOrNull()?.first
        if (best != null || !waited) return best
        return rows.filter(::isSong).minByOrNull { it.top }
    }

    fun isSong(row: Row) = SONG.containsMatchIn(row.text)

    private fun words(s: String) = s.lowercase().split(SPLIT).filter { it.length > 1 }

    /** Same word, give or take a letter: "kesaria" and "kesariya", "beat" and "beats". */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val shorter = minOf(a.length, b.length)
        val apart = kotlin.math.abs(a.length - b.length)
        return when {
            shorter >= 9 -> apart <= 2 && editDistance(a, b) <= 2
            shorter >= 5 -> apart <= 1 && editDistance(a, b) <= 1
            // Short words: only an added ending ("beat", "beats"), so "love" isn't "live".
            shorter == 4 -> apart == 1 && (a.startsWith(b) || b.startsWith(a))
            else -> false
        }
    }

    fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val cur = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
        }
        return d[b.length]
    }
}
