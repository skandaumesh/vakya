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
import com.replybot.data.Store
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Friday's voice. By default "Jenny (Dioco)": an Irish woman's voice, in the spirit of FRIDAY in
 * the films without copying anyone's voice, made on the phone by sherpa-onnx (offline, nothing
 * sent). The phone's own text-to-speech is the fallback while Jenny loads or if she can't, and
 * the user's other choice in Vakya.
 */
class JarvisVoice(context: Context) {

    private val app = context.applicationContext
    private val phone = PhoneVoice(app)
    @Volatile private var jenny: JennyVoice? = null
    private val loader = Executors.newSingleThreadExecutor()

    init {
        loader.execute {
            jenny = runCatching { JennyVoice(app) }
                .onFailure { Log.w("Vakya", "friday: Jenny's voice didn't load (${it.javaClass.simpleName})") }
                .getOrNull()
        }
    }

    /** Say [text], then call [done] (on a background thread). */
    fun speak(text: String, done: () -> Unit) {
        phone.stop()
        jenny?.stop()
        val j = jenny
        if (Store.get(app).fridayVoice == JENNY && j != null) j.speak(text, done) else phone.speak(text, done)
    }

    /** For "Hear her" in Vakya: waits for Jenny to load first. */
    fun preview(text: String) = loader.execute { speak(text) {} }

    fun shutdown() {
        phone.shutdown()
        jenny?.release()
        loader.shutdown()
    }

    companion object {
        const val JENNY = "jenny"
        const val PHONE = "phone"
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
                tts.generateWithCallback(text, 0, 1.0f, play)
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
