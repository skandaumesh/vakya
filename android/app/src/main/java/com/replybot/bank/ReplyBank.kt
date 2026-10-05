package com.replybot.bank

import android.content.Context
import com.replybot.data.SLURS
import org.json.JSONArray
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The user's own past replies: what someone said, and what the user answered.
 * Lives only on the phone. A new message is matched against everything they were
 * ever told, and the user's own answers come back as suggestions, instantly and offline.
 */
class ReplyBank(private val file: File?) {

    data class Entry(val prompt: String, val reply: String, val contact: String)

    data class Match(val reply: String, val score: Double, val prompt: String)

    private val entries = ArrayList<Entry>()
    private var vectors: List<Map<String, Double>> = emptyList()
    private var replyKeys: List<String> = emptyList()
    private var idf: Map<String, Double> = emptyMap()
    private var maxIdf = 1.0
    private var dirty = true

    @Synchronized
    fun size() = entries.size

    @Synchronized
    fun add(items: List<Entry>) {
        val seen = entries.mapTo(HashSet()) { it.prompt to it.reply }
        items.filter { it.prompt.isNotBlank() && it.reply.isNotBlank() }
            .forEach { if (seen.add(it.prompt to it.reply)) entries += it }
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        dirty = true
        save()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        dirty = true
        save()
    }

    /**
     * Best distinct replies to [query], best first, at least [MIN_SCORE].
     * Replies from the same chat get a small bonus; with [onlyThisChat] (clients,
     * family...) only that chat's replies are used, so friend slang never leaks there.
     */
    @Synchronized
    fun search(
        query: String,
        contact: String?,
        onlyThisChat: Boolean = false,
        limit: Int = 3,
        minScore: Double = MIN_SCORE,
    ): List<Match> {
        if (entries.isEmpty() || query.isBlank()) return emptyList()
        if (dirty) rebuild()
        val q = vector(TextSim.features(query))
        if (q.isEmpty()) return emptyList()

        val best = HashMap<String, Match>()
        entries.forEachIndexed { i, e ->
            val sameChat = contact != null && e.contact == contact
            if (onlyThisChat && !sameChat) return@forEachIndexed
            if (SLURS.containsMatchIn(e.reply)) return@forEachIndexed
            var score = cosine(q, vectors[i])
            if (score <= 0.0) return@forEachIndexed
            if (sameChat) score = minOf(1.0, score + SAME_CHAT_BONUS)
            val key = replyKeys[i]
            if ((best[key]?.score ?: -1.0) < score) best[key] = Match(e.reply, score, e.prompt)
        }
        return best.values.filter { it.score >= minScore }.sortedByDescending { it.score }.take(limit)
    }

    private fun rebuild() {
        val feats = entries.map { TextSim.features(it.prompt) }
        val df = HashMap<String, Int>()
        feats.forEach { f -> f.keys.forEach { df.merge(it, 1) { a, b -> a + b } } }
        val n = entries.size
        idf = df.mapValues { (_, d) -> ln((n + 1.0) / (d + 1.0)) + 1.0 }
        maxIdf = ln(n + 1.0) + 1.0
        vectors = feats.map(::vector)
        replyKeys = entries.map { TextSim.tokens(it.reply).joinToString(" ") }
        dirty = false
    }

    private fun vector(f: Map<String, Int>): Map<String, Double> {
        val v = f.mapValues { (k, count) -> (1 + ln(count.toDouble())) * (idf[k] ?: maxIdf) }
        val norm = sqrt(v.values.sumOf { it * it })
        return if (norm == 0.0) emptyMap() else v.mapValues { it.value / norm }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        val (small, big) = if (a.size <= b.size) a to b else b to a
        return small.entries.sumOf { (k, x) -> x * (big[k] ?: 0.0) }
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        entries.forEach { arr.put(JSONArray().put(it.prompt).put(it.reply).put(it.contact)) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val e = arr.optJSONArray(i) ?: continue
            entries += Entry(e.optString(0), e.optString(1), e.optString(2))
        }
        dirty = true
    }

    companion object {
        /** Below this a past reply is too different to suggest. */
        const val MIN_SCORE = 0.6

        /** At or above this, the user's own replies are shown without asking the AI. */
        const val STRONG_SCORE = 0.8

        private const val SAME_CHAT_BONUS = 0.05
        private const val MAX_ENTRIES = 5000

        /** Long messages rarely match a past one closely enough, so ask for more. */
        fun strongScoreFor(query: String) = if (query.split(' ').size > 12) 0.9 else STRONG_SCORE

        @Volatile
        private var instance: ReplyBank? = null

        fun get(context: Context): ReplyBank = instance ?: synchronized(this) {
            instance ?: ReplyBank(File(context.applicationContext.filesDir, "replybank.json"))
                .also {
                    it.load()
                    instance = it
                }
        }
    }
}
