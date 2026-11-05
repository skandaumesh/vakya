package com.replybot.jarvis

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.replybot.data.ReplyApi
import com.replybot.data.Store
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Friday's voice. By default a natural voice ("Diana"): a human-sounding neural voice made by the
 * Vakya server (Orpheus), which sends each reply already spoken, so it costs no extra wait.
 * Offline there's "Jenny (Dioco)", an Irish voice made on the phone by sherpa-onnx, and the
 * phone's own text-to-speech. When the natural voice can't be had (no internet, server busy),
 * Friday says it with Jenny instead, so she's never silent.
 */
class JarvisVoice(context: Context) {

    private val app = context.applicationContext
    private val phone = PhoneVoice(app)
    @Volatile private var jenny: JennyVoice? = null
    private val natural = NaturalVoice(app)
    private val loader = Executors.newSingleThreadExecutor()

    init {
        loader.execute {
            jenny = runCatching { JennyVoice(app) }
                .onFailure { Log.w("Vakya", "friday: Jenny's voice didn't load (${it.javaClass.simpleName})") }
                .getOrNull()
        }
    }

    /** Say [text], then call [done] (on a background thread). [onFallback]: the natural voice
     *  couldn't be had, so it's said with Jenny (or the phone's voice). */
    fun speak(text: String, onFallback: (() -> Unit)? = null, done: () -> Unit) {
        stop()
        val store = Store.get(app)
        when (store.fridayVoice) {
            NATURAL -> natural.speak(text, store.naturalVoice, done) {
                onFallback?.invoke()
                offline(text, done)
            }
            JENNY -> offline(text, done)
            else -> phone.speak(text, done)
        }
    }

    private fun offline(text: String, done: () -> Unit) {
        val j = jenny
        if (j != null) j.speak(text, done) else phone.speak(text, done)
    }

    /** The voice to ask the server for with each reply, or null when the phone speaks itself. */
    fun serverVoice(): String? = Store.get(app).let { if (it.fridayVoice == NATURAL) it.naturalVoice else null }

    /** The server's spoken [audio] of [text] (null: it couldn't make it), for when it's said. */
    fun offer(text: String, audio: ByteArray?) = natural.offer(text, audio)

    /** Make [text] ahead of time, so saying it later starts at once. */
    fun prepare(text: String) {
        loader.execute { jenny?.prepare(text) }
        serverVoice()?.let { natural.prepare(text, it) }
    }

    /** Stop talking now (the user interrupted). */
    fun stop() {
        phone.stop()
        jenny?.stop()
        natural.stop()
    }

    /** "Hear her" in the Command Center. Jenny is waited for if she's still loading. */
    fun preview(text: String, onFallback: (() -> Unit)? = null) {
        if (Store.get(app).fridayVoice == NATURAL) speak(text, onFallback) {} else loader.execute { speak(text) {} }
    }

    fun shutdown() {
        phone.shutdown()
        jenny?.release()
        natural.release()
        loader.shutdown()
    }

    companion object {
        const val NATURAL = "natural"
        const val JENNY = "jenny"
        const val PHONE = "phone"

        /** Orpheus voices on the server, by name. */
        val NATURAL_VOICES = listOf("diana", "hannah", "autumn", "austin", "daniel", "troy")
    }
}

/**
 * The natural voice: audio made by the server. A reply's audio comes with the reply ([offer]);
 * other lines (the greeting, a reminder) are fetched from /speak, and short ones kept on the
 * phone so they play instantly next time.
 */
private class NaturalVoice(private val app: Context) {

    private val worker = Executors.newSingleThreadExecutor()
    private val fetcher = Executors.newSingleThreadExecutor()
    @Volatile private var current = 0
    /** After the server said no (or didn't answer), use the offline voice for a while. */
    @Volatile private var offUntil = 0L
    private val ready = lru<ByteArray>(6)
    private val missing = lru<Boolean>(16)
    private val dir = File(app.cacheDir, "friday-natural")

    fun offer(text: String, audio: ByteArray?) {
        if (audio != null) synchronized(ready) { ready[text] = audio } else synchronized(missing) { missing[text] = true }
    }

    fun speak(text: String, voice: String, done: () -> Unit, fallback: () -> Unit) {
        val me = ++current
        val asked = SystemClock.elapsedRealtime()
        worker.execute {
            val wav = synchronized(ready) { ready[text] } ?: saved(text, voice) ?: fetch(text, voice)?.also { keep(text, voice, it) }
            when {
                me != current -> done()
                wav == null -> fallback()
                else -> {
                    Log.i("Vakya", "friday: natural voice ($voice) starts after ${SystemClock.elapsedRealtime() - asked} ms")
                    if (!play(wav, me)) fallback() else done()
                }
            }
        }
    }

