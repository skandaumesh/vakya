package com.replybot.jarvis

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.replybot.agent.Outcome
import com.replybot.data.DeviceTask
import com.replybot.data.Store
import com.replybot.device.DeviceStatus
import com.replybot.device.SystemControls
import com.replybot.memory.Memory
import com.replybot.service.ChatReaderService

/**
 * Friday's hands: does a phone task the server picked out of what you said (an alarm, a call, a
 * switch, a tap on screen...). Everything here runs on the phone; contacts are looked up here and
 * never sent anywhere.
 *
 * [run] reports an [Outcome] once the task is done (some, like switches, take a moment): ok only
 * when it happened, with what Friday should say instead of the server's line when the phone knows
 * better (it failed, or it measured something like the battery).
 */
class DeviceActions(private val context: Context) {

    private val store = Store.get(context)
    private val memory = Memory.get(context)
    private val controls = SystemControls(context)
    private val status = DeviceStatus(context)
    private val main = Handler(Looper.getMainLooper())

    /** The app the last task opened, to check it really came to the front. */
    var opened: String? = null
        private set

    fun run(task: DeviceTask, done: (Outcome) -> Unit) {
        opened = null
        try {
            when (task.kind) {
                "toggle" -> controls.toggle(task.target, task.on, done)
                "brightness" -> controls.brightness(task.value, task.on, done)
                "message" -> message(task, done)
                "tap" -> tap(task.text, done)
                else -> done(runNow(task))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "friday task ${task.kind}: ${e.javaClass.simpleName}")
            done(Outcome.fail("Android didn't let me do that, boss.", "not allowed by Android"))
        } catch (e: Exception) {
            Log.w(TAG, "friday task ${task.kind} failed: ${e.javaClass.simpleName}")
            done(Outcome.fail("Something went wrong doing that, boss.", e.javaClass.simpleName))
        }
    }

    private fun runNow(task: DeviceTask): Outcome = when (task.kind) {
        "alarm" -> alarm(task)
        "timer" -> timer(task)
        "open_app" -> openApp(task.app)
        "call" -> call(task.contact)
        "play" -> play(task)
        "navigate" -> navigate(task.text)
        "search" -> search(task.text)
        "flashlight" -> flashlight(task.on)
        "battery" -> battery()
        "phone" -> phone(task.target)
        "settings" -> if (controls.openSettings(task.target)) Outcome.OK else Outcome.fail("I couldn't open settings, boss.")
        "volume" -> controls.volume(task.value, task.target, task.on)
        "note_add" -> if (task.text.isBlank()) Outcome.fail("What should I note down, boss?") else { store.addNote(task.text); Outcome(true, null, "noted") }
        "notes_clear" -> { store.clearNotes(); Outcome.OK }
        "notes_read" -> Outcome.OK
        "task_add" -> memory.addTask(task.text, Memory.parseTime(task.time))?.let { Outcome(true, null, "in your tasks") }
            ?: Outcome.fail("What's the task, boss?")
        "task_done" -> memory.completeTask(task.text)?.let { Outcome(true, null, "marked done: ${it.text}") }
            ?: Outcome.fail("I couldn't find that in your tasks, boss.", "no matching task")
        "calendar_add" -> calendarAdd(task)
        "files" -> files(task.target)
        "routine_save" -> if (task.label.isBlank() || task.text.isBlank()) Outcome.fail("What should the routine be called, boss?")
            else { memory.saveRoutine(task.label, task.text); Outcome(true, null, "routine saved") }
        "type" -> typeText(task.text)
        "scroll" -> scroll(task)
        "media" -> media(task.target)
        "send" -> if (ChatReaderService.running?.jarvisSend() == true) Outcome(true, null, "sent")
            else Outcome.fail("I couldn't find the send button, boss. Tap it for me?", "no send button on screen")
        else -> Outcome.fail("I can't do that one yet, boss.", "unknown task ${task.kind}")
    }

    // ---------- Clock ----------

