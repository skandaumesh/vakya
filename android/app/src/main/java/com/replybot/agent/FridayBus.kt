package com.replybot.agent

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What Friday is doing right now, for every screen that shows her: the full-screen assistant, the
 * HUD over other apps, the floating orb and the Command Center. [com.replybot.jarvis.JarvisService]
 * is the only writer; listeners are called on the main thread.
 */
object FridayBus {

    enum class Phase(val label: String) {
        OFF("Asleep"),
        IDLE("Waiting for \"Hey Friday\""),
        LISTENING("Listening"),
        THINKING("Thinking"),
        LOOKING("Looking at your screen"),
        WORKING("Working"),
        SPEAKING("Speaking"),
        WAITING("Waiting for your answer"),
    }

    data class Line(val fromMe: Boolean, val text: String)

    data class State(
        val phase: Phase = Phase.OFF,
        /** Microphone loudness, 0..1, while listening. */
        val level: Float = 0f,
        val heard: String = "",
        val say: String = "",
        val steps: List<TaskRunner.Step> = emptyList(),
        /** This conversation so far, oldest first. */
        val lines: List<Line> = emptyList(),
        /** The screen is being captured (shown with an eye in every Friday view). */
        val seeing: Boolean = false,
        /** Steps waiting for the user's yes. */
        val pending: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Volatile
    var state = State()
        private set

    @Synchronized
    fun update(change: State.() -> State) {
        state = state.change()
        val s = state
        main.post { listeners.forEach { it(s) } }
    }

    fun line(fromMe: Boolean, text: String) {
        if (text.isBlank()) return
        update { copy(lines = (lines + Line(fromMe, text.trim())).takeLast(30)) }
    }

    /** Adds [l] and calls it at once with the current state. */
    fun listen(l: (State) -> Unit) {
        listeners += l
        main.post { l(state) }
    }

    fun unlisten(l: (State) -> Unit) {
        listeners -= l
    }
}