    fun prepare(text: String, voice: String) {
        fetcher.execute { if (saved(text, voice) == null) fetch(text, voice)?.let { keep(text, voice, it) } }
    }

    fun stop() {
        current++
    }

    fun release() {
        stop()
        worker.shutdown()
        fetcher.shutdown()
    }

    private fun fetch(text: String, voice: String): ByteArray? {
        if (synchronized(missing) { missing[text] } == true || SystemClock.elapsedRealtime() < offUntil) return null
        val started = SystemClock.elapsedRealtime()
        return runCatching { Store.get(app).api().speak(text, voice) }
            .onSuccess { Log.i("Vakya", "friday: natural voice fetched in ${SystemClock.elapsedRealtime() - started} ms") }
            .onFailure { e ->
                // 503: the server can't make the voice right now; otherwise slow or offline.
                offUntil = SystemClock.elapsedRealtime() + if (e is ReplyApi.HttpError) 5 * 60_000L else 60_000L
                Log.w("Vakya", "friday: natural voice unavailable (${ReplyApi.describe(e)}), using Jenny")
            }
            .getOrNull()
    }

    private fun file(text: String, voice: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$voice-$hash.wav")
    }

    private fun saved(text: String, voice: String): ByteArray? = file(text, voice).takeIf { it.exists() }?.let { f ->
        runCatching { f.readBytes() }.getOrNull()
    }

    /** Short lines are kept on the phone (the greeting, "All done, boss."). */
    private fun keep(text: String, voice: String, wav: ByteArray) {
        if (text.length > 60) return
        runCatching {
            dir.mkdirs()
            dir.listFiles()?.takeIf { it.size > 40 }?.sortedBy { it.lastModified() }?.take(10)?.forEach { it.delete() }
            file(text, voice).writeBytes(wav)
        }
    }

    /** Plays [wav] until it ends or this line is stopped; false if it isn't audio we can play. */
    private fun play(wav: ByteArray, me: Int): Boolean {
        val audio = Wav.read(wav) ?: return false
        val encoding = if (audio.float) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val mask = if (audio.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(audio.rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(audio.rate, mask, encoding) * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse { return false }
        try {
            track.play()
            var at = 0
            while (at < audio.length && me == current) {
                val n = minOf(8192, audio.length - at)
                val wrote = track.write(ByteBuffer.wrap(audio.bytes, audio.offset + at, n), n, AudioTrack.WRITE_BLOCKING)
                if (wrote <= 0) break
                at += wrote
            }
            val frames = at / audio.frameBytes
            val until = SystemClock.uptimeMillis() + 2000 + frames * 1000L / audio.rate
            while (me == current && track.playbackHeadPosition < frames && SystemClock.uptimeMillis() < until) {
                Thread.sleep(20)
            }
            if (me != current) {
                // Interrupted: cut it now rather than finish what's buffered.
                track.pause()
                track.flush()
            }
        } catch (e: Exception) {
            Log.w("Vakya", "friday: natural voice couldn't play (${e.javaClass.simpleName})")
        } finally {
            runCatching { track.stop(); track.release() }
        }
        return true
    }

    private fun <T> lru(max: Int) = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > max
    }
}

/** "Jenny (Dioco)", Piper VITS, int8, from assets/friday-voice (fetched by the build). */
private class JennyVoice(context: Context) {

    private val tts: OfflineTts
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var current = 0

    init {
        require(context.assets.list(DIR)?.contains("model.onnx") == true) { "no voice in the APK" }
        val data = espeakData(context)
        tts = OfflineTts(
            assetManager = context.assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$DIR/model.onnx",
                        tokens = "$DIR/tokens.txt",
                        dataDir = data.absolutePath,
                        lengthScale = 0.92f,  // a touch quicker than the recordings
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                maxNumSentences = 1,  // one sentence at a time: the first is heard sooner
            ),
        )
    }

