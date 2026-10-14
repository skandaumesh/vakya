package com.replybot.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The notifications Friday has seen, on this phone only (a file in Vakya's private storage):
 * the last day of them, for "any new messages?", and for a week just who sent something on which
 * app, for "who texts me most?". OTPs, codes and passwords are never kept.
 */
class NotificationLog private constructor(private val file: File) {

    data class Item(
        val pkg: String,
        val app: String,
        /** The sender, or "Group: sender" in a group chat. */
        val who: String,
        val text: String,
        val time: Long,
        val isMessage: Boolean,
        val isGroup: Boolean,
    )

    private val items = ArrayList<Item>()
    private val senders = ArrayList<Triple<String, String, Long>>()  // app, who, time

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val j = JSONObject(file.readText())
            j.optJSONArray("items")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).run {
                    items += Item(
                        optString("pkg"), optString("app"), optString("who"), optString("text"), optLong("time"),
                        optBoolean("message"), optBoolean("group"),
                    )
                }
            }
            j.optJSONArray("senders")?.let { a ->
                for (i in 0 until a.length()) a.getJSONArray(i).run { senders += Triple(getString(0), getString(1), getLong(2)) }
            }
        }
    }

    /** Keeps [item] unless it's a secret or already here. True when it's new. */
    @Synchronized
    fun add(item: Item): Boolean {
        if (isSecret(item.text) || isSecret(item.who)) return false
        if (items.any { it.pkg == item.pkg && it.who == item.who && it.text == item.text && kotlin.math.abs(it.time - item.time) < DAY }) {
            return false
        }
        items += item
        if (item.isMessage) senders += Triple(item.app, item.who.substringBefore(": ").trim(), item.time)
        prune()
        save()
        return true
    }

    /** Newest first, from the last [hours]. */
    @Synchronized
    fun recent(hours: Int = 12, max: Int = 25): List<Item> {
        val since = System.currentTimeMillis() - hours * HOUR
        return items.filter { it.time >= since }.sortedByDescending { it.time }.take(max)
    }

    /** Who messaged most this week: ("Rahul (WhatsApp)", 42). */
    @Synchronized
    fun topSenders(max: Int = 5): List<Pair<String, Int>> =
        senders.groupingBy { (app, who, _) -> "$who ($app)" }.eachCount()
            .entries.sortedByDescending { it.value }.take(max).map { it.key to it.value }

    @Synchronized
    fun clear() {
        items.clear()
        senders.clear()
        file.delete()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        items.removeAll { now - it.time > DAY }
        while (items.size > 200) items.removeAt(0)
        senders.removeAll { now - it.third > 7 * DAY }
        while (senders.size > 3000) senders.removeAt(0)
    }

    private fun save() {
        runCatching {
            val j = JSONObject()
                .put("items", JSONArray(items.map {
                    JSONObject().put("pkg", it.pkg).put("app", it.app).put("who", it.who).put("text", it.text)
                        .put("time", it.time).put("message", it.isMessage).put("group", it.isGroup)
                }))
                .put("senders", JSONArray(senders.map { JSONArray().put(it.first).put(it.second).put(it.third) }))
            file.writeText(j.toString())
        }
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR

        // OTPs, login codes, passwords: never kept, never sent anywhere.
        private val SECRET_WORDS = Regex(
            "\\b(otp|one[ -]?time password|verification|security code|login code|authenticat\\w*|passcode|password|cvv)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val CODE = Regex("\\b(code|pin)\\b", RegexOption.IGNORE_CASE)
        private val DIGITS = Regex("\\b(\\d{4,8}|\\d{3}[- ]\\d{3})\\b")  // 4821, 552901, 123-456

        fun isSecret(text: String) = SECRET_WORDS.containsMatchIn(text) || (CODE.containsMatchIn(text) && DIGITS.containsMatchIn(text))

        @Volatile private var instance: NotificationLog? = null

        fun get(context: Context): NotificationLog = instance ?: synchronized(this) {
            instance ?: NotificationLog(File(context.applicationContext.filesDir, "notifications.json")).also { instance = it }
        }

        /** "5 min ago", "2:14 pm", "yesterday 9:30 pm": how Friday says when. */
        fun whenSaid(time: Long, now: Long = System.currentTimeMillis()): String {
            val minutes = ((now - time) / 60_000).toInt()
            if (minutes < 1) return "just now"
            if (minutes < 60) return "$minutes min ago"
            val clock = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(time)).lowercase()
            val day = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.DAY_OF_YEAR)
            val today = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
            return if (day == today) clock else "yesterday $clock"
        }
    }
}
