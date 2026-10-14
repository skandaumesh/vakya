package com.replybot.jarvis

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import java.util.Calendar

/**
 * "Let Friday learn my day": a few lines about how the phone gets used, worked out on the phone
 * from Android's app-usage times (which app was open, from when to when; nothing inside the apps):
 * screen time today, a usual day this week, when each app gets used, when the day starts and ends,
 * and who messages most (from [NotificationLog]). Sent with a question only when it's about that.
 */
object Habits {

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    @Volatile private var cached: Pair<Long, String?>? = null

    fun allowed(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The lines for Friday, or null when there's nothing to tell. Worked out at most every 10 minutes. */
    fun summary(context: Context): String? {
        cached?.let { (at, text) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return text }
        val lines = mutableListOf<String>()
        if (allowed(context)) runCatching { usage(context, lines) }
        val top = NotificationLog.get(context).topSenders()
        if (top.isNotEmpty()) {
            lines += "messages most often from (last 7 days): " + top.joinToString(", ") { (who, n) -> "$who $n" }
        }
        if (!allowed(context)) lines += "app usage: access is off"
        val text = lines.joinToString("\n").ifBlank { null }
        cached = System.currentTimeMillis() to text
        return text
    }

    private fun usage(context: Context, lines: MutableList<String>) {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val start = midnight - 6 * DAY
        val skip = skipped(context)

        val week = HashMap<String, Long>()
        val today = HashMap<String, Long>()
        val hours = HashMap<String, LongArray>()
        // A "day" runs 4 am to 4 am, so a late night counts with the evening before it.
        val firstUse = HashMap<Long, Long>()
        val lastUse = HashMap<Long, Long>()
        val open = HashMap<String, Long>()

        fun used(pkg: String, from: Long, to: Long) {
            if (to <= from || pkg in skip) return
            val ms = to - from
            week[pkg] = (week[pkg] ?: 0) + ms
            if (from >= midnight) today[pkg] = (today[pkg] ?: 0) + ms
            val hour = Calendar.getInstance().apply { timeInMillis = from }.get(Calendar.HOUR_OF_DAY)
            hours.getOrPut(pkg) { LongArray(24) }[hour] += ms
            val day = Math.floorDiv(from - midnight - 4 * HOUR, DAY)
            firstUse[day] = minOf(firstUse[day] ?: Long.MAX_VALUE, from)
            lastUse[day] = maxOf(lastUse[day] ?: 0L, to)
        }

        val events = usm.queryEvents(start, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open[e.packageName] = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    open.remove(e.packageName)?.let { used(e.packageName, it, e.timeStamp) }
            }
        }
        open.forEach { (pkg, from) -> used(pkg, from, minOf(now, from + HOUR)) }
        if (week.isEmpty()) return

        val pm = context.packageManager
        fun name(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

        val todayTop = today.entries.sortedByDescending { it.value }.filter { it.value >= 60_000 }.take(6)
        if (todayTop.isNotEmpty()) {
            lines += "screen time today so far: " + todayTop.joinToString(", ") { "${name(it.key)} ${duration(it.value)}" } +
                "; all apps ${duration(today.values.sum())}"
        }
        val days = maxOf(1, firstUse.size)
        val usual = week.entries.sortedByDescending { it.value }.filter { it.value / days >= 60_000 }.take(8)
        if (usual.isNotEmpty()) {
            lines += "a usual day (average of the last $days days): " + usual.joinToString("; ") {
                "${name(it.key)} ${duration(it.value / days)}, mostly ${partOfDay(hours[it.key]!!)}"
            }
        }
        val past = firstUse.keys.filter { it < Math.floorDiv(now - midnight - 4 * HOUR, DAY) || firstUse.size == 1 }
        if (past.isNotEmpty()) {
            fun typical(times: List<Long>): String {
                val minutes = times.map { t ->
                    Calendar.getInstance().apply { timeInMillis = t }.let { c -> (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + 1440 - 240) % 1440 }
                }.sorted()
                val m = (minutes[minutes.size / 2] + 240) % 1440
                return clock(m / 60, m % 60)
            }
            lines += "phone use usually starts around ${typical(past.map { firstUse[it]!! })} and ends around ${typical(past.map { lastUse[it]!! })}"
        }
    }

    /** Home screens, the system and Vakya itself: not "using an app". */
    private fun skipped(context: Context): Set<String> {
        val pm = context.packageManager
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        return homes.toSet() + setOf(context.packageName, "android", "com.android.systemui")
    }

    private fun partOfDay(hours: LongArray): String {
        val parts = listOf(
            "early morning" to (4..7), "morning" to (8..11), "afternoon" to (12..16),
            "evening" to (17..20), "night" to (21..23), "late night" to (0..3),
        )
        return parts.maxByOrNull { (_, range) -> range.sumOf { hours[it] } }!!.first
    }

    private fun duration(ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    private fun clock(h: Int, m: Int): String {
        val hour = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(hour, m, if (h < 12) "am" else "pm")
    }
}
