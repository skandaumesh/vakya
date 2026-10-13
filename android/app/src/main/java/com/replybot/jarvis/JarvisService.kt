package com.replybot.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.replybot.R
import com.replybot.data.JarvisContext
import com.replybot.data.JarvisResult
import com.replybot.data.ReplyApi
import com.replybot.data.Store
import com.replybot.service.ChatReaderService
import com.replybot.ui.MainActivity
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * "Hey Jarvis". Listens on the phone for the wake word (no audio leaves the phone until it's
 * heard), records the command until you stop talking, sends it to the Vakya server, and does
 * what it says: writes into the open chat, shows reply options, or answers aloud.
 *
 * A foreground service with a notification ("Jarvis is listening"), started from the Vakya app
 * while "Listen for Hey Jarvis" is on. It never presses send.
 */
class JarvisService : Service() {

    companion object {
        const val ACTION_START = "com.replybot.jarvis.START"
        const val ACTION_STOP = "com.replybot.jarvis.STOP"
        /** Skip the wake word: listen for a command right now. */
        const val ACTION_TALK = "com.replybot.jarvis.TALK"
        /** Self-test: play a WAV from the app's own files folder instead of the microphone. */
        const val ACTION_TEST = "com.replybot.jarvis.TEST"
        const val EXTRA_FILE = "file"

        private const val TAG = "Vakya"
        private const val CHANNEL = "jarvis"
        private const val NOTIFICATION_ID = 7

        private const val MAX_COMMAND_CHUNKS = 125  // 10 s
        private const val NO_SPEECH_CHUNKS = 50     // 4 s and nothing said: give up
        private const val END_SILENCE_CHUNKS = 10   // 0.8 s of quiet after speaking: done
        private const val CHIME_CHUNKS = 2          // don't record the "listening" chime

        @Volatile
        var running = false
            private set

        fun start(context: Context, action: String = ACTION_START, file: String? = null) {
            val intent = Intent(context, JarvisService::class.java).setAction(action)
            if (file != null) intent.putExtra(EXTRA_FILE, file)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }

    private enum class State { WAKE, COMMAND, BUSY }

    @Volatile private var state = State.WAKE
    @Volatile private var stopping = false
    @Volatile private var testing = false
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var wake: WakeWord? = null
    private var voice: JarvisVoice? = null
    private var mic: Thread? = null
    private var hideToken = 0

    // The command being recorded (audio thread only, after beginCommand sets it up).
    private val command = ShortArray(MAX_COMMAND_CHUNKS * WakeWord.CHUNK)
    private var commandLength = 0
    private var commandChunks = 0
    private var silentChunks = 0
    private var skipChunks = 0
    private var heardSpeech = false
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
            ACTION_TALK -> main.post { beginCommand() }
            ACTION_TEST -> intent.getStringExtra(EXTRA_FILE)?.let(::playTestFile)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        running = false
        mic?.join(500)
        main.removeCallbacksAndMessages(null)
        ChatReaderService.running?.jarvisHudHide()
        voice?.shutdown()
        io.execute { wake?.close() }
        io.shutdown()
        super.onDestroy()
    }

    // ---------- Setup ----------

