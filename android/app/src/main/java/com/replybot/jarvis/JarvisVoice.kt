package com.replybot.jarvis

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Jarvis's voice: the phone's own text-to-speech (offline, free), in Indian English when the
 * phone has it, with a lower, calmer male voice where one exists.
 */
class JarvisVoice(context: Context) {

    private lateinit var tts: TextToSpeech
    @Volatile private var ready = false
    private var pending: Pair<String, () -> Unit>? = null
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
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
                // No speech engine: carry on silently (the screen still shows what Jarvis says).
                pending?.second?.invoke()
                pending = null
            }
        }
    }

    private fun pickVoice() {
        val india = Locale("en", "IN")
        tts.language = if (tts.isLanguageAvailable(india) >= TextToSpeech.LANG_AVAILABLE) india else Locale.UK
        val country = tts.voice?.locale?.country
        // Google's offline Indian-English male voices are "en-in-x-ene" and "en-in-x-end".
        tts.voices
            ?.filter { it.locale.language == "en" && it.locale.country == country && !it.isNetworkConnectionRequired }
            ?.firstOrNull { v -> listOf("-ene", "-end", "male").any { v.name.contains(it, ignoreCase = true) } }
            ?.let { tts.voice = it }
        tts.setPitch(0.92f)
        tts.setSpeechRate(1.0f)
    }

    /** Say [text], then call [done] (on a background thread). */
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

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        callbacks.clear()
    }
}
