package com.replybot.jarvis

import com.replybot.data.ReplyApi
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Friday's natural voice: the server's audio is read whatever WAV shape it comes in. */
class NaturalVoiceTest {

    @Test
    fun aSixteenBitWavIsRead() {
        val wav = Wav.encode(shortArrayOf(1, 2, 3, 4), sampleRate = 24_000)
        val a = Wav.read(wav)
        assertNotNull(a)
        a!!
        assertEquals(24_000, a.rate)
        assertEquals(1, a.channels)
        assertEquals(false, a.float)
        assertEquals(4, a.frames)
        assertEquals(44, a.offset)
    }

    @Test
    fun aStreamedWavWithoutSizesIsStillRead() {
        val wav = Wav.encode(shortArrayOf(5, 6, 7), sampleRate = 24_000)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(4, -1)   // RIFF size unknown
        b.putInt(40, -1)  // data size unknown
        assertEquals(3, Wav.read(wav)!!.frames)
    }

    @Test
    fun aFloatWavIsRead() {
        val data = 2 * 4
        val wav = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(3); putShort(1)
            putInt(22_050); putInt(22_050 * 4); putShort(4); putShort(32)
            put("data".toByteArray()); putInt(data)
            putFloat(0.5f); putFloat(-0.5f)
        }.array()
        val a = Wav.read(wav)!!
        assertTrue(a.float)
        assertEquals(2, a.frames)
        assertEquals(22_050, a.rate)
    }

    @Test
    fun notAWavIsRefused() {
        assertNull(Wav.read("{\"detail\":\"no\"}".toByteArray()))
        assertNull(Wav.read(ByteArray(0)))
    }

    @Test
    fun theReplyCarriesItsAudio() {
        val wav = Wav.encode(shortArrayOf(9, 9), sampleRate = 24_000)
        val j = JSONObject().put("heard", "hi").put("action", "answer").put("say", "Hello, boss.")
            .put("audio", Base64.getEncoder().encodeToString(wav))
        assertArrayEquals(wav, ReplyApi.parseJarvis(j).audio)
    }

    @Test
    fun noAudioIsNull() {
        val j = JSONObject().put("heard", "hi").put("action", "answer").put("say", "Hello, boss.").put("audio", JSONObject.NULL)
        assertNull(ReplyApi.parseJarvis(j).audio)
        assertNull(ReplyApi.parseJarvis(JSONObject().put("say", "x")).audio)
    }
}
