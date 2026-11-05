package com.replybot.agent

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.replybot.data.DeviceTask
import com.replybot.jarvis.DeviceActions
import com.replybot.service.ChatReaderService

/** How one step went: [ok] only once the phone has checked it. [say] replaces Friday's line when set. */
data class Outcome(val ok: Boolean, val say: String? = null, val note: String = "") {
    companion object {
        val OK = Outcome(true)
        fun fail(say: String, note: String = say) = Outcome(false, say, note)
        fun info(say: String) = Outcome(true, say)
    }
}

/**
 * Friday's hands, one step at a time: runs a plan's steps in order on the main thread, waits for
 * apps to open before touching their screens, checks each step really happened, skips steps that
 * depended on one that failed, and reports. Nothing is ever reported done that wasn't checked.
 */
class TaskRunner(private val actions: DeviceActions) {

    enum class Status { WAITING, RUNNING, DONE, FAILED, SKIPPED }

    class Step(val task: DeviceTask) {
        @Volatile var status = Status.WAITING
        @Volatile var note = ""
        @Volatile var outcome: Outcome? = null
        val title: String get() = describe(task)
    }

    class Report(val steps: List<Step>) {
        val failed get() = steps.filter { it.status == Status.FAILED }
        val done get() = steps.filter { it.status == Status.DONE }
        val ok get() = steps.none { it.status == Status.FAILED || it.status == Status.SKIPPED }

        /** A failure Friday could fix by looking at the screen and trying another way. */
        val retryable get() = failed.any { it.task.kind in setOf("tap", "type") }

        /** What Friday says, given what she planned to say ([planned]). Honest about failures. */
        fun speech(planned: String): String {
            if (steps.size == 1) return steps[0].outcome?.say ?: planned
            if (ok) {
                // "already on" and measurements (battery) are worth saying too.
                val extra = done.mapNotNull { it.outcome?.say }.filter { "already" in it.lowercase() }
                return (listOf(planned) + extra).joinToString(" ")
            }
            val problems = (failed + steps.filter { it.status == Status.SKIPPED }.take(1))
                .mapNotNull { s -> s.outcome?.say ?: if (s.status == Status.SKIPPED) "I skipped ${s.title.lowercase()} after that." else null }
                .distinct()
            val head = if (done.isEmpty()) "That didn't work, boss." else "I did ${done.size} of ${steps.size}, boss."
            return (listOf(head) + problems).joinToString(" ")
        }

        /** For the server when Friday tries again: what failed and why. */
        fun failures(): List<String> = failed.map { "${it.title}: ${it.note.ifBlank { it.outcome?.say.orEmpty() }}" }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var token = 0

    val busy get() = running

    @Volatile private var running = false

    fun cancel() {
        token++
        running = false
    }

    fun run(tasks: List<DeviceTask>, update: (List<Step>) -> Unit, done: (Report) -> Unit) {
        val me = ++token
        running = true
        val steps = tasks.map(::Step)
        update(steps)

        fun finish() {
            running = false
            done(Report(steps))
        }

        fun next(i: Int) {
            if (me != token) {
                steps.drop(i).forEach { if (it.status == Status.WAITING) it.status = Status.SKIPPED }
                update(steps)
                return
            }
            if (i >= steps.size) return finish()
            val step = steps[i]
            val previous = steps.getOrNull(i - 1)
            // A screen step after a failed app step would press things in the wrong app.
            if (previous != null && previous.status == Status.FAILED && step.task.kind in SCREEN_STEPS) {
                step.status = Status.SKIPPED
                update(steps)
                return next(i + 1)
            }
            step.status = Status.RUNNING
            update(steps)
            val before = ChatReaderService.running?.foregroundPackage()
            val ran = try {
                actions.run(step.task) { out -> main.post { if (me == token) afterStep(me, steps, i, out, before, update) { next(i + 1) } } }
                true
            } catch (e: Exception) {
                Log.w("Vakya", "friday step ${step.task.kind} crashed: ${e.javaClass.simpleName}")
                false
            }
            if (!ran) afterStep(me, steps, i, Outcome.fail("Something went wrong with ${step.title.lowercase()}, boss."), before, update) { next(i + 1) }
        }
        next(0)
    }

