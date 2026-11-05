package com.replybot.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.replybot.BuildConfig
import com.replybot.agent.FridayBus
import com.replybot.agent.TaskRunner
import com.replybot.automation.Proactive
import com.replybot.data.Store
import com.replybot.device.CalendarReader
import com.replybot.device.DeviceStatus
import com.replybot.device.SystemControls
import com.replybot.jarvis.JarvisService
import com.replybot.jarvis.NotificationLog
import com.replybot.memory.Memory
import com.replybot.permissions.Permissions
import com.replybot.service.ChatReaderService
import java.util.concurrent.Executors

/**
 * The Command Center: Friday's home. Her status and switch, the phone at a glance (tap a tile to
 * flip it), what's coming up, recent notifications, everything she remembers (search, export,
 * delete), routines, what she may do on her own, and every permission with why it's needed.
 * The reply assistant's settings are one tap away.
 */
class CommandCenterActivity : Activity() {

    private lateinit var kit: NeoKit
    private lateinit var store: Store
    private lateinit var memory: Memory
    private lateinit var status: DeviceStatus
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var heroOrb: OrbView
    private lateinit var heroStatus: TextView
    private lateinit var listenSwitch: Switch
    private lateinit var tiles: LinearLayout
    private lateinit var upNext: LinearLayout
    private lateinit var notes: LinearLayout
    private lateinit var memoryBox: LinearLayout
    private lateinit var searchResults: LinearLayout
    private lateinit var routinesBox: LinearLayout
    private lateinit var permsBox: LinearLayout
    private lateinit var factsBox: LinearLayout
    private var turnOnAfterMic = false

    private val busListener: (FridayBus.State) -> Unit = { s ->
        heroOrb.phase = if (JarvisService.running) s.phase else FridayBus.Phase.OFF
        heroOrb.level = s.level
        heroStatus.text = if (JarvisService.running) "ONLINE · ${s.phase.label.uppercase()}" else "STANDBY · FRIDAY IS OFF"
    }

    private val refreshTiles = object : Runnable {
        override fun run() {
            renderTiles()
            main.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = NeoKit(this)
        store = Store.get(this)
        memory = Memory.get(this)
        status = DeviceStatus(this)
        window.statusBarColor = Neo.BG_TOP
        window.navigationBarColor = Neo.BG_BOTTOM
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        // Friday can only start listening while Vakya is open (Android's rule for the microphone),
        // so this brings her back after a restart.
        if (store.jarvisOn && micAllowed() && !JarvisService.running) JarvisService.start(this)
        listenSwitch.isChecked = store.jarvisOn && micAllowed()
        Proactive.schedule(this)
        FridayBus.listen(busListener)
        main.post(refreshTiles)
        renderAll()
    }

    override fun onPause() {
        FridayBus.unlisten(busListener)
        main.removeCallbacks(refreshTiles)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun renderAll() {
        renderUpNext()
        renderNotifications()
        renderMemory()
        renderRoutines()
        renderPermissions()
    }

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------- Layout ----------

    private fun build(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.px(16), kit.px(18), kit.px(16), kit.px(40))
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            background = Neo.background()
            addView(col)
        }

        // Hero.
        heroOrb = OrbView(this).apply { setOnClickListener { openFriday(JarvisService.ACTION_TALK) } }
        heroStatus = kit.text("", 11f, Neo.CYAN, mono = true)
        listenSwitch = Switch(this)
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(heroOrb, LinearLayout.LayoutParams(kit.px(112), kit.px(112)).apply { marginEnd = kit.px(14) })
            addView(LinearLayout(this@CommandCenterActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(kit.text("COMMAND CENTER", 10.5f, Neo.MUTED, bold = true, mono = true).apply { letterSpacing = 0.18f })
                addView(TextView(this@CommandCenterActivity).apply {
                    text = "F.R.I.D.A.Y."
                    textSize = 30f
                    typeface = Neo.LIGHT
                    letterSpacing = 0.08f
                    setTextColor(Neo.TEXT)
                })
                addView(heroStatus)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val hero = kit.card().apply {
            addView(heroRow)
            val row = kit.switchRow("Listen for \"Hey Friday\"", "Heard on this phone; nothing is sent until you say it.", store.jarvisOn && micAllowed()) { on ->
                if (on) turnOn() else {
                    store.jarvisOn = false
                    JarvisService.stop(this@CommandCenterActivity)
                }
            }
            listenSwitch = row.tag as Switch
            addView(row)
            addView(kit.switchRow("Answer when called", "Say \"Hey Friday\" and pause: she says \"How can I help you, boss?\". Say it all in one go and she just does it.", store.fridayGreeting) {
                store.fridayGreeting = it
            })
            addView(LinearLayout(this@CommandCenterActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(kit.button("🎙  Talk to Friday", primary = true) { openFriday(JarvisService.ACTION_TALK) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = kit.px(8); topMargin = kit.px(8) }
                })
                addView(kit.button("Brief me", primary = false) { openFriday(briefing = "day") }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.7f).apply { topMargin = kit.px(8) }
                })
            })
        }
        col.addView(hero)

