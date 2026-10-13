package com.replybot.jarvis

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM to and from WAV files (what the speech-to-text service takes). */
object Wav {

    fun encode(pcm: ShortArray, sampleRate: Int = WakeWord.SAMPLE_RATE): ByteArray {
        val data = pcm.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            pcm.forEach { putShort(it) }
        }.array()
    }

    /** The samples of a 16-bit mono WAV (chunks before "data" are skipped). */
    fun decode(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= buf.limit()) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(size, buf.limit() - pos - 8) / 2
                return ShortArray(n) { buf.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + size + (size and 1)
        }
        return ShortArray(0)
    }
}
