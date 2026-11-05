package com.replybot.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.replybot.jarvis.Habits

/**
 * Friday's eyes and fingers on any app, through the accessibility service, and only when asked:
 * reads the words and buttons on screen (for "what's on my screen?"), taps a button by its words
 * ("tap Accept"), types into the selected box, and switches back to the last app. Nothing it
 * reads is kept.
 */
class ScreenAgent(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())

    /** The apps' windows on screen now (not Vakya's own overlays), topmost first. */
    private fun roots(): List<AccessibilityNodeInfo> {
        val own = service.packageName
        val ws = runCatching { service.windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() != own || it.packageName == null }
        return ws.ifEmpty { listOfNotNull(service.rootInActiveWindow) }
    }

    private fun walk(root: AccessibilityNodeInfo, max: Int = 2000, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = arrayListOf(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < max) {
            val n = stack.removeAt(stack.lastIndex)
            seen++
            visit(n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
    }

    private fun words(n: AccessibilityNodeInfo): String =
        (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString().orEmpty())
            .replace(Regex("\\s+"), " ").trim()

    /** Words and controls on screen, top to bottom: "[button] Install", "[switch on] Wi-Fi", "Storage almost full". */
    fun screenText(max: Int = 120): List<String> {
        data class Item(val top: Int, val left: Int, val text: String)
        val items = ArrayList<Item>()
        val seen = HashSet<String>()
        for (root in roots().take(3)) {
            walk(root) { n ->
                if (!n.isVisibleToUser) return@walk
                val w = words(n)
                if (w.isEmpty() || w.length > 300) return@walk
                val tag = when {
                    n.isEditable -> "[field] "
                    n.isCheckable -> if (n.isChecked) "[switch on] " else "[switch off] "
                    n.isClickable -> "[button] "
                    else -> ""
                }
                val line = tag + w.take(160)
                if (seen.add(line)) {
                    val r = Rect().also(n::getBoundsInScreen)
                    items += Item(r.top, r.left, line)
                }
            }
        }
        return items.sortedWith(compareBy({ it.top / 24 }, { it.left })).map { it.text }.take(max)
    }

    /** The package of the app in front. */
    fun foreground(): String? = roots().firstOrNull()?.packageName?.toString()

    /**
     * Taps the control whose words best match [label]. Returns the words it tapped, or null if
     * nothing on screen matched well enough.
     */
    fun tap(label: String): String? {
        val want = norm(label)
        if (want.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestWords = ""
        var bestScore = 0
        for (root in roots().take(3)) {
            walk(root) { n ->
                if (!n.isVisibleToUser) return@walk
                val w = words(n)
                if (w.isEmpty()) return@walk
                val s = score(norm(w), want) + if (clickable(n) != null) 5 else 0
                if (s > bestScore) {
                    bestScore = s
                    best = n
                    bestWords = w
                }
            }
        }
        val node = best ?: return null
        if (bestScore < 40) return null
        val target = clickable(node)
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return bestWords
        // Not clickable through accessibility (games, web views): a real tap in its middle.
        val r = Rect().also(node::getBoundsInScreen)
        if (r.isEmpty) return null
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val tap = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return if (service.dispatchGesture(tap, null, null)) bestWords else null
    }

    /** Types [text] into the selected box (or the first one on screen). */
    fun type(text: String): Boolean {
        val focused = runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val box = focused?.takeIf { it.isEditable } ?: run {
            var first: AccessibilityNodeInfo? = null
            for (root in roots().take(2)) {
                walk(root) { n -> if (first == null && n.isEditable && n.isVisibleToUser) first = n }
                if (first != null) break
            }
            first
        } ?: return false
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return box.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
    }

    /**
     * Back to the app used before this one: from Android's usage history when "Learn my day" is
     * allowed (exact), else a double tap of Recents (most phones switch apps that way).
     */
    fun lastApp(): Boolean {
        previousApp()?.let { pkg ->
            val launch = service.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null && runCatching { service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) return false
        main.postDelayed({ service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }, 350)
        return true
    }

    private fun previousApp(): String? {
        if (!Habits.allowed(service)) return null
        val usage = service.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usage.queryEvents(now - 30 * 60_000L, now) }.getOrNull() ?: return null
        val current = foreground()
        val launchers = service.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        ).map { it.activityInfo.packageName }.toSet()
        val order = ArrayList<String>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                order.remove(e.packageName)
                order += e.packageName
            }
        }
        return order.asReversed().firstOrNull {
            it != current && it != service.packageName && it !in launchers && it != "com.android.systemui"
        }
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && !n.isClickable && depth < 5) {
            n = n.parent
            depth++
        }
        return n?.takeIf { it.isClickable && it.isEnabled }
    }

    companion object {
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

        /** How well on-screen words [have] match what was asked for [want] (both normalised), 0-100. */
        fun score(have: String, want: String): Int {
            if (have.isEmpty() || want.isEmpty()) return 0
            if (have == want) return 100
            if (have.startsWith("$want ") || have.endsWith(" $want")) return 80
            if (Regex("\\b${Regex.escape(want)}\\b").containsMatchIn(have) && have.length <= want.length * 3) return 70
            val a = have.split(' ').toSet()
            val b = want.split(' ').toSet()
            val overlap = a.intersect(b).size.toDouble() / b.size
            if (overlap >= 0.75 && a.size <= b.size + 3) return 60
            if (want.length >= 4 && have.length <= want.length + 3 && distance(have, want) <= maxOf(1, want.length / 5)) return 50
            return if (overlap >= 0.5 && a.size <= b.size + 2) 40 else 0
        }

        private fun distance(a: String, b: String): Int {
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
}
