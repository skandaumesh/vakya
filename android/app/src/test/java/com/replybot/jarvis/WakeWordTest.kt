package com.replybot.jarvis

import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone's "Hey Jarvis" detector against the official openWakeWord package, on clips of
 * three Windows voices: reference_scores.json holds Python's score after every 80 ms chunk.
 */
class WakeWordTest {

    companion object {
        private lateinit var detector: WakeWord
        private val models = File("src/main/assets/jarvis")
        private val clips = File("src/test/resources/jarvis")

        @BeforeClass
        @JvmStatic
        fun load() {
            detector = WakeWord(
                File(models, "melspectrogram.onnx").readBytes(),
                File(models, "embedding_model.onnx").readBytes(),
                File(models, "hey_jarvis_v0.1.onnx").readBytes(),
            )
        }

        @AfterClass
        @JvmStatic
        fun close() = detector.close()
    }

    /** 16 kHz mono 16-bit PCM from a WAV file (any chunks before "data" are skipped). */
    private fun pcm(file: File): ShortArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(pos + it) })
            val size = bytes.getInt(pos + 4)
            if (id == "data") {
                return ShortArray(size / 2) { bytes.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + size + (size and 1)
        }
        error("no audio in $file")
    }

    private fun scores(clip: String): List<Float> {
        detector.reset()
        val audio = pcm(File(clips, "$clip.wav"))
        return (0..audio.size - WakeWord.CHUNK step WakeWord.CHUNK).map {
            detector.process(audio.copyOfRange(it, it + WakeWord.CHUNK))
        }
    }

    private val reference by lazy { JSONObject(File(clips, "reference_scores.json").readText()) }

    private fun python(clip: String): List<Double> =
        reference.getJSONArray(clip).let { arr -> List(arr.length()) { arr.getDouble(it) } }

    @Test
    fun hearsHeyJarvisInEveryVoice() {
        for (clip in listOf("jarvis1-David", "jarvis2-Zira", "jarvis3-Hazel")) {
            val max = scores(clip).max()
            assertTrue("$clip scored $max", max > 0.9f)
        }
    }

    @Test
    fun ignoresOtherSpeechIncludingHeyGoogle() {
        for (clip in listOf("neg_google-David", "neg_chat-Hazel", "neg_service-Zira")) {
            val max = scores(clip).max()
            assertTrue("$clip scored $max", max < 0.1f)
        }
    }

    @Test
    fun matchesTheOfficialPythonScoresChunkByChunk() {
        for (clip in listOf("jarvis1-David", "neg_google-David")) {
            val ours = scores(clip)
            val theirs = python(clip)
            assertEquals(clip, theirs.size, ours.size)
            // From 1.3 s on, the random warm-up noise has left the 16-frame window.
            for (i in 16 until ours.size) {
                assertEquals("$clip chunk $i", theirs[i], ours[i].toDouble(), 0.02)
            }
        }
    }

    @Test
    fun firesOnceAtTheRightMoment() {
        val ours = scores("jarvis1-David")
        val theirs = python("jarvis1-David")
        assertEquals(theirs.indexOf(theirs.max()).toDouble(), ours.indexOf(ours.max()).toDouble(), 1.0)
    }
}
