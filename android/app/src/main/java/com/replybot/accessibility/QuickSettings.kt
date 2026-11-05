package com.replybot.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Flips a Quick Settings tile the way a finger would: pulls down Quick Settings, finds the tile by
 * its name (Wi-Fi, Bluetooth, Do not disturb...), taps it if it isn't already the way the user
 * wants, follows Android 12+'s Internet panel to its switch, reads the tile again, and closes the
 * shade. Used where Android has no API for apps to switch something (Bluetooth, mobile data,
 * battery saver, airplane mode, hotspot...). Tile names differ between phone makers, so each
 * switch has several.
 */
class QuickSettings(private val service: AccessibilityService) {

    /** [found]: a tile with that name was on screen. [on]: its state afterwards, if it says. */
    data class Result(val found: Boolean, val on: Boolean?)

    private val main = Handler(Looper.getMainLooper())

    fun toggle(target: String, want: Boolean, done: (Result) -> Unit) {
        val names = NAMES[target] ?: return done(Result(false, null))
        open {
            findTile(names, page = 0) { tile ->
                if (tile == null) {
                    close()
                    return@findTile done(Result(false, null))
                }
                val before = stateOf(tile)
                if (before == want) {
                    close()
                    return@findTile done(Result(true, true == want))
                }
                click(tile)
                main.postDelayed({
                    // Android 12+: Internet opens a panel with the Wi-Fi / mobile data switch inside.
                    if (target == "wifi" || target == "mobile_data") flipPanelSwitch(names, want)
                    main.postDelayed({
                        val again = findNow(names)
                        val after = again?.let(::stateOf)
                        close()
                        done(Result(true, after))
                    }, 700)
                }, 800)
            }
        }
    }