    private fun alarm(task: DeviceTask): Outcome {
        val (h, m) = task.time.split(":").mapNotNull { it.trim().toIntOrNull() }.let {
            if (it.size == 2 && it[0] in 0..23 && it[1] in 0..59) it[0] to it[1] else return Outcome.fail("What time, boss?", "no time given")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (task.label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, task.label)
        if (!launch(intent, track = false)) return Outcome.fail("I couldn't reach your clock app, boss.", "no clock app")
        // "Remind me at 8 to call Amma": also a task, so "what do I need to do?" and briefings know it.
        if (task.label.isNotBlank()) memory.addTask(task.label, Memory.nextAt(h, m))
        return Outcome(true, null, "set in your clock app")
    }

    private fun timer(task: DeviceTask): Outcome {
        if (task.seconds !in 1..86_400) return Outcome.fail("For how long, boss?", "no length given")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, task.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (task.label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, task.label)
        if (!launch(intent, track = false)) return Outcome.fail("I couldn't reach your clock app, boss.", "no clock app")
        if (task.label.isNotBlank()) memory.addTask(task.label, System.currentTimeMillis() + task.seconds * 1000L)
        return Outcome(true, null, "running in your clock app")
    }

    // ---------- Apps ----------

    private fun openApp(name: String): Outcome {
        val wanted = norm(name)
        val special = when (wanted) {
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "phone", "dialer", "dialpad" -> Intent(Intent.ACTION_DIAL)
            else -> null
        }
        if (special != null) return if (launch(special)) Outcome.OK else Outcome.fail("I couldn't open $name, boss.")
        val pkg = findApp(name) ?: return Outcome.fail("I couldn't find $name on your phone, boss.", "not installed")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return Outcome.fail("I couldn't open $name, boss.")
        return if (launch(launchIntent)) Outcome.OK else Outcome.fail("I couldn't open $name, boss.")
    }

    /** The installed app whose name best matches [name], by package. */
    private fun findApp(name: String): String? {
        val wanted = norm(name)
        if (wanted.isEmpty()) return null
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val best = apps.minByOrNull { app ->
            val label = norm(app.loadLabel(pm).toString())
            when {
                label == wanted -> 0
                label.startsWith(wanted) || wanted.startsWith(label) -> 1
                wanted in label -> 2
                else -> 3 + editDistance(label, wanted)
            }
        } ?: return null
        val label = norm(best.loadLabel(pm).toString())
        if (wanted !in label && !label.startsWith(wanted) && !wanted.startsWith(label) && editDistance(label, wanted) > 2) return null
        return best.activityInfo.packageName
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun editDistance(a: String, b: String): Int {
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

    // ---------- People ----------

    private data class Person(val name: String, val number: String)

    /** A number as said, or the best match in the phone's contacts. */
    private fun person(said: String): Person? {
        val digits = said.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 6) return Person(said, digits)
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(said))
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            var found: Person? = null
            while (c.moveToNext()) {
                val p = Person(c.getString(0), c.getString(1))
                if (found == null || c.getInt(2) == 1) found = p
            }
            return found
        }
        return null
    }

