package com.replybot.jarvis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * "Hey Jarvis", detected on the phone with openWakeWord's models (nothing leaves the phone).
 *
 * A streaming port of openwakeword's AudioFeatures + Model.predict, fed one 80 ms chunk
 * (1280 samples of 16 kHz mono PCM) at a time:
 * audio -> melspectrogram (32 bins per 10 ms, scaled x/10 + 2) -> one 96-number embedding of
 * the last 76 mel frames per chunk -> the wake model scores the last 16 embeddings (0..1).
 * Checked against the Python package in WakeWordTest.
 */
class WakeWord(melModel: ByteArray, embeddingModel: ByteArray, wakeModel: ByteArray) : AutoCloseable {

    companion object {
        /** Samples per call: 80 ms at 16 kHz. */
        const val CHUNK = 1280
        const val SAMPLE_RATE = 16_000
        /** Default threshold; openWakeWord's own default is 0.5. */
        const val THRESHOLD = 0.5f

        private const val CONTEXT = 480       // earlier samples the melspectrogram needs before a chunk
        private const val MEL_BINS = 32
        private const val WINDOW = 76         // mel frames per embedding
        private const val STEP = 8            // mel frames per chunk
        private const val MAX_MEL = 970
        private const val MAX_FEATURES = 120
        private const val WARMUP = 5          // the first scores are unreliable, as in openWakeWord
    }

    private val env = OrtEnvironment.getEnvironment()
    private val mel: OrtSession = env.createSession(melModel)
    private val embedding: OrtSession = env.createSession(embeddingModel)
    private val wake: OrtSession = env.createSession(wakeModel)
    private val wakeInput = wake.inputNames.first()
    private val wakeFrames = (wake.inputInfo.getValue(wakeInput).info as TensorInfo).shape[1].toInt()

    private val melFrames = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private var tail = FloatArray(0)
    private var predictions = 0

    init {
        reset()
    }

    /** Forget all audio, e.g. after a detection so the same words don't fire twice. */
    fun reset() {
        melFrames.clear()
        repeat(WINDOW) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        // Like openWakeWord, start from embeddings of 4 s of quiet noise so the buffer is full.
        val rnd = Random(0)
        val spec = melspectrogram(FloatArray(SAMPLE_RATE * 4) { rnd.nextInt(-1000, 1000).toFloat() })
        features.clear()
        var i = 0
        while (i + WINDOW <= spec.size) {
            features.addLast(embed(spec, i))
            i += STEP
        }
        tail = FloatArray(0)
        predictions = 0
    }

    /** The wake score (0..1) after this 80 ms of audio. */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK) { "need $CHUNK samples, got ${chunk.size}" }
        val audio = FloatArray(tail.size + CHUNK)
        tail.copyInto(audio)
        for (j in chunk.indices) audio[tail.size + j] = chunk[j].toFloat()
        tail = audio.copyOfRange(audio.size - CONTEXT, audio.size)

        melspectrogram(audio).forEach { melFrames.addLast(it) }
        while (melFrames.size > MAX_MEL) melFrames.removeFirst()
        features.addLast(embed(melFrames, melFrames.size - WINDOW))
        while (features.size > MAX_FEATURES) features.removeFirst()

        val score = score()
        predictions++
        return if (predictions <= WARMUP) 0f else score
    }

    /** Mel frames of [audio] (raw int16 values as floats), scaled like openWakeWord. */
    private fun melspectrogram(audio: FloatArray): List<FloatArray> {
        val out = run(mel, "input", audio, longArrayOf(1, audio.size.toLong()))
        return List(out.size / MEL_BINS) { f -> FloatArray(MEL_BINS) { b -> out[f * MEL_BINS + b] / 10f + 2f } }
    }

    /** The embedding of [WINDOW] mel frames starting at [from]. */
    private fun embed(frames: List<FloatArray>, from: Int): FloatArray {
        val input = FloatArray(WINDOW * MEL_BINS)
        for (f in 0 until WINDOW) frames[from + f].copyInto(input, f * MEL_BINS)
        return run(embedding, "input_1", input, longArrayOf(1, WINDOW.toLong(), MEL_BINS.toLong(), 1))
    }

    private fun score(): Float {
        val n = features.size
        val dim = features.first().size
        val input = FloatArray(wakeFrames * dim)
        for (f in 0 until wakeFrames) features[n - wakeFrames + f].copyInto(input, f * dim)
        return run(wake, wakeInput, input, longArrayOf(1, wakeFrames.toLong(), dim.toLong()))[0]
    }

    private fun run(session: OrtSession, inputName: String, data: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val buf = (result[0] as OnnxTensor).floatBuffer
                FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }

    override fun close() {
        mel.close()
        embedding.close()
        wake.close()
    }
}