    /** Sets the brightness slider in Quick Settings to [percent] (no "Modify system settings" needed). */
    fun brightness(percent: Int, done: (Boolean) -> Unit) {
        open {
            main.postDelayed({
                val slider = nodes().firstOrNull { n ->
                    n.rangeInfo != null && n.isVisibleToUser &&
                        (label(n).contains("bright") || n.className?.toString()?.contains("SeekBar") == true)
                }
                val ok = slider?.let { s ->
                    val r = s.rangeInfo
                    val value = r.min + (r.max - r.min) * percent.coerceIn(0, 100) / 100f
                    s.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) },
                    )
                } == true
                main.postDelayed({ close(); done(ok) }, 400)
            }, 500)
        }
    }

    private fun open(then: () -> Unit) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        main.postDelayed(then, 900)
    }

    fun close() {
        val ok = Build.VERSION.SDK_INT >= 31 &&
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        if (!ok) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            main.postDelayed({ if (shadeOpen()) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }, 250)
        }
    }

    private fun shadeOpen() = runCatching {
        service.windows.any { it.root?.packageName?.toString() == SYSTEM_UI && it.type == AccessibilityWindowInfo.TYPE_SYSTEM && boundsTall(it) }
    }.getOrDefault(false)

    private fun boundsTall(w: AccessibilityWindowInfo): Boolean {
        val r = android.graphics.Rect().also(w::getBoundsInScreen)
        return r.height() > service.resources.displayMetrics.heightPixels / 3
    }

    /** Looks for the tile; if it isn't on this page of Quick Settings, tries the next page. */
    private fun findTile(names: List<String>, page: Int, done: (AccessibilityNodeInfo?) -> Unit) {
        findNow(names)?.let { return done(it) }
        if (page >= 2) return done(null)
        // Fully expanded Quick Settings shows more tiles; then swipe the pages.
        if (page == 0) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        } else {
            nodes().firstOrNull { it.isScrollable && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        main.postDelayed({ findTile(names, page + 1, done) }, 700)
    }

    private fun findNow(names: List<String>): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (n in nodes()) {
            if (!n.isVisibleToUser) continue
            val l = label(n)
            if (l.isEmpty() || EXCLUDE.any { it in l }) continue
            val score = names.withIndex().maxOfOrNull { (i, name) ->
                val base = when {
                    l == name -> 30
                    l.startsWith("$name,") || l.startsWith("$name ") || l.startsWith(name) -> 20
                    Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(l) -> 10
                    else -> 0
                }
                if (base == 0) 0 else base + (names.size - i)  // earlier names are surer
            } ?: 0
            val clickable = clickableOf(n) ?: continue
            if (score > bestScore) {
                bestScore = score
                best = clickable
            }
        }
        return best
    }

    /** On, off, or unknown: from the tile's checked state, its state text, or its description. */
    private fun stateOf(tile: AccessibilityNodeInfo): Boolean? {
        if (tile.isCheckable) return tile.isChecked
        val words = buildList {
            if (Build.VERSION.SDK_INT >= 30) tile.stateDescription?.toString()?.let(::add)
            add(label(tile))
            for (i in 0 until tile.childCount) tile.getChild(i)?.let { c ->
                if (c.isCheckable) return c.isChecked
                add(label(c))
                if (Build.VERSION.SDK_INT >= 30) c.stateDescription?.toString()?.let(::add)
            }
        }.joinToString(" ").lowercase()
        return when {
            Regex("\\b(on|enabled|connected|active)\\b").containsMatchIn(words) && !Regex("\\b(off|disabled|turned off)\\b").containsMatchIn(words) -> true
            Regex("\\b(off|disabled|not connected|turned off)\\b").containsMatchIn(words) -> false
            else -> null
        }
    }

    private fun flipPanelSwitch(names: List<String>, want: Boolean) {
        val switch = nodes().firstOrNull { n ->
            n.isCheckable && n.isVisibleToUser && n.isChecked != want &&
                (names.any { it in label(n) } || names.any { name -> n.parent?.let { label(it).contains(name) } == true })
        } ?: return
        click(switch)
        // Close the panel it opened in.
        main.postDelayed({ service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }, 500)
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun clickableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && !n.isClickable && depth < 4) {
            n = n.parent
            depth++
        }
        return n?.takeIf { it.isClickable }
    }

    private fun label(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
            .joinToString(" ").trim().lowercase().replace(' ', ' ')

    /** Every node in System UI's windows (the shade and Quick Settings). */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val roots = runCatching { service.windows }.getOrDefault(emptyList())
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == SYSTEM_UI }
            .ifEmpty { listOfNotNull(service.rootInActiveWindow?.takeIf { it.packageName?.toString() == SYSTEM_UI }) }
        val out = ArrayList<AccessibilityNodeInfo>()
        for (root in roots) {
            val stack = arrayListOf(root)
            while (stack.isNotEmpty() && out.size < 2500) {
                val n = stack.removeAt(stack.lastIndex)
                out += n
                for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return out
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"

        /** Tile names by switch, surest first. Lowercase. */
        val NAMES = mapOf(
            "wifi" to listOf("wi-fi", "wifi", "wlan", "internet"),
            "bluetooth" to listOf("bluetooth"),
            "mobile_data" to listOf("mobile data", "cellular data", "data", "mobile network", "internet"),
            "dnd" to listOf("do not disturb", "dnd", "silent mode", "focus", "zen mode", "silent"),
            "battery_saver" to listOf("battery saver", "power saver", "power saving mode", "power saving", "battery saving", "low power", "saver"),
            "rotation" to listOf("auto-rotate", "auto rotate", "autorotate", "auto-rotation", "rotate", "portrait", "rotation lock"),
            "airplane" to listOf("airplane mode", "aeroplane mode", "flight mode", "airplane", "aeroplane"),
            "hotspot" to listOf("hotspot", "personal hotspot", "wi-fi hotspot", "mobile hotspot", "tethering"),
            "location" to listOf("location", "gps"),
            "dark_mode" to listOf("dark theme", "dark mode", "dark"),
            "nfc" to listOf("nfc"),
        )

        /** Tiles that look like a match but aren't (night light isn't dark mode). */
        private val EXCLUDE = listOf("night light", "eye comfort", "reading mode", "data saver", "edit", "settings")
    }
}
