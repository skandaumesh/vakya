package com.replybot.agent

import com.replybot.accessibility.ScreenAgent
import com.replybot.data.DeviceTask
import com.replybot.device.SystemControls
import com.replybot.memory.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FridayAgentTest {

    private fun step(kind: String, status: TaskRunner.Status, out: Outcome?, target: String = "", text: String = "") =
        TaskRunner.Step(DeviceTask(kind = kind, target = target, text = text)).apply {
            this.status = status
            this.outcome = out
        }

    @Test
    fun `one step speaks what the phone measured`() {
        val r = TaskRunner.Report(listOf(step("battery", TaskRunner.Status.DONE, Outcome.info("You're at 54 percent, boss."))))
        assertEquals("You're at 54 percent, boss.", r.speech("Checking, boss."))
    }

    @Test
    fun `all steps done says the plan, plus anything already done`() {
        val r = TaskRunner.Report(listOf(
            step("toggle", TaskRunner.Status.DONE, Outcome(true, "Wi-Fi is already on, boss.", "already on"), target = "wifi"),
            step("alarm", TaskRunner.Status.DONE, Outcome.OK),
        ))
        assertTrue(r.ok)
        assertEquals("Wi-Fi on and alarm set, boss. Wi-Fi is already on, boss.", r.speech("Wi-Fi on and alarm set, boss."))
    }

    @Test
    fun `a failed step is never reported as done`() {
        val r = TaskRunner.Report(listOf(
            step("alarm", TaskRunner.Status.DONE, Outcome.OK),
            step("message", TaskRunner.Status.FAILED, Outcome.fail("I couldn't find Rahul in your contacts, boss.")),
            step("send", TaskRunner.Status.SKIPPED, null),
        ))
        assertFalse(r.ok)
        val said = r.speech("Alarm set and message sent, boss.")
        assertTrue(said, said.startsWith("I did 1 of 3, boss."))
        assertTrue(said, "couldn't find Rahul" in said)
        assertFalse(said, "message sent" in said)
    }

    @Test
    fun `only screen taps are retried by looking`() {
        val tap = TaskRunner.Report(listOf(step("tap", TaskRunner.Status.FAILED, Outcome.fail("not found"), text = "Accept")))
        val call = TaskRunner.Report(listOf(step("call", TaskRunner.Status.FAILED, Outcome.fail("no contact"))))
        assertTrue(tap.retryable)
        assertFalse(call.retryable)
        assertEquals(listOf("Tap “Accept”: not found"), tap.failures())
    }

    @Test
    fun `steps are described for the screen`() {
        assertEquals("Wi-Fi on", TaskRunner.describe(DeviceTask(kind = "toggle", target = "wifi", on = true)))
        assertEquals("Brightness 40%", TaskRunner.describe(DeviceTask(kind = "brightness", value = 40)))
        assertEquals("Alarm 06:30 · wake up", TaskRunner.describe(DeviceTask(kind = "alarm", time = "06:30", label = "wake up")))
        assertEquals("Switch to last app", TaskRunner.describe(DeviceTask(kind = "phone", target = "last_app")))
    }

    @Test
    fun `buttons are matched by their words`() {
        fun s(have: String, want: String) = ScreenAgent.score(ScreenAgent.norm(have), ScreenAgent.norm(want))
        assertEquals(100, s("Accept", "accept"))
        assertTrue(s("Accept all cookies", "accept") >= 40)
        assertTrue(s("Instal", "install") >= 40)  // a speech-to-text slip
        assertEquals(0, s("Settings", "accept"))
        assertTrue(s("Accept", "accept") > s("Accept all cookies and continue to the website", "accept"))
    }

    @Test
    fun `brightness follows the slider, not the raw number`() {
        assertEquals(255, SystemControls.percentToRaw(100, 255))
        assertTrue(SystemControls.percentToRaw(50, 255) in 40..70)
        assertEquals(1, SystemControls.percentToRaw(0, 255))
        for (p in listOf(10, 30, 50, 80, 100)) {
            val back = SystemControls.rawToPercent(SystemControls.percentToRaw(p, 4095), 4095)
            assertTrue("$p -> $back", kotlin.math.abs(back - p) <= 2)
        }
    }

    @Test
    fun `tasks are found by most of their words`() {
        val tasks = listOf("Pay the rent", "Call Amma about Sunday", "Finish the report")
        assertEquals("Pay the rent", Memory.bestMatch("I've paid the rent", tasks) { it })
        assertEquals("Finish the report", Memory.bestMatch("report finished", tasks) { it })
        assertNull(Memory.bestMatch("buy milk", tasks) { it })
    }

    @Test
    fun `times from the server`() {
        val t = Memory.parseTime("2026-11-05 08:00")
        assertTrue(t > 0)
        assertEquals(0L, Memory.parseTime("tomorrow"))
        val now = Memory.parseTime("2026-11-05 09:00")
        assertEquals(Memory.parseTime("2026-11-06 08:00"), Memory.nextAt(8, 0, now))
        assertEquals(Memory.parseTime("2026-11-05 21:30"), Memory.nextAt(21, 30, now))
    }
}