        // Device.
        tiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(kit.card().apply {
            addView(kit.heading("Your phone", "device"))
            addView(tiles)
            addView(kit.text("Tap a switch and Friday flips it: directly where Android allows, otherwise through Quick Settings.", 12f, Neo.MUTED))
        })

        // Up next.
        upNext = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val taskInput = kit.input("Add a task, e.g. Pay rent")
        col.addView(kit.card().apply {
            addView(kit.heading("Up next", "schedule"))
            addView(upNext)
            addView(taskInput)
            addView(kit.button("Add task", primary = false) {
                memory.addTask(taskInput.text.toString())
                taskInput.setText("")
                renderUpNext()
            })
        })

        // Notifications.
        notes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(kit.card().apply {
            addView(kit.heading("Recent notifications", "inbox"))
            addView(notes)
        })

        // Memory.
        memoryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        factsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = kit.input("Search memory: Rahul, rent, birthday…")
        val factInput = kit.input("Teach Friday a fact, e.g. Name: Skanda")
        col.addView(kit.card().apply {
            addView(kit.heading("Memory", "what friday knows"))
            addView(memoryBox)
            addView(kit.text("About you (long-press to forget):", 13f, Neo.TEXT, bold = true).apply { setPadding(0, kit.px(10), 0, 0) })
            addView(factsBox)
            addView(factInput)
            addView(kit.button("Add fact", primary = false) {
                val f = factInput.text.toString().trim()
                if (f.isNotEmpty()) {
                    store.updateProfile(listOf(f))
                    factInput.setText("")
                    renderMemory()
                }
            })
            addView(search)
            addView(kit.button("Search", primary = false) { renderSearch(search.text.toString()) })
            addView(searchResults)
            addView(LinearLayout(this@CommandCenterActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(kit.button("Export", primary = false) { exportMemory() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = kit.px(8); topMargin = kit.px(8) }
                })
                addView(kit.button("Delete all", primary = false) { deleteAllMemory() }.apply {
                    setTextColor(Neo.BAD)
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { topMargin = kit.px(8) }
                })
            })
            addView(kit.text("Everything here stays on this phone. Only the few items that match a question go to the AI, and the server keeps nothing.", 12f, Neo.MUTED))
        })

        // Routines.
        routinesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(kit.card().apply {
            addView(kit.heading("Routines", "automation"))
            addView(kit.text("Say a routine's name (\"Friday, I'm going to sleep\") and she plans its steps, then asks before doing them. Or make one by voice: \"when I say I'm driving, turn on Bluetooth and open Maps\".", 12.5f, Neo.MUTED))
            addView(routinesBox)
            addView(kit.button("+ New routine", primary = false) { editRoutine(null) })
        })

        // Proactive.
        col.addView(kit.card().apply {
            addView(kit.heading("On her own", "proactive"))
            addView(timedRow("Morning briefing", store.morningBriefing, { store.morningAt }, { store.morningAt = it }) { store.morningBriefing = it })
            addView(timedRow("Evening summary", store.eveningSummary, { store.eveningAt }, { store.eveningAt = it }) { store.eveningSummary = it })
            addView(kit.switchRow("Reminders when they're due", "Says them and shows a notification.", store.taskAlerts) { store.taskAlerts = it })
            addView(kit.switchRow("Upcoming events", "\"Design review in ten minutes, boss.\" Needs calendar access.", store.calendarAlerts) {
                store.calendarAlerts = it
                if (it && !CalendarReader.allowed(this@CommandCenterActivity)) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), Permissions.REQUEST_CALENDAR)
            })
            addView(kit.switchRow("Low battery", "Offers battery saver at 15 percent.", store.batteryAlerts) { store.batteryAlerts = it })
            addView(kit.switchRow("New messages", "\"Message from Rahul. Shall I read it?\" Needs notification access.", store.announceMessages) { store.announceMessages = it })
            addView(kit.switchRow("Floating Friday", "A small orb over your apps: tap to talk, hold to have her look at the screen.", store.floatingFriday) {
                store.floatingFriday = it
                ChatReaderService.running?.applyFloating()
                if (it && ChatReaderService.running == null) toast("Switch Vakya on in Accessibility for the floating orb.")
            })
            addView(kit.text("Friday does these only while \"Listen for Hey Friday\" is on (briefings come as a notification when it's off). Never in a call, on silent or in Do not disturb.", 12f, Neo.MUTED))
        })

        // Permissions.
        permsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(kit.card().apply {
            addView(kit.heading("Permissions", "access"))
            addView(kit.text("All optional. Tap one to allow it; Friday tells you when she needs something.", 12.5f, Neo.MUTED))
            addView(permsBox)
        })

        // Reply assistant.
        col.addView(kit.card().apply {
            addView(kit.heading("Reply assistant", "vakya"))
            addView(kit.text("Reply suggestions in your style inside WhatsApp, your texting style, the server, your own Groq key, Friday's voice and privacy.", 13f))
            addView(kit.button("Open reply assistant & settings", primary = false) {
                startActivity(Intent(this@CommandCenterActivity, MainActivity::class.java))
            })
        })
        col.addView(kit.text("Vakya ${BuildConfig.VERSION_NAME} · Friday Mark II", 11f, Neo.MUTED, mono = true).apply { gravity = Gravity.CENTER })
        return scroll
    }

    private fun timedRow(title: String, on: Boolean, getAt: () -> Int, setAt: (Int) -> Unit, setOn: (Boolean) -> Unit): View {
        fun label(m: Int) = "%d:%02d %s".format((m / 60) % 12 + if ((m / 60) % 12 == 0) 12 else 0, m % 60, if (m / 60 < 12) "am" else "pm")
        val row = kit.switchRow(title, "At ${label(getAt())} · tap the time to change it", on) {
            setOn(it)
            Proactive.schedule(this)
        }
        val time = kit.chip(label(getAt())) {}
        time.setOnClickListener {
            val m = getAt()
            TimePickerDialog(this, { _, h, min ->
                setAt(h * 60 + min)
                time.text = label(h * 60 + min)
                Proactive.schedule(this)
            }, m / 60, m % 60, false).show()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(time)
        }
    }

    // ---------- Device tiles ----------

    private fun renderTiles() {
        tiles.removeAllViews()
        val b = status.battery()
        val items = mutableListOf<Triple<String, String, String?>>()  // label, value, toggle target
        items += Triple("Battery", "${b.percent}%" + if (b.charging) " ⚡" else "", "battery_saver_info")
        fun sw(label: String, target: String) {
            val on = status.isOn(target)
            items += Triple(label, when (on) { true -> "On"; false -> "Off"; null -> "—" }, target)
        }
        sw("Wi-Fi", "wifi")
        sw("Bluetooth", "bluetooth")
        sw("Do not disturb", "dnd")
        sw("Battery saver", "battery_saver")
        sw("Auto-rotate", "rotation")
        items += Triple("Network", status.network(), null)
        items += Triple("Volume", status.volumePercent()?.let { "$it%" } ?: "—", null)
        items += Triple("Brightness", status.brightnessPercent()?.let { "$it%" } ?: "—", null)
        items += Triple("Free space", status.freeStorageGb()?.let { "%.0f GB".format(it) } ?: "—", null)
        val audio = status.connectedAudio()
        items += Triple("Connected", if (audio.isEmpty()) "None" else audio.joinToString(), null)
        items += Triple("Ringer", status.ringer().replaceFirstChar { it.uppercase() }, null)
        items.chunked(3).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { (label, value, target) -> row.addView(tile(label, value, target)) }
            repeat(3 - rowItems.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            tiles.addView(row)
        }
    }

    private fun tile(label: String, value: String, target: String?): View {
        val lit = value == "On"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.px(12), kit.px(10), kit.px(12), kit.px(10))
            background = kit.rounded(if (lit) 0x335CE1FF else 0x1A0B2A48, 16, if (lit) 0xAA5CE1FF.toInt() else 0x335CE1FF)
            addView(kit.text(label.uppercase(), 9.5f, Neo.MUTED, bold = true, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(kit.text(value, 15f, if (lit) Neo.CYAN else Neo.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(kit.px(3), kit.px(3), kit.px(3), kit.px(3)) }
            if (target != null && target != "battery_saver_info") {
                setOnClickListener {
                    val on = status.isOn(target) != true
                    toast("${TaskRunner.TOGGLE_NAMES[target]} ${if (on) "on" else "off"}…")
                    SystemControls(this@CommandCenterActivity).toggle(target, on) { out ->
                        main.post {
                            if (!out.ok) toast(out.say ?: "That didn't work.")
                            renderTiles()
                        }
                    }
                }
            }
        }
    }

    // ---------- Up next, notifications ----------

    private fun renderUpNext() {
        upNext.removeAllViews()
        io.execute {
            val events = CalendarReader.upcoming(this, hours = 36, max = 8)
            main.post {
                if (!CalendarReader.allowed(this)) {
                    upNext.addView(kit.text("Calendar: tap to let Friday see it", 13f, Neo.CYAN).apply {
                        setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), Permissions.REQUEST_CALENDAR) }
                    })
                }
                events.forEach { e -> upNext.addView(line("◷", CalendarReader.line(e))) }
                val tasks = memory.openTasks()
                tasks.take(12).forEach { t ->
                    upNext.addView(line("☐", t.text + if (t.due > 0) "  · ${Memory.stamp(t.due)}" else "").apply {
                        setOnClickListener {
                            AlertDialog.Builder(this@CommandCenterActivity)
                                .setMessage(t.text)
                                .setPositiveButton("Done") { _, _ -> memory.completeTask(t.text); renderUpNext() }
                                .setNeutralButton("Delete") { _, _ -> memory.deleteTask(t.id); renderUpNext() }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    })
                }
                if (events.isEmpty() && tasks.isEmpty()) upNext.addView(kit.text("Nothing coming up. Say \"Friday, remind me at 8 to call Amma\".", 13f, Neo.MUTED))
            }
        }
    }

    private fun renderNotifications() {
        notes.removeAllViews()
        val items = NotificationLog.get(this).recent(hours = 24, max = 6)
        if (items.isEmpty()) {
            notes.addView(kit.text(
                if (com.replybot.jarvis.NotificationWatcher.allowed(this)) "Nothing new." else "Allow notification access (below) and Friday can sum up your messages.",
                13f, Neo.MUTED,
            ))
        }
        items.forEach { n ->
            notes.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, kit.px(4), 0, kit.px(4))
                addView(kit.text("${n.who.ifBlank { n.app }} · ${n.app} · ${NotificationLog.whenSaid(n.time)}", 11.5f, Neo.CYAN, mono = true).apply { setPadding(0, 0, 0, 0) })
                addView(kit.text(n.text, 13.5f, Neo.TEXT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 0, 0, 0) })
            })
        }
        if (items.isNotEmpty()) notes.addView(kit.button("Summarise them, Friday", primary = false) { openFriday(JarvisService.ACTION_ASK, "Summarise my important notifications") })
    }

    private fun line(mark: String, text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, kit.px(4), 0, kit.px(4))
        addView(kit.text(mark, 14f, Neo.CYAN), LinearLayout.LayoutParams(kit.px(24), WRAP_CONTENT))
        addView(kit.text(text, 14f, Neo.TEXT), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    // ---------- Memory ----------

    private fun renderMemory() {
        memoryBox.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        memory.overview().forEach { (name, n) ->
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(kit.text("$n", 18f, Neo.CYAN, bold = true).apply { gravity = Gravity.CENTER })
                addView(kit.text(name, 10f, Neo.MUTED, mono = true).apply { gravity = Gravity.CENTER; maxLines = 2 })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        memoryBox.addView(row)
        factsBox.removeAllViews()
        val facts = store.profile
        if (facts.isEmpty()) factsBox.addView(kit.text("Nothing yet. Tell her: \"Friday, remember my name is Skanda\".", 13f, Neo.MUTED))
        facts.forEach { fact ->
            factsBox.addView(kit.text("• $fact", 14f, Neo.TEXT).apply {
                setOnLongClickListener {
                    AlertDialog.Builder(this@CommandCenterActivity)
                        .setMessage("Forget \"$fact\"?")
                        .setPositiveButton("Forget") { _, _ -> store.updateProfile(emptyList(), listOf(fact)); renderMemory() }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            })
        }
    }

    private fun renderSearch(query: String) {
        searchResults.removeAllViews()
        if (query.isBlank()) return
        io.execute {
            val found = memory.search(query)
            main.post {
                if (found.isEmpty()) searchResults.addView(kit.text("Nothing matches \"$query\".", 13f, Neo.MUTED))
                found.forEach { searchResults.addView(kit.text("› $it", 13.5f, Neo.TEXT)) }
            }
        }
    }

    private fun exportMemory() {
        io.execute {
            val where = memory.exportToDownloads()
            main.post { toast(if (where != null) "Saved to $where" else "Couldn't save the export.") }
        }
    }

    private fun deleteAllMemory() {
        AlertDialog.Builder(this)
            .setTitle("Delete everything Friday remembers?")
            .setMessage("About you, notes, tasks, routines, past conversations and the notes about each chat. Your texting style and reply bank stay. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                memory.deleteAll()
                NotificationLog.get(this).clear()
                renderAll()
                toast("Friday's memory is empty.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Routines ----------

    private fun renderRoutines() {
        routinesBox.removeAllViews()
        val all = memory.routines()
        if (all.isEmpty()) routinesBox.addView(kit.text("No routines yet.", 13f, Neo.MUTED))
        all.forEach { r ->
            routinesBox.addView(kit.inset().apply {
                addView(kit.text("“${r.name}”", 14.5f, Neo.CYAN, bold = true))
                addView(kit.text(r.steps, 13f, Neo.BODY))
                setOnClickListener { editRoutine(r) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@CommandCenterActivity)
                        .setMessage("Delete the routine \"${r.name}\"?")
                        .setPositiveButton("Delete") { _, _ -> memory.deleteRoutine(r.name); renderRoutines() }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            })
        }
    }

    private fun editRoutine(r: Memory.Routine?) {
        val name = kit.input("Name, e.g. going to sleep").apply { setText(r?.name.orEmpty()) }
        val steps = kit.input("What to do, e.g. do not disturb on, brightness 10%, alarm 6:30", multiLine = true).apply {
            setText(r?.steps.orEmpty())
            minLines = 3
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.px(20), kit.px(8), kit.px(20), 0)
            addView(name)
            addView(steps)
        }
        AlertDialog.Builder(this)
            .setTitle(if (r == null) "New routine" else "Edit routine")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                if (r != null && r.name != name.text.toString().trim().lowercase()) memory.deleteRoutine(r.name)
                memory.saveRoutine(name.text.toString(), steps.text.toString())
                renderRoutines()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Permissions ----------

    private fun renderPermissions() {
        permsBox.removeAllViews()
        Permissions.ALL.forEach { p ->
            val ok = p.granted(this)
            permsBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, kit.px(8), 0, kit.px(8))
                addView(kit.dot(ok))
                addView(LinearLayout(this@CommandCenterActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(kit.text(p.name, 14.5f, Neo.TEXT).apply { setPadding(0, 0, 0, 0) })
                    addView(kit.text(p.why, 12f, Neo.MUTED).apply { setPadding(0, 0, 0, 0) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(kit.text(if (ok) "ON" else "ALLOW ›", 11f, if (ok) Neo.OK else Neo.CYAN, bold = true, mono = true))
                setOnClickListener { if (!ok) p.request(this@CommandCenterActivity) else toast("${p.name} is allowed. Change it in Android's settings.") }
            })
        }
    }

    // ---------- Friday ----------

    /** Talk, a briefing, or a question: Friday answers in her card at the top; no screen opens. */
    private fun openFriday(action: String? = null, text: String? = null, briefing: String? = null) {
        if (!micAllowed()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), Permissions.REQUEST_MIC)
            return
        }
        if (ChatReaderService.running == null) toast("Switch Vakya on in Accessibility to see Friday at the top of the screen.")
        if (briefing != null) JarvisService.startBriefingNow(this, briefing)
        else JarvisService.command(this, action ?: JarvisService.ACTION_TALK, text)
    }

    private fun turnOn() {
        if (!micAllowed()) {
            turnOnAfterMic = true
            val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(wanted.toTypedArray(), Permissions.REQUEST_MIC)
            return
        }
        store.jarvisOn = true
        JarvisService.start(this)
        toast("Friday is listening. Say \"Hey Friday\".")
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.REQUEST_MIC && turnOnAfterMic) {
            turnOnAfterMic = false
            if (micAllowed()) turnOn() else {
                listenSwitch.isChecked = false
                toast("Friday needs the microphone to hear you.")
            }
        }
        renderAll()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