    fun speak(text: String, done: () -> Unit) {
        val me = ++current
        worker.execute {
            var track: AudioTrack? = null
            try {
                if (me != current) return@execute
                val rate = tts.sampleRate()
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                var frames = 0
                // Each sentence plays as soon as it's made. A real class, not a lambda: the native
                // side looks up invoke(float[]) by name, which a compiled lambda doesn't have.
                val play = object : (FloatArray) -> Int {
                    override fun invoke(samples: FloatArray): Int {
                        if (me != current) return 0
                        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        frames += samples.size
                        return 1
                    }
                }
                val ready = cache[text]
                if (ready != null) {
                    // Made earlier ("How can I help you, boss?"): no wait at all.
                    var i = 0
                    while (i < ready.size && me == current) {
                        val n = minOf(4096, ready.size - i)
                        track.write(ready, i, n, AudioTrack.WRITE_BLOCKING)
                        i += n
                    }
                    frames = i
                } else {
                    tts.generateWithCallback(text, 0, 1.0f, play)
                }
                // Let the last of it play out.
                val until = SystemClock.uptimeMillis() + 2000 + frames * 1000L / rate
                while (me == current && track.playbackHeadPosition < frames && SystemClock.uptimeMillis() < until) {
                    Thread.sleep(20)
                }
            } catch (e: Exception) {
                Log.w("Vakya", "friday: Jenny couldn't speak (${e.javaClass.simpleName})")
            } finally {
                runCatching { track?.stop(); track?.release() }
                done()
            }
        }
    }

    fun stop() {
        current++
    }

    /** Short lines said often, made once and kept, so they're heard the moment they're needed. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()

    fun prepare(text: String) {
        worker.execute {
            if (!cache.containsKey(text)) runCatching { cache[text] = tts.generate(text, 0, 1.0f).samples }
        }
    }

    fun release() {
        stop()
        worker.execute { tts.release() }
        worker.shutdown()
    }

    companion object {
        private const val DIR = "friday-voice"
        private const val COPY = ".copied-1"

        /** The pronunciation rules (espeak-ng) must be real files: copied out of the APK once. */
        private fun espeakData(context: Context): File {
            val out = File(context.filesDir, "$DIR/espeak-ng-data")
            if (File(out, COPY).exists()) return out
            out.deleteRecursively()
            copy(context.assets, "$DIR/espeak-ng-data", out)
            File(out, COPY).writeText("ok")
            return out
        }

        private fun copy(assets: AssetManager, path: String, to: File) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                to.parentFile?.mkdirs()
                assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
                return
            }
            to.mkdirs()
            for (child in children) copy(assets, "$path/$child", File(to, child))
        }
    }
}

/** The phone's own text-to-speech, in Indian English when it has it, with a woman's voice where one exists. */
private class PhoneVoice(context: Context) {

    private lateinit var tts: TextToSpeech
    @Volatile private var ready = false
    private var pending: Pair<String, () -> Unit>? = null
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                pickVoice()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) {}
                    override fun onDone(id: String) { callbacks.remove(id)?.invoke() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String) { callbacks.remove(id)?.invoke() }
                    override fun onError(id: String, errorCode: Int) { callbacks.remove(id)?.invoke() }
                })
                ready = true
                pending?.let { (text, done) -> speak(text, done) }
                pending = null
            } else {
                // No speech engine: carry on silently (the screen still shows what Friday says).
                pending?.second?.invoke()
                pending = null
            }
        }
    }

    private fun pickVoice() {
        val india = Locale("en", "IN")
        tts.language = if (tts.isLanguageAvailable(india) >= TextToSpeech.LANG_AVAILABLE) india else Locale.UK
        val country = tts.voice?.locale?.country
        // Google's offline Indian-English women's voices are "en-in-x-ahp" and "en-in-x-cxx"
        // (other engines name theirs "female").
        val voices = tts.voices
            ?.filter { it.locale.language == "en" && it.locale.country == country && !it.isNetworkConnectionRequired }
            .orEmpty()
        voices.firstOrNull { v -> listOf("-ahp", "-cxx", "female").any { v.name.contains(it, ignoreCase = true) } }
            ?.let { tts.voice = it }
        tts.setPitch(1.05f)
        tts.setSpeechRate(1.08f)
    }

    fun speak(text: String, done: () -> Unit) {
        if (!ready) {
            pending?.second?.invoke()
            pending = text to done
            return
        }
        val id = UUID.randomUUID().toString()
        callbacks[id] = done
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) callbacks.remove(id)?.invoke()
    }

    fun stop() {
        if (ready) runCatching { tts.stop() }
        // Whatever was cut off still counts as finished.
        callbacks.keys.toList().forEach { callbacks.remove(it)?.invoke() }
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        callbacks.clear()
    }
}
