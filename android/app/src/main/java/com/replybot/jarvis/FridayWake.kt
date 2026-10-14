package com.replybot.jarvis

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream

/**
 * "Hey Friday", heard on the phone (no audio leaves it) by sherpa-onnx's keyword spotter: a
 * tiny speech model (assets/friday, Apache-2.0) that listens for "Hey Friday" or just "Friday".
 * "Friday" alone also fires on "see you on Friday": the server checks the words around it (the
 * command's audio starts just before the wake word) and stays quiet when it wasn't said to her.
 */
class FridayWake(assets: AssetManager) : AutoCloseable {

    companion object {
        /** 80 ms of 16 kHz mono audio per call. */
        const val CHUNK = 1280
        const val SAMPLE_RATE = 16_000
        private const val DIR = "friday"
        private const val TARGET = 3000.0   // speech loudness the model knows
        private const val GATE = 60.0       // below this it's quiet: keep the gain as it is
        private const val MAX_GAIN = 12.0
    }

    private val spotter = KeywordSpotter(
        assetManager = assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$DIR/encoder.int8.onnx",
                    decoder = "$DIR/decoder.int8.onnx",
                    joiner = "$DIR/joiner.int8.onnx",
                ),
                tokens = "$DIR/tokens.txt",
                numThreads = 1,
                provider = "cpu",
            ),
            maxActivePaths = 4,
            // "▁HE Y ▁F RI DAY :2.5 #0.15 @HEY_FRIDAY": the phrase as the model's word pieces,
            // with its boost (:) and how sure the model must be (#), and the same for "Friday".
            keywordsFile = "$DIR/keywords.txt",
            keywordsScore = 2.5f,
            keywordsThreshold = 0.15f,
            numTrailingBlanks = 1,
        ),
    )
    private var stream: OnlineStream = spotter.createStream()
    private val samples = FloatArray(CHUNK)

    // A quiet mic (one vivo hears normal speech at ~300 of 32768) hides the phrase from the model:
    // lift speech toward the loudness it learnt from, never boosting silence. On the user's own
    // recording this took the model from hearing "history" to hearing "Friday".
    private var gain = 1f

    /** True when this 80 ms of audio completes "Hey Friday" (or "Friday"). */
    fun process(chunk: ShortArray): Boolean {
        var sum = 0.0
        for (s in chunk) sum += s.toDouble() * s
        val level = kotlin.math.sqrt(sum / chunk.size)
        val want = if (level > GATE) (TARGET / level).coerceIn(1.0, MAX_GAIN) else gain.toDouble()
        gain += ((want - gain) * if (want < gain) 0.5 else 0.25).toFloat()
        for (i in chunk.indices) samples[i] = (chunk[i] * gain / 32768f).coerceIn(-1f, 1f)
        stream.acceptWaveform(samples, SAMPLE_RATE)
        var heard = false
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotBlank()) {
                heard = true
                spotter.reset(stream)
            }
        }
        return heard
    }

    /** Forget what was heard, so the same words can't fire twice. */
    fun reset() {
        stream.release()
        stream = spotter.createStream()
    }

    override fun close() {
        stream.release()
        spotter.release()
    }
}
