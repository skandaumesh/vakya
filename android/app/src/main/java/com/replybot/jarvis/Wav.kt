package com.replybot.jarvis

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM to and from WAV files (what the speech-to-text service takes). */
object Wav {

    fun encode(pcm: ShortArray, sampleRate: Int = FridayWake.SAMPLE_RATE): ByteArray {
        val data = pcm.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            pcm.forEach { putShort(it) }
        }.array()
    }

    /** What a WAV holds: its format, and where in [bytes] the audio is. */
    class Audio(val rate: Int, val channels: Int, val float: Boolean, val bytes: ByteArray, val offset: Int, val length: Int) {
        val frameBytes get() = channels * (if (float) 4 else 2)
        val frames get() = length / frameBytes
    }

    /** A 16-bit or 32-bit-float WAV, mono or stereo (Friday's natural voice); null if it isn't one.
     *  A streamed WAV may not say how long its audio is: then the rest of the file is audio. */
    fun read(bytes: ByteArray): Audio? {
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            if (id == "fmt " && pos + 24 <= bytes.size) {
                format = buf.getShort(pos + 8).toInt() and 0xFFFF
                channels = buf.getShort(pos + 10).toInt()
                rate = buf.getInt(pos + 12)
                bits = buf.getShort(pos + 22).toInt()
                // WAVE_FORMAT_EXTENSIBLE: the real format is the start of the sub-format GUID.
                if (format == 0xFFFE && size >= 40 && pos + 34 <= bytes.size) format = buf.getShort(pos + 32).toInt() and 0xFFFF
            }
            if (id == "data") {
                val start = pos + 8
                val available = bytes.size - start
                val length = if (size <= 0 || size > available) available else size
                val float = format == 3 && bits == 32
                if (!(float || (format == 1 && bits == 16)) || channels !in 1..2 || rate <= 0) return null
                val frame = channels * (if (float) 4 else 2)
                return Audio(rate, channels, float, bytes, start, length - length % frame)
            }
            if (size < 0) return null
            pos += 8 + size + (size and 1)
        }
        return null
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
