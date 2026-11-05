package com.replybot.jarvis

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.replybot.R
import com.replybot.agent.FridayBus
import com.replybot.agent.TaskRunner
import com.replybot.automation.Proactive
import com.replybot.data.DeviceTask
import com.replybot.data.JarvisContext
import com.replybot.data.JarvisResult
import com.replybot.data.ReplyApi
import com.replybot.data.Store
import com.replybot.device.CalendarReader
import com.replybot.device.DeviceStatus
import com.replybot.memory.Memory
import com.replybot.service.ChatReaderService
import com.replybot.ui.CommandCenterActivity
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * "Hey Friday". Listens on the phone for the wake word (no audio leaves the phone until it's
 * heard), records the command until you stop talking, sends it to the Vakya server, and does
 * what it says: writes into the open chat, shows reply options, answers aloud, or runs the steps
 * of a plan on the phone (checking each one), looking at the screen first when she needs to.
 *
 * A foreground service with a notification ("Friday is listening"), started from the Vakya app
 * while "Listen for Hey Friday" is on. She sends a message only after you say yes, asks before
 * running a routine, and can be interrupted by saying "Friday" while she talks, or with stop.
 */
class JarvisService : Service() {

    companion object {
        const val ACTION_START = "com.replybot.jarvis.START"
        const val ACTION_STOP = "com.replybot.jarvis.STOP"
        /** Skip the wake word: listen for a command right now. */
        const val ACTION_TALK = "com.replybot.jarvis.TALK"
        /** Voice check: record the next 8 s from the mic ("Hey Friday" a few times), count the wakes, save the clip. */
        const val ACTION_CHECK = "com.replybot.jarvis.CHECK"
        /** Self-test: play a WAV from the app's own files folder instead of the microphone. */
        const val ACTION_TEST = "com.replybot.jarvis.TEST"
        /** Look at the screen and explain it (the floating orb, held). */
        const val ACTION_LOOK = "com.replybot.jarvis.LOOK"
        /** A typed command ([EXTRA_TEXT]), from the Friday screen. */
        const val ACTION_ASK = "com.replybot.jarvis.ASK"
        /** A spoken briefing ([EXTRA_KIND]: morning, evening or day). */
        const val ACTION_BRIEFING = "com.replybot.jarvis.BRIEFING"
        /** Stop talking and doing, now. */
        const val ACTION_INTERRUPT = "com.replybot.jarvis.INTERRUPT"
        const val EXTRA_FILE = "file"
        const val EXTRA_TEXT = "text"
        const val EXTRA_KIND = "kind"

        private const val TAG = "Vakya"
        private const val CHANNEL = "jarvis"
        private const val ALERTS = "friday_alerts"
        private const val NOTIFICATION_ID = 7

        private const val MAX_COMMAND_CHUNKS = 190  // 15 s
        private const val NO_SPEECH_CHUNKS = 75     // 6 s and nothing said: give up
        private const val FOLLOW_UP_CHUNKS = 100    // after an answer, 8 s to say something more
        // The quiet that ends a command: long enough that a pause to think mid-sentence
        // ("message Amma that... I'll be late") doesn't cut it off.
        private const val END_SILENCE_CHUNKS = 16   // 1.3 s once a few words are in
        private const val END_SILENCE_EARLY = 22    // 1.8 s while only a word or two has been said
        private const val GREET_WAIT_CHUNKS = 10    // 0.8 s of quiet after "Hey Friday": greet, then listen
        const val GREETING = "How can I help you, boss?"
        private const val CHIME_CHUNKS = 2          // don't record the "listening" chime
        private const val PRE_ROLL_CHUNKS = 20      // 1.6 s before the wake word goes with the command
        private const val PENDING_FOR_MS = 2 * 60_000L

        @Volatile
        var running = false
            private set

        @Volatile private var instance: JarvisService? = null

        /** The unlock prompt Friday asked for ([com.replybot.ui.UnlockActivity]) was answered. */
        fun unlocked(ok: Boolean) {
            instance?.let { it.main.post { it.afterUnlock(ok) } }
        }

        /** New personal messages (from [NotificationWatcher]): say so, if the user wants that. */
        fun announce(items: List<NotificationLog.Item>) {
            instance?.let { it.main.post { it.queueAnnouncement(items) } }
        }

        /** Stop talking and doing (stop buttons). */
        fun interrupt() {
            instance?.let { it.main.post { it.interruptAll() } }
        }

        /** A scheduled briefing: true if Friday is on and will say it now. */
        fun briefing(kind: String): Boolean {
            val s = instance ?: return false
            s.main.post { s.startBriefing(kind, byUser = false) }
            return true
        }

        fun start(context: Context, action: String = ACTION_START, file: String? = null) {
            val intent = Intent(context, JarvisService::class.java).setAction(action)
            if (file != null) intent.putExtra(EXTRA_FILE, file)
            context.startForegroundService(intent)
        }

        /**
         * Talk / look / ask from anywhere (floating orb, the card at the top, Command Center). Goes
         * straight to the running service, or starts her for it. Friday only ever shows as the card
         * at the top of the screen; no screen of her own opens.
         */
        fun command(context: Context, action: String, text: String? = null) {
            val s = instance
            if (s != null && running) {
                s.main.post {
                    when (action) {
                        ACTION_TALK -> s.talkNow()
                        ACTION_LOOK -> s.lookNow()
                        ACTION_ASK -> text?.let(s::typed)
                        ACTION_INTERRUPT -> s.interruptAll()
                    }
                }
                return
            }
            val intent = Intent(context, JarvisService::class.java).setAction(action)
            text?.let { intent.putExtra(EXTRA_TEXT, it) }
            runCatching { context.startForegroundService(intent) }.onFailure {
                // Android only lets the microphone start while Vakya is in front.
                android.widget.Toast.makeText(context, "Open Vakya and turn on Listen for Hey Friday.", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        /** A briefing asked for now (Command Center): starts Friday if needed. */
        fun startBriefingNow(context: Context, kind: String) {
            val intent = Intent(context, JarvisService::class.java).setAction(ACTION_BRIEFING).putExtra(EXTRA_KIND, kind)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }

    // WAKE: waiting for "Hey Friday". COMMAND: recording what's said. BUSY: thinking or working.
    // SPEAKING: talking, while still listening for "Friday" to be interrupted.
    private enum class State { WAKE, COMMAND, BUSY, SPEAKING }

    /** One request to the server, and what kind it was. */
    private data class Ask(
        val text: String? = null,
        val looked: Boolean = false,
        val failed: List<String> = emptyList(),
        val briefing: String? = null,
    )

    /** Steps waiting for the user's yes, and the question Friday asked. */
    private data class Pending(val steps: List<DeviceTask>, val question: String, val at: Long)

    /** Steps waiting for the phone to be unlocked. */
    private data class UnlockWait(val steps: List<DeviceTask>, val say: String, val heard: String, val at: Long)

    @Volatile private var state = State.WAKE
    @Volatile private var stopping = false
    @Volatile private var testing = false
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var wake: FridayWake? = null
    private var voice: JarvisVoice? = null
    private var mic: Thread? = null
    private var hideToken = 0
    private var speakToken = 0
    /** Earlier exchanges in this conversation (heard, said), for follow-ups. */
    private val history = ArrayDeque<Pair<String, String>>()
    /** Listening for a follow-up (no wake word needed) rather than a fresh command. */
    private var followUp = false
    /** The last answer ended the conversation ("thanks"), or failed. */
    private var conversationOver = false
    private val device by lazy { DeviceActions(this) }
    private val runner by lazy { TaskRunner(device) }
    private val status by lazy { DeviceStatus(this) }
    private var pending: Pending? = null

    // The command being recorded (audio thread only, after beginCommand sets it up).
    private val command = ShortArray((MAX_COMMAND_CHUNKS + PRE_ROLL_CHUNKS) * FridayWake.CHUNK)
    // The last moments before the wake word (mic thread), so "Hey Friday play Kesariya" said in one
    // breath is heard whole, and the server can tell "Friday, ..." from "see you on Friday".
    private val preRoll = ArrayDeque<ShortArray>()
    /** This command began with the wake word (not "Talk now" or a follow-up). */
    private var fromWake = false
    private var unlockWait: UnlockWait? = null
    private var commandLength = 0
    private var commandChunks = 0
    private var silentChunks = 0
    private var skipChunks = 0
    private var heardSpeech = false
    /** How much of the command was speech (80 ms chunks): pauses are allowed longer early on. */
    private var spokenChunks = 0
    /** Just woken by "Hey Friday": greet if nothing more is said in a moment. */
    private var greetPending = false
    /** Friday has just said the greeting: the command comes next. */
    private var afterGreeting = false
    /** Background loudness, learnt while waiting for the wake word. */
    private var noise = 300.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store.get(this).jarvisOn = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val test = intent?.action == ACTION_TEST
        if (!goForeground() || !setUp(withMic = !test)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CHECK -> main.post { startCheck() }
            ACTION_TALK -> main.post { talkNow() }
            ACTION_LOOK -> main.post { lookNow() }
            ACTION_ASK -> intent.getStringExtra(EXTRA_TEXT)?.let { t -> main.post { typed(t) } }
            ACTION_BRIEFING -> main.post { startBriefing(intent.getStringExtra(EXTRA_KIND) ?: "day") }
            ACTION_INTERRUPT -> main.post { interruptAll() }
            ACTION_TEST -> intent.getStringExtra(EXTRA_FILE)?.let(::playTestFile)
        }
        return START_STICKY
    }

    // Unlocked any way (the prompt Friday showed, or as usual): finish what was waiting for it.
    private val unlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = afterUnlock(true)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerReceiver(unlockedReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        main.postDelayed(proactiveTick, 20_000)
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(unlockedReceiver) }
        stopping = true
        running = false
        runner.cancel()
        mic?.join(500)
        main.removeCallbacksAndMessages(null)
        ChatReaderService.running?.jarvisHudHide()
        FridayBus.update { copy(phase = FridayBus.Phase.OFF, steps = emptyList(), seeing = false, pending = null) }
        voice?.shutdown()
        io.execute { wake?.close() }
        io.shutdown()
        super.onDestroy()
    }

    // ---------- Setup ----------

    private fun goForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hey Friday", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Friday listens for \"Hey Friday\""
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERTS, "Friday's reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders, upcoming events and low battery"
            },
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_jarvis)
        fun service(code: Int, action: String) = PendingIntent.getService(
            this, code, Intent(this, JarvisService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Friday is listening")
            .setContentText("Say \"Hey Friday\", then what you need")
            .setOngoing(true)
            // Tapping the notification: talk to Friday (her card shows at the top; nothing opens).
            .setContentIntent(service(0, ACTION_TALK))
            .addAction(Notification.Action.Builder(icon, "Talk now", service(1, ACTION_TALK)).build())
            .addAction(Notification.Action.Builder(icon, "Turn off", service(2, ACTION_STOP)).build())
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Android won't start a microphone service from the background (say, after the
            // system restarted it): it starts again next time Vakya is opened.
            Log.w(TAG, "jarvis can't listen now: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun setUp(withMic: Boolean): Boolean {
        if (wake == null) {
            wake = try {
                FridayWake(assets)
            } catch (e: Exception) {
                Log.e(TAG, "friday: wake word model failed to load", e)
                return false
            }
            voice = JarvisVoice(this).also { it.prepare(GREETING) }
            running = true
            phase(FridayBus.Phase.IDLE)
        }
        if (withMic && mic == null) {
            stopping = false
            mic = Thread(::listen, "jarvis-mic").apply { start() }
        }
        return true
    }

    // ---------- What every Friday view shows ----------

    private fun phase(p: FridayBus.Phase, seeing: Boolean = false) {
        FridayBus.update { copy(phase = p, seeing = seeing, level = if (p == FridayBus.Phase.LISTENING) level else 0f) }
        ChatReaderService.running?.jarvisPhase(p, seeing)
    }

    private fun hud(state: String, caption: String? = null, thinking: Boolean = false) {
        ChatReaderService.running?.jarvisHud(state, caption, thinking)
        ChatReaderService.running?.jarvisPhase(FridayBus.state.phase, FridayBus.state.seeing)
    }

    // ---------- Listening ----------

    private fun listen() {
        val rate = FridayWake.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FridayWake.CHUNK * 8),
            )
        } catch (e: SecurityException) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "jarvis: microphone unavailable")
            rec?.release()
            main.post { stopSelf() }
            return
        }
        val chunk = ShortArray(FridayWake.CHUNK)
        rec.startRecording()
        Log.i(TAG, "jarvis: listening")
        try {
            while (!stopping) {
                var n = 0
                while (n < chunk.size && !stopping) {
                    val read = rec.read(chunk, n, chunk.size - n)
                    if (read <= 0) break
                    n += read
                }
                if (n < chunk.size) {
                    Thread.sleep(100)  // the mic is busy (a call, a voice note): try again shortly
                    continue
                }
                if (!testing) onChunk(chunk)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    // ---------- New messages ----------

    private val waitingMessages = mutableListOf<NotificationLog.Item>()
    private val announceNow = Runnable { announceWaiting() }

    private fun queueAnnouncement(items: List<NotificationLog.Item>) {
        if (!Store.get(this).announceMessages) return
        waitingMessages += items
        // A burst of messages gets one announcement.
        main.removeCallbacks(announceNow)
        main.postDelayed(announceNow, 2500)
    }

    /** "Message from Rahul on WhatsApp. Shall I read it?", then listen for the answer, no wake word needed. */
    private fun announceWaiting() {
        val items = waitingMessages.toList()
        waitingMessages.clear()
        if (items.isEmpty() || state != State.WAKE || mic == null || check != null) return
        if (!quietIsFine()) return
        // Already looking at it.
        if (ChatReaderService.running?.foregroundPackage() in items.map { it.pkg }) return
        val people = items.map { it.who }.distinct()
        val app = items.map { it.app }.distinct().singleOrNull()
        val on = app?.let { " on $it" }.orEmpty()
        val say = when {
            people.size == 1 && items.size == 1 -> "Message from ${people[0]}$on. Shall I read it?"
            people.size == 1 -> "${items.size} messages from ${people[0]}$on. Shall I read them?"
            people.size == 2 -> "Messages from ${people[0]} and ${people[1]}. Shall I read them?"
            else -> "${items.size} new messages, boss. Shall I read them?"
        }
        Log.i(TAG, "friday: announcing ${items.size} messages")
        proactiveSay(say, followUp = true)
    }

    /** Friday speaks up on her own (a message came, a reminder is due), then listens for an answer. */
    private fun proactiveSay(say: String, followUp: Boolean, pendingSteps: List<DeviceTask> = emptyList()) {
        state = State.BUSY
        conversationOver = !followUp
        history.clear()
        history.addLast("" to say)
        pending = if (pendingSteps.isNotEmpty()) Pending(pendingSteps, say, SystemClock.elapsedRealtime()) else null
        val question = pending?.question
        FridayBus.update { copy(lines = emptyList(), steps = emptyList(), pending = question) }
        FridayBus.line(false, say)
        hideToken++
        hud("Friday", say)
        speak(say)
    }

    /** Not when the phone is on silent, in Do Not Disturb, or in a call. */
    private fun quietIsFine(): Boolean {
        val audio = getSystemService(AudioManager::class.java) ?: return false
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.mode != AudioManager.MODE_NORMAL) return false
        val nm = getSystemService(NotificationManager::class.java) ?: return true
        return nm.currentInterruptionFilter <= NotificationManager.INTERRUPTION_FILTER_ALL
    }

    // ---------- Proactive: reminders, events, battery ----------

    private val announcedEvents = HashSet<Long>()
    private var lowBatterySaid = false

    private val proactiveTick = object : Runnable {
        override fun run() {
            runCatching { proactive() }.onFailure { Log.w(TAG, "friday proactive: ${it.javaClass.simpleName}") }
            main.postDelayed(this, 60_000)
        }
    }

    private fun proactive() {
        val store = Store.get(this)
        val memory = Memory.get(this)
        if (store.taskAlerts) {
            memory.takeDue().forEach { t ->
                Proactive.notify(this, ALERTS, "Reminder", t.text, id = t.id.hashCode())
                if (state == State.WAKE && quietIsFine()) proactiveSay("Reminder, boss: ${t.text}.", followUp = false)
            }
        }
        if (store.calendarAlerts && CalendarReader.allowed(this)) {
            val now = System.currentTimeMillis()
            CalendarReader.upcoming(this, hours = 1).filter { !it.allDay && it.begin - now in 8 * 60_000L..11 * 60_000L }
                .forEach { e ->
                    if (announcedEvents.add(e.id * 31 + e.begin)) {
                        val text = "${e.title} in ten minutes" + if (e.location.isNotBlank()) ", at ${e.location}." else "."
                        Proactive.notify(this, ALERTS, "Coming up", text, id = e.id.toInt())
                        if (state == State.WAKE && quietIsFine()) proactiveSay("Heads up, boss: $text", followUp = false)
                    }
                }
        }
        if (store.batteryAlerts) {
            val b = status.battery()
            if (b.charging || b.percent > 25) lowBatterySaid = false
            val saver = status.isOn("battery_saver") == true
            if (!lowBatterySaid && !b.charging && b.percent in 1..15 && !saver && state == State.WAKE) {
                lowBatterySaid = true
                val say = "Battery's at ${b.percent} percent, boss. Shall I turn on battery saver?"
                if (quietIsFine()) proactiveSay(say, followUp = true, pendingSteps = listOf(DeviceTask(kind = "toggle", target = "battery_saver", on = true)))
                else Proactive.notify(this, ALERTS, "Battery low", "${b.percent}% left. Turn on battery saver to last longer.", id = 77)
            }
        }
    }

    /** [byUser]: asked for on the Friday screen (interrupts her); else scheduled (only when she's free). */
    private fun startBriefing(kind: String, byUser: Boolean = true) {
        when (state) {
            State.WAKE -> Unit
            State.COMMAND -> if (!byUser) return
            State.SPEAKING -> if (byUser) { speakToken++; voice?.stop() } else return
            State.BUSY -> return
        }
        if (runner.busy) return
        state = State.BUSY
        conversationOver = false
        history.clear()
        FridayBus.update { copy(lines = emptyList(), steps = emptyList(), heard = "", say = "") }
        phase(FridayBus.Phase.THINKING)
        hud("Your $kind briefing", "One moment, boss…", thinking = true)
        ask(audio = null, Ask(text = "Give me my $kind briefing", briefing = kind))
    }

    // ---------- Voice check ----------

    // Voice check: the mic's audio for a few seconds, saved so the wake word can be tuned to a real voice.
    @Volatile private var check: ShortArray? = null
    private var checkLength = 0
    private var checkWakes = 0
    private var levelSum = 0.0
    private var levelPeak = 0.0
    private var levelChunks = 0

    private fun startCheck() {
        checkLength = 0
        checkWakes = 0
        if (mic == null || state != State.WAKE) return
        check = ShortArray(FridayWake.SAMPLE_RATE * 8)
        hud("Voice check", "Say \"Hey Friday\" three times, a second apart.")
    }

    private fun recordCheck(chunk: ShortArray, woke: Boolean) {
        val buf = check ?: return
        if (woke) checkWakes++
        val n = minOf(chunk.size, buf.size - checkLength)
        chunk.copyInto(buf, checkLength, 0, n)
        checkLength += n
        if (checkLength < buf.size) return
        check = null
        val wakes = checkWakes
        val file = File(getExternalFilesDir(null), "friday_check.wav")
        runCatching { file.writeBytes(Wav.encode(buf)) }
        Log.i(TAG, "friday: voice check heard $wakes, saved ${file.name}")
        state = State.BUSY
        main.post {
            conversationOver = true
            val say = when (wakes) {
                0 -> "I didn't catch it that time, boss."
                1 -> "I heard you once, boss."
                else -> "I heard you $wakes times, boss."
            }
            hud("Voice check", say)
            speak(say)
        }
    }

    // ---------- Audio ----------

    /** 80 ms of audio, on the microphone (or self-test) thread. */
    private fun onChunk(chunk: ShortArray) {
        val level = rms(chunk)
        // Loudness only (never audio): how loud the mic is, every ~5 s, to tune the wake word.
        levelSum += level
        if (level > levelPeak) levelPeak = level
        if (++levelChunks >= 62) {
            Log.i(TAG, "friday: mic level avg=%.0f peak=%.0f noise=%.0f".format(levelSum / levelChunks, levelPeak, noise))
            levelSum = 0.0
            levelPeak = 0.0
            levelChunks = 0
        }
        when (state) {
            State.WAKE -> {
                preRoll.addLast(chunk.copyOf())
                if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                val heard = wake?.process(chunk) ?: return
                if (check != null) {
                    recordCheck(chunk, heard)
                    if (heard) wake?.reset()
                    return
                }
                if (!heard) noise = noise * 0.97 + level * 0.03
                if (heard) {
                    Log.i(TAG, "friday: wake word")
                    state = State.BUSY
                    val before = preRoll.toList()
                    preRoll.clear()
                    main.post { beginCommand(before = before) }
                }
            }
            State.SPEAKING -> {
                // "Friday, ..." while she talks: stop and listen.
                if (wake?.process(chunk) == true) {
                    Log.i(TAG, "friday: interrupted by the wake word")
                    state = State.BUSY
                    main.post {
                        speakToken++
                        voice?.stop()
                        beginCommand(isFollowUp = true)
                    }
                }
            }
            State.COMMAND -> {
                val l = (level / 5000.0).toFloat()
                main.post {
                    ChatReaderService.running?.jarvisLevel(l)
                    FridayBus.update { copy(level = l) }
                }
                capture(chunk, level)
            }
            State.BUSY -> Unit
        }
    }

    private fun rms(chunk: ShortArray): Double {
        var sum = 0.0
        for (s in chunk) sum += s.toDouble() * s
        return sqrt(sum / chunk.size)
    }

    // ---------- The command ----------

    /** "Talk now": listen straight away (stops whatever Friday was saying). */
    private fun talkNow() {
        if (state == State.COMMAND) return
        if (state == State.SPEAKING) {
            speakToken++
            voice?.stop()
        }
        if (runner.busy) runner.cancel()
        beginCommand(isFollowUp = FridayBus.state.lines.isNotEmpty() && !conversationOver)
    }

    private fun beginCommand(isFollowUp: Boolean = false, before: List<ShortArray> = emptyList()) {
        if (state == State.COMMAND) return
        followUp = isFollowUp
        if (!isFollowUp) {
            history.clear()
            FridayBus.update { copy(lines = emptyList(), steps = emptyList(), heard = "", say = "") }
        }
        commandLength = 0
        for (c in before) {
            c.copyInto(command, commandLength)
            commandLength += c.size
        }
        fromWake = before.isNotEmpty()
        commandChunks = 0
        silentChunks = 0
        spokenChunks = 0
        heardSpeech = false
        // "Hey Friday" on its own (not "see you on Friday" mid-sentence): she greets if a pause follows.
        greetPending = fromWake && !isFollowUp && Store.get(this).fridayGreeting && !talkingBefore(before)
        val greeted = afterGreeting
        afterGreeting = false
        hideToken++
        if (greetPending) {
            // The greeting is the answer, so no chime (it would also sound like speech to the mic).
            skipChunks = 1
            buzz()
        } else {
            skipChunks = CHIME_CHUNKS
            chime(soft = isFollowUp)
        }
        phase(FridayBus.Phase.LISTENING)
        hud(when {
            greeted || !isFollowUp -> "Listening…"
            pending != null -> "Listening… (yes or no)"
            else -> "Listening… (or say thanks)"
        })
        state = State.COMMAND
    }

    /** Was someone already talking before the wake word (so "Friday" was just a word in a sentence)? */
    private fun talkingBefore(before: List<ShortArray>): Boolean {
        // The last ~1 s is "Hey Friday" itself; look at what came before it.
        val earlier = before.dropLast(13)
        val loud = maxOf(noise * 3.5, 150.0)
        return earlier.count { rms(it) > loud } >= 3
    }

    /** "Hey Friday" and a pause: "How can I help you, boss?", then listen for the command. */
    private fun greet() {
        Log.i(TAG, "friday: greeting")
        // The greeting answered the wake word: what's said next is the command itself.
        fromWake = false
        afterGreeting = true
        conversationOver = false
        FridayBus.line(false, GREETING)
        hud("Friday", GREETING)
        speak(GREETING)
    }

    private fun capture(chunk: ShortArray, level: Double) {
        if (skipChunks > 0) {
            skipChunks--
            return
        }
        chunk.copyInto(command, commandLength)
        commandLength += chunk.size
        commandChunks++
        if (level > maxOf(noise * 3.5, 150.0)) {
            heardSpeech = true
            silentChunks = 0
            spokenChunks++
        } else {
            silentChunks++
        }
        if (greetPending) {
            if (heardSpeech) {
                greetPending = false  // "Hey Friday, play Kesariya" in one breath: no greeting
            } else if (commandChunks >= GREET_WAIT_CHUNKS) {
                greetPending = false
                state = State.BUSY
                main.post { greet() }
                return
            }
        }
        val endQuiet = if (spokenChunks < 8) END_SILENCE_EARLY else END_SILENCE_CHUNKS
        val done = (heardSpeech && silentChunks >= endQuiet) ||
            (!heardSpeech && commandChunks >= (if (followUp) FOLLOW_UP_CHUNKS else NO_SPEECH_CHUNKS)) ||
            commandChunks >= MAX_COMMAND_CHUNKS
        if (!done) return
        state = State.BUSY
        val speech = heardSpeech
        // Drop most of the trailing quiet; keep a little so the last word isn't clipped.
        val pcm = command.copyOf(commandLength - maxOf(0, silentChunks - 3) * FridayWake.CHUNK)
        Log.i(TAG, "jarvis: command %.1fs speech=%s".format(pcm.size / 16000.0, speech))
        main.post { send(pcm, speech) }
    }

    private fun send(pcm: ShortArray, speech: Boolean) {
        if (!speech) {
            if (followUp) endConversation() else {
                ChatReaderService.running?.jarvisHudHide()
                phase(FridayBus.Phase.IDLE)
                resume()
            }
            return
        }
        phase(FridayBus.Phase.THINKING)
        hud("Thinking…", thinking = true)
        ask(Wav.encode(louder(pcm)), Ask())
    }

    /** A typed command from the Friday screen. */
    private fun typed(text: String) {
        if (text.isBlank()) return
        if (state == State.SPEAKING) {
            speakToken++
            voice?.stop()
        }
        if (runner.busy) runner.cancel()
        val fresh = conversationOver || FridayBus.state.lines.isEmpty()
        if (fresh) history.clear()
        conversationOver = false
        followUp = !fresh
        state = State.BUSY
        FridayBus.line(true, text)
        phase(FridayBus.Phase.THINKING)
        hud("Thinking…", "“$text”", thinking = true)
        ask(audio = null, Ask(text = text))
    }

    /** The floating orb, held: look at the screen and explain it. */
    private fun lookNow() {
        if (state == State.COMMAND || runner.busy) return
        if (state == State.SPEAKING) {
            speakToken++
            voice?.stop()
        }
        state = State.BUSY
        conversationOver = false
        history.clear()
        FridayBus.update { copy(lines = emptyList(), steps = emptyList()) }
        lookAndAsk("What's on my screen? Explain it and tell me anything I should do.")
    }

    /** Sends a command to the server (audio, or text when Friday already knows what was said). */
    private fun ask(audio: ByteArray?, a: Ask, screenImage: String? = null, screenText: List<String> = emptyList()) {
        val a11y = ChatReaderService.running
        val context = a11y?.jarvisContext() ?: JarvisContext(app = "phone")
        val store = Store.get(this)
        val memory = Memory.get(this)
        val contact = context.chatKey?.let { store.contact(it) }
        val body = ReplyApi.jarvisBody(
            audio = audio,
            wake = fromWake && audio != null && !a.looked,
            context = context,
            relationship = contact?.relationship ?: contact?.guessedRelationship,
            styleCardJson = store.styleCardJson,
            style = store.style,
            examples = contact?.examples?.toList().orEmpty(),
            memory = contact?.memory?.toList().orEmpty(),
            language = contact?.language,
            localTime = SimpleDateFormat("EEE d MMM yyyy, h:mm a", Locale.ENGLISH).format(Date()),
            history = history.toList(),
            notes = store.notes,
            profile = store.profile,
            notificationsAllowed = NotificationWatcher.allowed(this),
            notifications = NotificationLog.get(this).recent(),
        )
        val pend = pending?.takeIf { SystemClock.elapsedRealtime() - it.at < PENDING_FOR_MS }
        if (pend == null) pending = null
        val api = store.api()
        io.execute {
            // Usage times, the calendar and memory take a moment to gather: off the main thread.
            runCatching { body.put("habits", Habits.summary(this)) }
            runCatching {
                ReplyApi.addFridayContext(
                    body,
                    text = a.text,
                    device = status.summary(),
                    calendar = CalendarReader.lines(this).ifEmpty {
                        if (CalendarReader.allowed(this)) emptyList() else listOf("calendar access is off")
                    },
                    tasks = memory.openTasks().map { t -> t.text + if (t.due > 0) " · due ${Memory.stamp(t.due)}" else "" },
                    memories = memory.searchable(),
                    routines = memory.routines().map { it.name to it.steps },
                    pending = pend?.let { p -> p.question + " [" + p.steps.joinToString("; ") { TaskRunner.describe(it) } + "]" },
                    screenText = screenText,
                    screenImage = screenImage,
                    looked = a.looked,
                    failed = a.failed,
                    briefing = a.briefing,
                )
            }
            val result = runCatching { api.jarvis(body) }
            main.post { handle(result, context, a) }
        }
    }

    private fun handle(result: Result<JarvisResult>, context: JarvisContext, a: Ask) {
        val a11y = ChatReaderService.running
        result
            .onSuccess { r ->
                Log.i(TAG, "jarvis: action=${r.action} steps=${r.steps.map { it.kind }} confirm=${r.confirm}")
                if (r.action == "ignore") {
                    // "Friday" was only a word in a sentence: back to waiting, quietly.
                    Log.i(TAG, "friday: not for me")
                    a11y?.jarvisHudHide()
                    phase(FridayBus.Phase.IDLE)
                    resume()
                    return@onSuccess
                }
                if (r.remember.isNotEmpty() || r.forget.isNotEmpty()) {
                    Store.get(this).updateProfile(r.remember, r.forget)
                    Log.i(TAG, "friday: knows ${r.remember.size} new, forgot ${r.forget.size}")
                }
                if (r.heard.isNotBlank() && a.text == null) FridayBus.line(true, r.heard)
                FridayBus.update { copy(heard = r.heard) }
                conversationOver = r.action == "end"
                if (r.action == "end") a11y?.stopAutoScroll()
                when (r.action) {
                    "look" -> {
                        hud("“${r.heard}”", r.say)
                        lookAndAsk(r.heard)
                        return@onSuccess
                    }
                    "confirm" -> {
                        val p = pending
                        pending = null
                        FridayBus.update { copy(pending = null) }
                        if (p != null) {
                            runSteps(p.steps, "All done, boss.", r.heard, a)
                            return@onSuccess
                        }
                    }
                    "cancel" -> {
                        pending = null
                        FridayBus.update { copy(pending = null) }
                    }
                    "device" -> if (r.steps.isNotEmpty()) {
                        if (r.confirm) {
                            // Ask first; the steps wait for "yes".
                            pending = Pending(r.steps, r.say, SystemClock.elapsedRealtime())
                            FridayBus.update { copy(pending = r.say, steps = r.steps.map(TaskRunner::Step)) }
                            a11y?.jarvisSteps(r.steps.map(TaskRunner::Step))
                            conversationOver = false
                            reply(r.heard, r.say)
                            return@onSuccess
                        }
                        runSteps(r.steps, r.say, r.heard, a)
                        return@onSuccess
                    }
                }
                var caption = r.say
                when (r.action) {
                    "write" -> r.compose?.let { c ->
                        if (c.ideas) {
                            a11y?.jarvisShow(context, compose = c)
                        } else {
                            val text = c.variants.firstOrNull()?.text
                            if (text != null && a11y?.jarvisWrite(text) == true) caption = "“$text”"
                        }
                    }
                    "suggest" -> r.suggest?.let { a11y?.jarvisShow(context, suggest = it) }
                }
                reply(r.heard, r.say, caption)
            }
            .onFailure { e ->
                Log.w(TAG, "jarvis: failed (${e.javaClass.simpleName})")
                val sorry = trouble(e)
                conversationOver = true
                reply("", sorry)
            }
    }

    /** Says [say] (showing [caption]), remembers the exchange, and keeps the conversation going. */
    private fun reply(heard: String, say: String, caption: String = say) {
        FridayBus.line(false, say)
        FridayBus.update { copy(say = say) }
        hud(if (heard.isNotBlank()) "“$heard”" else "Friday", caption)
        history.addLast(heard to say)
        while (history.size > 4) history.removeFirst()
        if (heard.isNotBlank()) io.execute { Memory.get(this).logExchange(heard, say) }
        speak(say)
    }

    /** Runs [steps] (checking each), then says how it went: [planned] if all of it worked. */
    private fun runSteps(steps: List<DeviceTask>, planned: String, heard: String, a: Ask) {
        val a11y = ChatReaderService.running
        if (locked() && steps.any { it.kind in TaskRunner.NEEDS_SCREEN || (it.kind == "toggle" && it.target !in setOf("dnd")) }) {
            // Apps can't open over the lock screen: ask to unlock, then carry on.
            unlockWait = UnlockWait(steps, planned, heard, SystemClock.elapsedRealtime())
            conversationOver = true
            askToUnlock()
            reply(heard, "Unlock your phone, boss, and I'll do it.")
            return
        }
        if (steps.size == 1 && steps[0].kind == "phone" && steps[0].target == "screenshot") {
            // Out of the picture first.
            a11y?.jarvisHudHide()
            main.postDelayed({ device.run(steps[0]) { reply(heard, planned) } }, 450)
            return
        }
        phase(FridayBus.Phase.WORKING)
        hud(if (heard.isNotBlank()) "“$heard”" else "Working…", planned)
        runner.run(
            steps,
            update = { s ->
                FridayBus.update { copy(steps = s.toList()) }
                ChatReaderService.running?.jarvisSteps(s)
            },
            done = { report ->
                Log.i(TAG, "friday: steps ok=${report.ok} failed=${report.failed.map { it.task.kind }}")
                if (!report.ok && report.retryable && !a.looked && ChatReaderService.running != null) {
                    // A button wasn't where Friday expected: look at the screen and try another way.
                    lookAndAsk(heard, failed = report.failures())
                    return@run
                }
                reply(heard, report.speech(planned))
            },
        )
    }

    /** Takes a screenshot (and the screen's words) and asks the server again with them. */
    private fun lookAndAsk(heard: String, failed: List<String> = emptyList()) {
        val a11y = ChatReaderService.running
        if (a11y == null) {
            reply(heard, "I need Vakya switched on in Accessibility to see your screen, boss.")
            return
        }
        phase(FridayBus.Phase.LOOKING, seeing = true)
        hud("Looking at your screen…", if (failed.isEmpty()) "Let me look, boss." else "That didn't work. Let me look again.")
        main.postDelayed({
            a11y.lookAtScreen { image, words ->
                Log.i(TAG, "friday: looked (image=${image != null}, words=${words.size})")
                phase(FridayBus.Phase.THINKING)
                hud("Thinking…", thinking = true)
                ask(audio = null, Ask(text = heard, looked = true, failed = failed), screenImage = image, screenText = words)
            }
        }, 0L)
    }

    private fun locked() = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun askToUnlock() {
        val from: Context = ChatReaderService.running ?: this
        runCatching {
            from.startActivity(Intent(from, com.replybot.ui.UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "friday: can't ask to unlock (${it.javaClass.simpleName})") }
    }

    private fun afterUnlock(ok: Boolean) {
        val w = unlockWait ?: return
        Log.i(TAG, "friday: unlocked=$ok")
        // No prompt (or it was dismissed): the task still waits for the user to unlock themselves.
        if (!ok) return
        unlockWait = null
        if (SystemClock.elapsedRealtime() - w.at > 60_000L) return
        // A moment for the lock screen to go, then do what was asked.
        main.postDelayed({
            if (state == State.WAKE || state == State.BUSY) {
                state = State.BUSY
                conversationOver = true
                runSteps(w.steps, w.say, w.heard, Ask())
            }
        }, 500)
    }

    /** Quiet mics: bring the command up to a normal loudness for speech-to-text. */
    private fun louder(pcm: ShortArray): ShortArray {
        var peak = 1
        for (s in pcm) peak = maxOf(peak, kotlin.math.abs(s.toInt()))
        val gain = (20_000f / peak).coerceIn(1f, 8f)
        if (gain < 1.1f) return pcm
        return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun trouble(e: Throwable): String = when {
        e is ReplyApi.HttpError && e.code == 429 ->
            "We've used today's free replies, boss. Add your own Groq key in Vakya and I'm all yours."
        e is ReplyApi.HttpError && e.code == 404 -> "The Vakya server needs an update before I can do that, boss."
        e is ReplyApi.HttpError && e.code == 401 -> "The server didn't accept our key, boss. Check Vakya's settings."
        e is ReplyApi.HttpError && e.code == 422 -> "The server couldn't take that request, boss. It may need an update."
        e is SocketTimeoutException -> "The server took too long, boss. Try me again."
        e is IOException -> "I can't reach the server right now, boss."
        else -> "Something went wrong on my end, boss."
    }

    // ---------- Speaking ----------

    private fun speak(text: String) {
        val token = ++speakToken
        val done = { if (token == speakToken) { speakToken++; doneSpeaking() } }
        val v = voice
        if (v == null || text.isBlank()) {
            done()
            return
        }
        phase(FridayBus.Phase.SPEAKING, FridayBus.state.seeing)
        // She can be interrupted by her name, unless she's saying it herself.
        state = if (mic != null && !text.contains("friday", ignoreCase = true)) State.SPEAKING else State.BUSY
        v.speak(text) { main.post(done) }
        // If the speech engine never reports back, don't stay deaf.
        main.postDelayed(done, 4000L + text.length * 90L)
    }

    /** After Friday speaks: keep listening for a follow-up, unless the conversation is over. */
    private fun doneSpeaking() {
        state = State.BUSY
        if (!conversationOver && mic != null) {
            beginCommand(isFollowUp = true)
            return
        }
        endConversation()
    }

    private fun endConversation() {
        history.clear()
        phase(FridayBus.Phase.IDLE)
        val token = ++hideToken
        main.postDelayed({
            if (token == hideToken) {
                ChatReaderService.running?.jarvisHudHide()
                if (state == State.WAKE) FridayBus.update { copy(steps = emptyList()) }
            }
        }, 2500)
        resume()
    }

    /** The stop button: quiet at once, nothing more done. */
    private fun interruptAll() {
        Log.i(TAG, "friday: stopped by the user")
        speakToken++
        voice?.stop()
        runner.cancel()
        pending = null
        unlockWait = null
        conversationOver = true
        state = State.BUSY
        FridayBus.update { copy(pending = null) }
        endConversation()
    }

    /** Back to waiting for "Hey Friday", with fresh audio so the last words can't fire again. */
    private fun resume() {
        // Talked to (or a self-test) without "Listen for Hey Friday" on: done, so don't keep
        // listening or stay in the notifications.
        if (!Store.get(this).jarvisOn) {
            stopSelf()
            return
        }
        io.execute {
            wake?.reset()
            state = State.WAKE
        }
    }

    private fun buzz() {
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun chime(soft: Boolean = false) {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, if (soft) 30 else 55)
            tone.startTone(if (soft) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, if (soft) 90 else 160)
            main.postDelayed({ tone.release() }, 400)
        }
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---------- Self-test ----------

    /**
     * Plays a WAV (16 kHz mono) from the app's own files folder through the whole chain in real
     * time, as if it came from the microphone: adb push it to
     * Android/data/<app>/files/, then open Vakya with the extra jarvis_test=<file name>.
     */
    private fun playTestFile(name: String) {
        val file = File(getExternalFilesDir(null), File(name).name)
        if (!file.exists()) {
            Log.w(TAG, "jarvis test: no ${file.name}")
            return
        }
        testing = true
        Thread({
            val pcm = Wav.decode(file.readBytes())
            Log.i(TAG, "jarvis test: %.1fs from %s".format(pcm.size / 16000.0, file.name))
            var i = 0
            while (i + FridayWake.CHUNK <= pcm.size && !stopping) {
                onChunk(pcm.copyOfRange(i, i + FridayWake.CHUNK))
                i += FridayWake.CHUNK
                Thread.sleep(80)
            }
            repeat(30) {  // quiet after the clip, so the command ends
                if (!stopping) {
                    onChunk(ShortArray(FridayWake.CHUNK))
                    Thread.sleep(80)
                }
            }
            testing = false
        }, "jarvis-test").start()
    }
}