    private fun noContact(said: String): Outcome =
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Outcome.fail("I need your contacts for that, boss. Tap Allow contacts in Vakya.", "no contacts permission")
        } else {
            Outcome.fail("I couldn't find $said in your contacts, boss.", "no contact named $said")
        }

    private fun call(said: String): Outcome {
        val p = person(said) ?: return noContact(said)
        val direct = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number)))
        if (!launch(intent, track = false)) return Outcome.fail("I couldn't start the call, boss.")
        return Outcome.info(if (direct) "Calling ${p.name}, boss." else "Dialing ${p.name}, boss. Just tap call.")
    }

    /** In-country numbers for WhatsApp and Telegram links: country code, no "+" (India by default). */
    private fun international(number: String): String {
        var n = number.filter { it.isDigit() }
        if (n.startsWith("00")) n = n.drop(2)
        if (n.startsWith("0")) n = n.trimStart('0')
        if (n.length == 10) n = "91$n"
        return n
    }

    private fun message(task: DeviceTask, done: (Outcome) -> Unit) {
        val p = person(task.contact) ?: return done(noContact(task.contact))
        val ready = if (task.text.isBlank()) "Here's your chat with ${p.name}, boss." else "Your message to ${p.name} is ready, boss. Shall I send it?"
        when (task.app.lowercase()) {
            "sms", "text", "messages" -> {
                val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", task.text)
                done(if (launch(sms)) Outcome.info(ready) else Outcome.fail("I couldn't open Messages, boss."))
            }
            "telegram" -> {
                val tg = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=" + international(p.number))).setPackage(TELEGRAM)
                if (!launch(tg)) return done(Outcome.fail("I couldn't open Telegram, boss.", "Telegram not installed"))
                if (task.text.isBlank()) return done(Outcome.info(ready))
                // Telegram has no "start with this text" link: type it once the chat is open.
                main.postDelayed({
                    val typed = ChatReaderService.running?.screen()?.type(task.text) == true
                    done(if (typed) Outcome.info(ready) else Outcome.fail("I opened ${p.name}'s chat on Telegram, boss, but couldn't type in it.", "couldn't type in Telegram"))
                }, 2200)
            }
            else -> {
                val number = international(p.number)
                // No text: just open the chat ("open Rahul's chat").
                val url = Uri.parse("https://wa.me/$number" + if (task.text.isBlank()) "" else "?text=" + Uri.encode(task.text))
                val ok = listOf("com.whatsapp", "com.whatsapp.w4b", null).any { pkg ->
                    launch(Intent(Intent.ACTION_VIEW, url).also { if (pkg != null) it.setPackage(pkg) })
                }
                done(if (ok) Outcome.info(ready) else Outcome.fail("I couldn't open WhatsApp, boss."))
            }
        }
    }

    // ---------- Media, maps, search, files ----------

    /**
     * Opens the app's search for [DeviceTask.text], then taps the top matching song or video (apps
     * only open their search screen from a link; "play from search" isn't honoured by all of them).
     */
    private fun play(task: DeviceTask): Outcome {
        // "Kesariya song" -> "Kesariya": the app's search knows it's a song.
        val q = task.text.replace(Regex("\\b(the )?(song|songs|track)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim().ifBlank { task.text.ifBlank { "top hits" } }
        val app = task.app.lowercase()
        fun has(pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg) != null
        val (pkg, uri) = when {
            app == "youtube" || (app.isBlank() && !has(SPOTIFY) && !has(YT_MUSIC)) ->
                YOUTUBE to "https://www.youtube.com/results?search_query=" + Uri.encode(q)
            app == "youtube music" || (app.isBlank() && !has(SPOTIFY)) ->
                YT_MUSIC to "https://music.youtube.com/search?q=" + Uri.encode(q)
            else -> SPOTIFY to "spotify:search:" + Uri.encode(q)
        }
        if (!has(pkg)) return Outcome.fail("That app isn't on your phone, boss.", "not installed")
        if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg))) return Outcome.fail("I couldn't open it, boss.")
        ChatReaderService.running?.jarvisTapResult(q, pkg)
        return Outcome(true, null, "searching ${q.take(30)}")
    }

    /** The music or video already playing: works with the phone locked, like headphone buttons. */
    private fun media(target: String): Outcome {
        val code = when (target) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return Outcome.fail("Play, pause, next or previous, boss?")
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return Outcome.fail("I couldn't reach the music, boss.")
        val wasPlaying = audio.isMusicActive
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        if (target == "pause" && !wasPlaying) return Outcome(true, null, "nothing was playing")
        return Outcome.OK
    }

    private fun scroll(task: DeviceTask): Outcome {
        val a11y = ChatReaderService.running ?: return Outcome.fail("Switch Vakya on in Accessibility first, boss.", "accessibility is off")
        return when {
            task.target == "stop" -> { a11y.stopAutoScroll(); Outcome.OK }
            task.seconds > 0 -> { a11y.jarvisAutoScroll(task.seconds.coerceIn(3, 120)); Outcome.OK }
            a11y.jarvisScroll(task.on) -> Outcome.OK
            else -> Outcome.fail("I couldn't scroll here, boss.")
        }
    }

    private fun navigate(place: String): Outcome {
        val ok = launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))) ||
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        return if (ok) Outcome.OK else Outcome.fail("I couldn't open maps, boss.")
    }

    private fun search(query: String): Outcome {
        // Google itself: on some phones the generic "web search" goes to the maker's own search app.
        val ok = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))) ||
            launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
        return if (ok) Outcome.OK else Outcome.fail("I couldn't open a browser, boss.")
    }

    private fun files(target: String): Outcome {
        val ok = when (target) {
            "downloads" -> launch(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            "photos" -> launch(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) ||
                listOf("Gallery", "Photos", "Album").any { name -> findApp(name)?.let { launchPackage(it) } == true }
            "music" -> launch(Intent(MediaStore.INTENT_ACTION_MUSIC_PLAYER)) || listOf("Music", "Files").any { name -> findApp(name)?.let { launchPackage(it) } == true }
            else -> listOf("Files", "File Manager", "My Files").any { name -> findApp(name)?.let { launchPackage(it) } == true } ||
                launch(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        }
        return if (ok) Outcome.OK else Outcome.fail("I couldn't open your files, boss.")
    }

    private fun launchPackage(pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { launch(it) } == true

    /** Opens the calendar with the event filled in: the user saves it (Friday never writes a calendar herself). */
    private fun calendarAdd(task: DeviceTask): Outcome {
        val begin = Memory.parseTime(task.time)
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, task.text.ifBlank { "Event" })
        if (begin > 0) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + (if (task.seconds > 0) task.seconds else 3600) * 1000L)
        }
        if (!launch(intent)) return Outcome.fail("I couldn't open your calendar, boss.", "no calendar app")
        return Outcome.info("It's in your calendar, boss. Tap save to keep it.")
    }

    // ---------- The screen ----------

    private fun tap(label: String, done: (Outcome) -> Unit) {
        val a11y = ChatReaderService.running ?: return done(Outcome.fail("Switch Vakya on in Accessibility first, boss.", "accessibility is off"))
        // A moment for the screen to settle (a step before may have just opened it).
        main.postDelayed({
            val tapped = a11y.screen().tap(label)
            done(if (tapped != null) Outcome(true, null, "tapped “$tapped”")
                 else Outcome.fail("I couldn't find $label on the screen, boss.", "'$label' isn't on screen"))
        }, 250)
    }

    private fun typeText(text: String): Outcome {
        val a11y = ChatReaderService.running ?: return Outcome.fail("Switch Vakya on in Accessibility first, boss.", "accessibility is off")
        return if (a11y.screen().type(text)) Outcome.OK else Outcome.fail("I couldn't find a box to type in, boss.", "no text box on screen")
    }

    // ---------- The phone itself ----------

    private fun flashlight(on: Boolean): Outcome {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Outcome.fail("This phone's flashlight isn't available to me, boss.")
        cameras.setTorchMode(id, on)
        return Outcome.OK
    }

    private fun battery(): Outcome {
        val b = status.battery()
        val extra = when {
            b.health == "overheating" || (b.tempC ?: 0f) >= 42f -> " It's running hot, so give it a break."
            !b.charging && b.percent in 0..15 -> " Want me to turn on battery saver?"
            else -> ""
        }
        return Outcome.info((if (b.charging) "You're at ${b.percent} percent, boss, and charging." else "You're at ${b.percent} percent, boss.") + extra)
    }

    private fun phone(target: String): Outcome {
        val a11y = ChatReaderService.running ?: return Outcome.fail("Switch Vakya on in Accessibility first, boss.", "accessibility is off")
        if (target == "last_app") return if (a11y.screen().lastApp()) Outcome.OK else Outcome.fail("I couldn't switch apps, boss.")
        val action = when (target) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return Outcome.fail("This phone is too old for that, boss.")
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return Outcome.fail("This phone is too old for that, boss.")
            else -> return Outcome.fail("I'm not sure how to do that, boss.")
        }
        return if (a11y.performGlobalAction(action)) Outcome.OK else Outcome.fail("That didn't work, boss.")
    }

    /**
     * Opens [intent] from the accessibility service when it's on: Android lets apps with one start
     * screens from the background, which a plain service can't. [track]: remember which app it
     * opened, to check it came to the front.
     */
    private fun launch(intent: Intent, track: Boolean = true): Boolean {
        val from: Context = ChatReaderService.running ?: context
        return try {
            from.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (track) opened = intent.`package` ?: intent.resolveActivity(context.packageManager)?.packageName
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private companion object {
        const val TAG = "Vakya"
        const val SPOTIFY = "com.spotify.music"
        const val YOUTUBE = "com.google.android.youtube"
        const val YT_MUSIC = "com.google.android.apps.youtube.music"
        const val TELEGRAM = "org.telegram.messenger"
    }
}