    private fun afterStep(
        me: Int, steps: List<Step>, i: Int, out: Outcome, before: String?,
        update: (List<Step>) -> Unit, next: () -> Unit,
    ) {
        val step = steps[i]
        step.outcome = out
        step.note = out.note
        step.status = if (out.ok) Status.DONE else Status.FAILED
        update(steps)
        val opens = out.ok && step.task.kind in OPENS_APP && actions.opened != null && actions.opened != before
        val more = i + 1 < steps.size
        if (!opens) {
            main.postDelayed(next, if (more) 350L else 0L)
            return
        }
        // Check the app really came to the front (vivo, Xiaomi and Oppo can silently block it).
        val target = actions.opened
        main.postDelayed({
            if (me != token) return@postDelayed
            val now = ChatReaderService.running?.foregroundPackage()
            if (now != null && now == before && now != target) {
                step.status = Status.FAILED
                step.note = "the phone blocked opening the app"
                step.outcome = Outcome.fail("Your phone stopped me opening it, boss. In Vakya, tap Let Friday open apps.", step.note)
                update(steps)
            }
            next()
        }, if (more) 1900L else 1500L)
    }

    companion object {
        /** Steps that open an app (checked to have come to the front, and waited for before the next). */
        val OPENS_APP = setOf("open_app", "play", "navigate", "search", "message", "files", "calendar_add", "settings")

        /** Steps that act on whatever is on screen. */
        val SCREEN_STEPS = setOf("tap", "type", "scroll", "send")

        /** Steps that need the phone unlocked (apps can't open over the lock screen). */
        val NEEDS_SCREEN = setOf(
            "open_app", "play", "navigate", "search", "message", "scroll", "settings", "tap", "type",
            "calendar_add", "files", "send",
        )

        /** "Turn Wi-Fi on", "Alarm 06:30", "Message Amma" — for the step list on screen. */
        fun describe(t: DeviceTask): String = when (t.kind) {
            "alarm" -> "Alarm ${t.time}" + if (t.label.isNotBlank()) " · ${t.label}" else ""
            "timer" -> "Timer ${t.seconds / 60} min" + if (t.seconds % 60 != 0) " ${t.seconds % 60} s" else ""
            "open_app" -> "Open ${t.app}"
            "call" -> "Call ${t.contact}"
            "message" -> if (t.text.isBlank()) "Open chat with ${t.contact}" else "Message ${t.contact}"
            "play" -> "Play ${t.text}" + if (t.app.isNotBlank()) " on ${t.app}" else ""
            "navigate" -> "Directions to ${t.text}"
            "search" -> "Search “${t.text}”"
            "flashlight" -> "Flashlight ${if (t.on) "on" else "off"}"
            "battery" -> "Check battery"
            "phone" -> when (t.target) {
                "last_app" -> "Switch to last app"
                "quick_settings" -> "Open Quick Settings"
                else -> t.target.replaceFirstChar { it.uppercase() }
            }
            "settings" -> "Open ${t.target.ifBlank { "Settings" }} settings"
            "volume" -> when {
                t.value >= 0 -> "Volume ${t.value}%"
                t.target == "mute" -> "Mute"
                t.target == "unmute" -> "Unmute"
                else -> "Volume ${if (t.on) "up" else "down"}"
            }
            "brightness" -> if (t.value >= 0) "Brightness ${t.value}%" else "Brightness ${if (t.on) "up" else "down"}"
            "toggle" -> "${TOGGLE_NAMES[t.target] ?: t.target} ${if (t.on) "on" else "off"}"
            "tap" -> "Tap “${t.text}”"
            "type" -> "Type “${t.text.take(30)}”"
            "note_add" -> "Note: ${t.text}"
            "notes_read" -> "Read your list"
            "notes_clear" -> "Clear your list"
            "task_add" -> "Task: ${t.text}"
            "task_done" -> "Done: ${t.text}"
            "calendar_add" -> "Calendar: ${t.text}"
            "files" -> "Open ${t.target.ifBlank { "files" }}"
            "routine_save" -> "Save routine “${t.label}”"
            "scroll" -> when {
                t.target == "stop" -> "Stop scrolling"
                t.seconds > 0 -> "Auto-scroll every ${t.seconds} s"
                else -> "Scroll ${if (t.on) "down" else "up"}"
            }
            "media" -> t.target.replaceFirstChar { it.uppercase() } + " music"
            "send" -> "Send the message"
            else -> t.kind
        }

        val TOGGLE_NAMES = mapOf(
            "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "mobile_data" to "Mobile data", "dnd" to "Do not disturb",
            "battery_saver" to "Battery saver", "rotation" to "Auto-rotate", "airplane" to "Airplane mode",
            "hotspot" to "Hotspot", "location" to "Location", "dark_mode" to "Dark mode", "nfc" to "NFC",
        )
    }
}
