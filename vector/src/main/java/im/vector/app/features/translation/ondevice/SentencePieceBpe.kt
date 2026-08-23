/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation.ondevice

import java.io.File
import java.text.Normalizer

/**
 * Pure-Kotlin SentencePiece BPE, enough to run NLLB's `sentencepiece_bpe.model` without the native
 * sentencepiece library: parses the model protobuf for the piece/score table, then encodes by
 * greedily merging the adjacent pair with the best-scoring merged piece (SentencePiece's BPE rule).
 *
 * Ids are in SentencePiece space (0=<unk>, 1=<s>, 2=</s>); [NllbTokenizer] converts to NLLB space.
 */
class SentencePieceBpe private constructor(
        private val pieces: List<String>,
        private val scores: FloatArray,
        private val ids: HashMap<String, Int>,
) {
    fun idOf(piece: String): Int = ids[piece] ?: UNK_ID

    fun pieceOf(id: Int): String = pieces.getOrNull(id).orEmpty()

    fun encode(text: String): IntArray {
        // nmt_nfkc-ish normalization: NFKC plus whitespace collapsing, then the "▁" word marker.
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(Regex("""\s+"""), " ")
                .trim()
        if (normalized.isEmpty()) return IntArray(0)
        val marked = "▁" + normalized.replace(' ', '▁')

        // Start from single code points and merge greedily by score.
        val symbols = ArrayList<String>()
        var i = 0
        while (i < marked.length) {
            val cp = marked.codePointAt(i)
            val len = Character.charCount(cp)
            symbols.add(marked.substring(i, i + len))
            i += len
        }
        while (true) {
            var bestIndex = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (j in 0 until symbols.size - 1) {
                val merged = ids[symbols[j] + symbols[j + 1]] ?: continue
                val score = scores[merged]
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = j
                }
            }
            if (bestIndex < 0) break
            symbols[bestIndex] = symbols[bestIndex] + symbols[bestIndex + 1]
            symbols.removeAt(bestIndex + 1)
        }
        return IntArray(symbols.size) { idOf(symbols[it]) }
    }

    companion object {
        const val UNK_ID = 0

        fun load(file: File): SentencePieceBpe {
            val bytes = file.readBytes()
            val pieces = ArrayList<String>(256000)
            val scores = ArrayList<Float>(256000)
            var pos = 0

            fun readVarint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = bytes[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b < 0x80) return result
                    shift += 7
                }
            }

            fun skip(wireType: Int) {
                when (wireType) {
                    0 -> readVarint()
                    1 -> pos += 8
                    2 -> pos += readVarint().toInt()
                    5 -> pos += 4
                    else -> error("Unsupported wire type $wireType")
                }
            }

            while (pos < bytes.size) {
                val tag = readVarint().toInt()
                val field = tag ushr 3
                val wire = tag and 7
                // The pieces are one consecutive run at the start; everything after (trainer /
                // normalizer specs) is irrelevant, so stop rather than skip-parse it.
                if (field != 1 && pieces.isNotEmpty()) break
                if (field == 1 && wire == 2) {
                    // repeated SentencePiece { string piece = 1; float score = 2; }
                    val end = pos + readVarint().toInt()
                    var piece = ""
                    var score = 0f
                    while (pos < end) {
                        val innerTag = readVarint().toInt()
                        when (innerTag ushr 3) {
                            1 -> {
                                val len = readVarint().toInt()
                                piece = String(bytes, pos, len, Charsets.UTF_8)
                                pos += len
                            }
                            2 -> {
                                score = Float.fromBits(
                                        (bytes[pos].toInt() and 0xFF) or
                                                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                                                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                                                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
                                )
                                pos += 4
                            }
                            else -> skip(innerTag and 7)
                        }
                    }
                    pieces.add(piece)
                    scores.add(score)
                } else {
                    skip(wire)
                }
            }

            val ids = HashMap<String, Int>(pieces.size * 2)
            pieces.forEachIndexed { index, piece -> ids[piece] = index }
            return SentencePieceBpe(pieces, scores.toFloatArray(), ids)
        }
    }
}
