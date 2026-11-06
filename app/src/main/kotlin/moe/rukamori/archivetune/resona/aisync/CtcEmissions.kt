/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/** Per-frame log probabilities from a CTC model (ONNX Runtime; NNAPI when asked, CPU otherwise). */
object CtcEmissions {
    private const val WINDOW = 30 * 16_000

    suspend fun compute(modelPath: String, audio: FloatArray, threads: Int, nnapi: Boolean): Array<FloatArray> {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            if (nnapi) addNnapi()
        }
        val frames = ArrayList<FloatArray>(audio.size / 320 + 1)
        env.createSession(modelPath, options).use { session ->
            val input = session.inputNames.first()
            var offset = 0
            while (offset < audio.size) {
                currentCoroutineContext().ensureActive()
                val piece = normalized(audio, offset, min(audio.size, offset + WINDOW))
                OnnxTensor.createTensor(env, FloatBuffer.wrap(piece), longArrayOf(1, piece.size.toLong())).use { tensor ->
                    session.run(mapOf(input to tensor)).use { result ->
                        val logits = result[0] as OnnxTensor
                        val shape = logits.info.shape
                        val buffer = logits.floatBuffer
                        val vocabulary = shape[2].toInt()
                        repeat(shape[1].toInt()) {
                            val row = FloatArray(vocabulary)
                            buffer.get(row)
                            frames += logSoftmax(row)
                        }
                    }
                }
                offset += WINDOW
            }
        }
        return frames.toTypedArray()
    }

    /** Zero mean, unit variance, as the model's feature extractor does. */
    private fun normalized(audio: FloatArray, from: Int, to: Int): FloatArray {
        val count = (to - from).coerceAtLeast(1)
        var mean = 0.0
        for (i in from until to) mean += audio[i]
        mean /= count
        var variance = 0.0
        for (i in from until to) variance += (audio[i] - mean) * (audio[i] - mean)
        val scale = sqrt(variance / count + 1e-7)
        return FloatArray(to - from) { ((audio[from + it] - mean) / scale).toFloat() }
    }

    private fun logSoftmax(row: FloatArray): FloatArray {
        var top = Float.NEGATIVE_INFINITY
        for (v in row) if (v > top) top = v
        var sum = 0.0
        for (v in row) sum += exp((v - top).toDouble())
        val log = ln(sum).toFloat()
        return FloatArray(row.size) { row[it] - top - log }
    }
}
