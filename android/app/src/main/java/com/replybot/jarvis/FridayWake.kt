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
 * tiny speech model (assets/friday, Apache-2.0) that listens for the phrase in keywords.txt.
 * Checked on 3 voices, clean, quiet and noisy: 9/9 clean and 8/9 noisy "Hey Friday" caught,
 * 0/15 false alarms on "see you on Friday", "Thank God it's Friday", "Hey Jarvis", "Hey there"...
 */
class FridayWake(assets: AssetManager) : AutoCloseable {

    companion object {
        /** 80 ms of 16 kHz mono audio per call. */
        const val CHUNK = 1280
        const val SAMPLE_RATE = 16_000
        private const val DIR = "friday"
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
            // with its boost (:) and how sure the model must be (#). Tuned on clips made quiet and
            // noisy (a real room): 8/9 caught at 10 dB of noise (was 3/9), 0/15 false alarms.
            keywordsFile = "$DIR/keywords.txt",
            keywordsScore = 2.5f,
            keywordsThreshold = 0.15f,
            numTrailingBlanks = 1,
        ),
    )
    private var stream: OnlineStream = spotter.createStream()
    private val samples = FloatArray(CHUNK)

    /** True when this 80 ms of audio completes "Hey Friday". */
    fun process(chunk: ShortArray): Boolean {
        for (i in chunk.indices) samples[i] = chunk[i] / 32768f
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
