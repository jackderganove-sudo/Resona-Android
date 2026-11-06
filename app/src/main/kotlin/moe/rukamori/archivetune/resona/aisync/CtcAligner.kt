/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max

/**
 * Forced alignment: the lyrics are known, so instead of transcribing, find where
 * their letters lie in a CTC model's per-frame probabilities (Viterbi path through
 * blank → letter → letter …). The words go through the same LyricsAligner as
 * Whisper's. Vocabulary of wav2vec2-base-960h: 0 = blank, 4 = word separator.
 */
object CtcAligner {
    const val BLANK = 0
    const val SEPARATOR = 4
    const val FRAME_SECONDS = 0.02
    private const val ALPHABET = "ETAONIHSRDLUMWCFGYPBVK'XJQZ"
    private const val FIRST_LETTER = 5

    data class Token(val id: Int, val word: Int)

    /** Letters of the model's alphabet; umlauts and accents fold to the plain letter. */
    fun letters(word: String): IntArray {
        val folded = Normalizer.normalize(word.replace("ß", "ss").replace("’", "'"), Normalizer.Form.NFD)
            .uppercase(Locale.ROOT)
        return folded.mapNotNull { c ->
            val index = ALPHABET.indexOf(c)
            if (index >= 0) FIRST_LETTER + index else null
        }.toIntArray()
    }

    fun tokens(words: List<String>): List<Token> {
        val out = ArrayList<Token>()
        words.forEachIndexed { index, word ->
            val letters = letters(word)
            if (letters.isEmpty()) return@forEachIndexed
            if (out.isNotEmpty()) out += Token(SEPARATOR, index)
            letters.forEach { out += Token(it, index) }
        }
        return out
    }

    /** First frame of each token on the best path (monotonic); [emissions] = log probabilities per frame. */
    fun path(emissions: Array<FloatArray>, tokens: IntArray): IntArray {
        val frames = emissions.size
        val count = tokens.size
        if (count == 0) return IntArray(0)
        if (frames < count) return IntArray(count) { it * frames / count }
        val advanced = java.util.BitSet((frames + 1) * (count + 1))
        var scores = FloatArray(count + 1) { Float.NEGATIVE_INFINITY }
        var next = FloatArray(count + 1)
        scores[0] = 0f
        for (t in 0 until frames) {
            val row = emissions[t]
            next[0] = scores[0] + row[BLANK]
            for (j in 1..count) {
                // Stay: blank, or the letter just entered held on.
                val stay = scores[j] + max(row[BLANK], row[tokens[j - 1]])
                val enter = scores[j - 1] + row[tokens[j - 1]]
                if (enter > stay) {
                    next[j] = enter
                    advanced.set(t * (count + 1) + j)
                } else {
                    next[j] = stay
                }
            }
            val swap = scores
            scores = next
            next = swap
        }
        val starts = IntArray(count)
        var j = count
        var t = frames - 1
        while (j > 0 && t >= 0) {
            if (advanced.get(t * (count + 1) + j)) {
                starts[j - 1] = t
                j--
            }
            t--
        }
        return starts
    }

    /**
     * Words on the best path, each with its mean letter probability. Words the
     * model doesn't really hear (below [minConfidence]) are left out, so the
     * aligner interpolates them as it does for Whisper's gaps.
     */
    fun words(emissions: Array<FloatArray>, lyricsWords: List<String>, minConfidence: Double): List<RecognizedWord> {
        val tokens = tokens(lyricsWords)
        val starts = path(emissions, IntArray(tokens.size) { tokens[it].id })
        val out = ArrayList<RecognizedWord>()
        var i = 0
        while (i < tokens.size) {
            val word = tokens[i].word
            if (tokens[i].id == SEPARATOR) {
                i++
                continue
            }
            var last = i
            var sum = 0.0
            var letters = 0
            while (last < tokens.size && tokens[last].word == word && tokens[last].id != SEPARATOR) {
                sum += exp(emissions[starts[last]][tokens[last].id].toDouble())
                letters++
                last++
            }
            val endFrame = starts.getOrNull(last)?.takeIf { it > starts[last - 1] } ?: (starts[last - 1] + 1)
            if (sum / letters >= minConfidence) {
                out += RecognizedWord(lyricsWords[word], starts[i] * FRAME_SECONDS, endFrame * FRAME_SECONDS)
            }
            i = last
        }
        return out
    }
}
