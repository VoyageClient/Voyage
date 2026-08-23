/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation.ondevice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Pure-Kotlin inference for fastText's quantized `lid.176.ftz` language-identification model
 * (product-quantized input matrix, dense output, hierarchical-softmax loss). Gives NLLB its
 * required source language without any native code or network.
 */
class FastTextLanguageId private constructor(
        private val dim: Int,
        private val minn: Int,
        private val maxn: Int,
        private val bucket: Int,
        private val nwords: Int,
        private val wordIds: HashMap<String, Int>,
        private val wordTypes: ByteArray,
        private val pruneIdx: HashMap<Int, Int>,
        private val input: QuantMatrix,
        private val output: FloatArray,
        private val labels: List<String>,
        private val paths: Array<IntArray>,
        private val codes: Array<BooleanArray>,
) {
    private class QuantMatrix(
            val nsubq: Int,
            val dsub: Int,
            val lastDsub: Int,
            val codes: ByteArray,
            val centroids: FloatArray,
            val normCodes: ByteArray?,
            val normCentroids: FloatArray?,
    ) {
        fun addRow(row: Int, agg: FloatArray) {
            val norm = if (normCodes != null && normCentroids != null) normCentroids[normCodes[row].toInt() and 0xFF] else 1f
            for (s in 0 until nsubq) {
                val code = codes[row * nsubq + s].toInt() and 0xFF
                val ds = if (s < nsubq - 1) dsub else lastDsub
                val base = s * 256 * dsub + code * ds
                for (k in 0 until ds) {
                    agg[s * dsub + k] += norm * centroids[base + k]
                }
            }
        }
    }

    /** Returns the best "__label__xx" suffix (e.g. "uk"), or null when nothing matched. */
    fun detect(text: String): String? {
        val rows = ArrayList<Int>()
        for (token in text.lowercase().split(WHITESPACE)) {
            if (token.isEmpty()) continue
            val wid = wordIds[token] ?: -1
            if (wid >= 0 && wordTypes[wid].toInt() == 0) {
                rows.add(wid)
                addSubwords(token, rows)
            } else if (wid < 0) {
                addSubwords(token, rows)
            }
        }
        wordIds["</s>"]?.let { rows.add(it) }
        if (rows.isEmpty()) return null

        val hidden = FloatArray(dim)
        for (row in rows) input.addRow(row, hidden)
        for (i in hidden.indices) hidden[i] = hidden[i] / rows.size

        var bestScore = Double.NEGATIVE_INFINITY
        var bestLabel: String? = null
        for (li in labels.indices) {
            var logProb = 0.0
            val path = paths[li]
            val code = codes[li]
            for (p in path.indices) {
                var dot = 0f
                val base = path[p] * dim
                for (k in 0 until dim) dot += output[base + k] * hidden[k]
                val sig = 1.0 / (1.0 + exp(-dot.toDouble()))
                logProb += ln(max(1e-9, if (code[p]) sig else 1.0 - sig))
            }
            if (logProb > bestScore) {
                bestScore = logProb
                bestLabel = labels[li]
            }
        }
        return bestLabel?.removePrefix("__label__")
    }

    private fun addSubwords(word: String, rows: ArrayList<Int>) {
        val wrapped = "<$word>"
        val chars = ArrayList<String>(wrapped.length)
        var i = 0
        while (i < wrapped.length) {
            val cp = wrapped.codePointAt(i)
            val len = Character.charCount(cp)
            chars.add(wrapped.substring(i, i + len))
            i += len
        }
        val n = chars.size
        for (start in 0 until n) {
            val ngram = StringBuilder()
            var end = start
            while (end < n) {
                ngram.append(chars[end])
                end++
                val len = end - start
                if (len in minn..maxn && len != n) {
                    pushHash(rows, (hash(ngram.toString()).toLong() and 0xFFFFFFFFL).mod(bucket.toLong()).toInt())
                }
                if (len >= maxn) break
            }
        }
    }

    private fun pushHash(rows: ArrayList<Int>, hashId: Int) {
        val mapped = pruneIdx[hashId] ?: return
        rows.add(nwords + mapped)
    }

    companion object {
        private val WHITESPACE = Regex("""\s+""")

        // FNV-1a with fastText's signed-byte quirk.
        private fun hash(s: String): Int {
            var h = -2128831035 // 2166136261 as Int
            for (byte in s.toByteArray(Charsets.UTF_8)) {
                h = h xor byte.toInt()
                h *= 16777619
            }
            return h
        }

        fun load(file: File): FastTextLanguageId {
            val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            check(buf.int == 793712314) { "Not a fastText model" }
            buf.int // version
            val dim = buf.int
            repeat(4) { buf.int } // ws, epoch, minCount, neg
            buf.int // wordNgrams (1 for lid.176 — word n-grams unused)
            val loss = buf.int
            buf.int // model
            val bucket = buf.int
            val minn = buf.int
            val maxn = buf.int
            buf.int // lrUpdateRate
            buf.double // t
            check(loss == 1) { "Only hierarchical-softmax models supported" }

            val size = buf.int
            val nwords = buf.int
            val nlabels = buf.int
            buf.long // ntokens
            val pruneIdxSize = buf.long
            val wordIds = HashMap<String, Int>(size * 2)
            val wordTypes = ByteArray(size)
            val labels = ArrayList<String>(nlabels)
            val labelCounts = ArrayList<Long>(nlabels)
            val wordBytes = java.io.ByteArrayOutputStream()
            for (i in 0 until size) {
                wordBytes.reset()
                while (true) {
                    val b = buf.get()
                    if (b.toInt() == 0) break
                    wordBytes.write(b.toInt())
                }
                val word = wordBytes.toString("UTF-8")
                val count = buf.long
                val type = buf.get()
                wordIds[word] = i
                wordTypes[i] = type
                if (type.toInt() == 1) {
                    labels.add(word)
                    labelCounts.add(count)
                }
            }
            val pruneIdx = HashMap<Int, Int>()
            repeat(pruneIdxSize.toInt().coerceAtLeast(0)) { pruneIdx[buf.int] = buf.int }

            check(buf.get().toInt() == 1) { "Expected a quantized input matrix" }
            val input = loadQuantMatrix(buf)
            val qout = buf.get().toInt() == 1
            check(!qout) { "Quantized output matrix not supported" }
            val m = buf.long.toInt()
            val n = buf.long.toInt()
            val output = FloatArray(m * n)
            for (i in output.indices) output[i] = buf.float

            val (paths, codes) = buildTree(labelCounts)
            return FastTextLanguageId(dim, minn, maxn, bucket, nwords, wordIds, wordTypes, pruneIdx, input, output, labels, paths, codes)
        }

        private fun loadQuantMatrix(buf: ByteBuffer): QuantMatrix {
            val qnorm = buf.get().toInt() == 1
            val rows = buf.long.toInt()
            buf.long // n (cols)
            val codeSize = buf.int
            val codes = ByteArray(codeSize).also { buf.get(it) }
            val pqDim = buf.int
            val nsubq = buf.int
            val dsub = buf.int
            val lastDsub = buf.int
            val centroids = FloatArray(pqDim * 256)
            for (i in centroids.indices) centroids[i] = buf.float
            var normCodes: ByteArray? = null
            var normCentroids: FloatArray? = null
            if (qnorm) {
                normCodes = ByteArray(rows).also { buf.get(it) }
                val nDim = buf.int
                repeat(3) { buf.int } // nsubq, dsub, lastdsub of the 1-dim norm PQ
                normCentroids = FloatArray(nDim * 256)
                for (i in normCentroids.indices) normCentroids[i] = buf.float
            }
            return QuantMatrix(nsubq, dsub, lastDsub, codes, centroids, normCodes, normCentroids)
        }

        // fastText's Huffman tree over label counts (model.cc buildTree). Labels are stored sorted
        // by descending count, which the two-pointer construction relies on.
        private fun buildTree(counts: List<Long>): Pair<Array<IntArray>, Array<BooleanArray>> {
            val osz = counts.size
            val parent = IntArray(2 * osz - 1) { -1 }
            val binary = BooleanArray(2 * osz - 1)
            val count = LongArray(2 * osz - 1) { Long.MAX_VALUE / 4 }
            for (i in 0 until osz) count[i] = counts[i]
            var leaf = osz - 1
            var node = osz
            for (i in osz until 2 * osz - 1) {
                val mini = IntArray(2)
                for (j in 0 until 2) {
                    if (leaf >= 0 && (node >= i || count[leaf] < count[node])) {
                        mini[j] = leaf--
                    } else {
                        mini[j] = node++
                    }
                }
                count[i] = count[mini[0]] + count[mini[1]]
                parent[mini[0]] = i
                parent[mini[1]] = i
                binary[mini[1]] = true
            }
            val paths = Array(osz) { IntArray(0) }
            val codes = Array(osz) { BooleanArray(0) }
            for (i in 0 until osz) {
                val path = ArrayList<Int>()
                val code = ArrayList<Boolean>()
                var j = i
                while (parent[j] != -1) {
                    path.add(parent[j] - osz)
                    code.add(binary[j])
                    j = parent[j]
                }
                paths[i] = IntArray(path.size) { path[path.size - 1 - it] }
                codes[i] = BooleanArray(code.size) { code[code.size - 1 - it] }
            }
            return paths to codes
        }
    }
}