    private fun goForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Jarvis listens for \"Hey Jarvis\""
                setShowBadge(false)
            },
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_jarvis)
        fun service(code: Int, action: String) = PendingIntent.getService(
            this, code, Intent(this, JarvisService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Hey Jarvis\", then what you need")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
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
                WakeWord(asset("melspectrogram.onnx"), asset("embedding_model.onnx"), asset("hey_jarvis_v0.1.onnx"))
            } catch (e: Exception) {
                Log.e(TAG, "jarvis: wake word models failed to load", e)
                return false
            }
            voice = JarvisVoice(this)
            running = true
        }
        if (withMic && mic == null) {
            stopping = false
            mic = Thread(::listen, "jarvis-mic").apply { start() }
        }
        return true
    }

    private fun asset(name: String) = assets.open("jarvis/$name").use { it.readBytes() }

    // ---------- Listening ----------

    private fun listen() {
        val rate = WakeWord.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, WakeWord.CHUNK * 8),
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
        val chunk = ShortArray(WakeWord.CHUNK)
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

    /** 80 ms of audio, on the microphone (or self-test) thread. */
    private fun onChunk(chunk: ShortArray) {
        val level = rms(chunk)
        when (state) {
            State.WAKE -> {
                val score = wake?.process(chunk) ?: return
                if (score < 0.1f) noise = noise * 0.97 + level * 0.03
                if (score >= WakeWord.THRESHOLD) {
                    Log.i(TAG, "jarvis: wake word (score %.2f)".format(score))
                    state = State.BUSY
                    main.post { beginCommand() }
                }
            }
            State.COMMAND -> {
                main.post { ChatReaderService.running?.jarvisLevel((level / 5000.0).toFloat()) }
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

    private fun beginCommand() {
        if (state == State.COMMAND) return
        commandLength = 0
        commandChunks = 0
        silentChunks = 0
        heardSpeech = false
        skipChunks = CHIME_CHUNKS
        hideToken++
        chime()
        ChatReaderService.running?.jarvisHud("Listening…")
        state = State.COMMAND
    }

    private fun capture(chunk: ShortArray, level: Double) {
        if (skipChunks > 0) {
            skipChunks--
            return
        }
        chunk.copyInto(command, commandLength)
        commandLength += chunk.size
        commandChunks++
        if (level > maxOf(noise * 3, 500.0)) {
            heardSpeech = true
            silentChunks = 0
        } else {
            silentChunks++
        }
        val done = (heardSpeech && silentChunks >= END_SILENCE_CHUNKS) ||
            (!heardSpeech && commandChunks >= NO_SPEECH_CHUNKS) ||
            commandChunks >= MAX_COMMAND_CHUNKS
        if (!done) return
        state = State.BUSY
        val speech = heardSpeech
        // Drop most of the trailing quiet; keep a little so the last word isn't clipped.
        val pcm = command.copyOf(commandLength - maxOf(0, silentChunks - 3) * WakeWord.CHUNK)
        Log.i(TAG, "jarvis: command %.1fs speech=%s".format(pcm.size / 16000.0, speech))
        main.post { send(pcm, speech) }
    }

    private fun send(pcm: ShortArray, speech: Boolean) {
        val a11y = ChatReaderService.running
        if (!speech) {
            a11y?.jarvisHudHide()
            resume()
            return
        }
        a11y?.jarvisHud("Thinking…", thinking = true)
        val context = a11y?.jarvisContext() ?: JarvisContext(app = "phone")
        val store = Store.get(this)
        val contact = context.chatKey?.let { store.contact(it) }
        val body = ReplyApi.jarvisBody(
            audio = Wav.encode(pcm),
            context = context,
            relationship = contact?.relationship ?: contact?.guessedRelationship,
            styleCardJson = store.styleCardJson,
            style = store.style,
            examples = contact?.examples?.toList().orEmpty(),
            memory = contact?.memory?.toList().orEmpty(),
            language = contact?.language,
            localTime = SimpleDateFormat("EEE d MMM yyyy, h:mm a", Locale.ENGLISH).format(Date()),
        )
        val api = store.api()
        io.execute {
            val result = runCatching { api.jarvis(body) }
            main.post { handle(result, context) }
        }
    }

    private fun handle(result: Result<JarvisResult>, context: JarvisContext) {
        val a11y = ChatReaderService.running
        result
            .onSuccess { r ->
                Log.i(TAG, "jarvis: action=${r.action}")
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
                a11y?.jarvisHud(if (r.heard.isNotBlank()) "“${r.heard}”" else "Jarvis", caption)
                speak(r.say)
            }
            .onFailure { e ->
                Log.w(TAG, "jarvis: failed (${e.javaClass.simpleName})")
                val sorry = trouble(e)
                a11y?.jarvisHud("Jarvis", sorry)
                speak(sorry)
            }
    }

    private fun trouble(e: Throwable): String = when {
        e is ReplyApi.HttpError && e.code == 429 ->
            "We've used today's free replies, boss. Add your own Groq key in Vakya and I'm all yours."
        e is ReplyApi.HttpError && e.code == 404 -> "The Vakya server needs an update before I can do that, boss."
        e is ReplyApi.HttpError && e.code == 401 -> "The server didn't accept our key, boss. Check Vakya's settings."
        e is SocketTimeoutException -> "The server took too long, boss. Try me again."
        e is IOException -> "I can't reach the server right now, boss."
        else -> "Something went wrong on my end, boss."
    }

    private fun speak(text: String) {
        val v = voice
        if (v == null || text.isBlank()) {
            doneSpeaking()
            return
        }
        v.speak(text) { main.post { doneSpeaking() } }
    }

    private fun doneSpeaking() {
        val token = ++hideToken
        main.postDelayed({ if (token == hideToken) ChatReaderService.running?.jarvisHudHide() }, 2500)
        resume()
    }

    /** Back to waiting for "Hey Jarvis", with fresh audio so the last words can't fire again. */
    private fun resume() {
        // A self-test without "Listen for Hey Jarvis" on: done, so don't stay in the notifications.
        if (mic == null && !Store.get(this).jarvisOn) {
            stopSelf()
            return
        }
        io.execute {
            wake?.reset()
            state = State.WAKE
        }
    }

    private fun chime() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 160)
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
            while (i + WakeWord.CHUNK <= pcm.size && !stopping) {
                onChunk(pcm.copyOfRange(i, i + WakeWord.CHUNK))
                i += WakeWord.CHUNK
                Thread.sleep(80)
            }
            repeat(30) {  // quiet after the clip, so the command ends
                if (!stopping) {
                    onChunk(ShortArray(WakeWord.CHUNK))
                    Thread.sleep(80)
                }
            }
            testing = false
        }, "jarvis-test").start()
    }
}
