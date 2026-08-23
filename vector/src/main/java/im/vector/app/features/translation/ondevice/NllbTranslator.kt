/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation.ondevice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Build
import im.vector.app.features.translation.TranslationLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.BreakIterator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device translation with NLLB-200 (distilled 600M, int8) over ONNX Runtime, ported from
 * RTranslator's NLLB_CACHE pipeline: encoder + kv-cache initializer + single-step decoder, with the
 * embedding/lm-head matrix shared through a dual-purpose session. The source language NLLB requires
 * comes from [FastTextLanguageId]. Requires API 24 (ONNX Runtime's floor) and downloaded models.
 */
@Singleton
class NllbTranslator @Inject constructor(
        private val store: NllbModelStore,
) {
    class NotReadyException(message: String) : Exception(message)

    private val mutex = Mutex()
    private var sessions: Sessions? = null
    private var tokenizer: SentencePieceBpe? = null
    private var languageId: FastTextLanguageId? = null

    private class Sessions(
            val env: OrtEnvironment,
            val encoder: OrtSession,
            val decoder: OrtSession,
            val cacheInit: OrtSession,
            val embedAndLmHead: OrtSession,
    )

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    val isAvailable: Boolean get() = isSupported && store.isReady()

    /** Translates [text] to [targetGoogleId]; returns the text and the detected source language id. */
    suspend fun translate(text: String, targetGoogleId: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        if (!isAvailable) throw NotReadyException("On-device models not downloaded")
        mutex.withLock {
            ensureLoaded()
            val detectedRaw = languageId!!.detect(text)
            val detectedGoogle = detectedRaw?.let { TranslationLanguages.normalize(it) }
                    ?.takeIf { it in NllbLanguages.supportedGoogleIds }
            val sourceFlores = NllbLanguages.floresOf(detectedGoogle ?: "en")!!
            val targetFlores = NllbLanguages.floresOf(targetGoogleId)
                    ?: throw NotReadyException("Language $targetGoogleId is not supported by NLLB")
            // NLLB silently drops the {{n}} placeholders that protect links/emoji/mentions, so the
            // model never sees them: translate the text between placeholders and stitch them back.
            val result = buildString {
                var index = 0
                for (match in im.vector.app.features.translation.TranslationExceptions.PLACEHOLDER.findAll(text)) {
                    append(translatePreservingEdges(text.substring(index, match.range.first), sourceFlores, targetFlores))
                    append(match.value)
                    index = match.range.last + 1
                }
                append(translatePreservingEdges(text.substring(index), sourceFlores, targetFlores))
            }
            result to detectedGoogle
        }
    }

    private fun ensureLoaded() {
        if (tokenizer == null) tokenizer = SentencePieceBpe.load(store.fileFor("sentencepiece_bpe.model"))
        if (languageId == null) languageId = FastTextLanguageId.load(store.fileFor("lid.176.ftz"))
        if (sessions == null) {
            val env = OrtEnvironment.getEnvironment()
            fun open(name: String): OrtSession {
                // Same options RTranslator settled on: they cut peak RAM roughly in half.
                val options = OrtSession.SessionOptions()
                options.setMemoryPatternOptimization(false)
                options.setCPUArenaAllocator(false)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                return env.createSession(store.fileFor(name).path, options)
            }
            sessions = Sessions(
                    env = env,
                    encoder = open("NLLB_encoder.onnx"),
                    decoder = open("NLLB_decoder.onnx"),
                    cacheInit = open("NLLB_cache_initializer.onnx"),
                    embedAndLmHead = open("NLLB_embed_and_lm_head.onnx"),
            )
        }
    }

    private fun translatePreservingEdges(part: String, sourceFlores: String, targetFlores: String): String {
        if (part.isBlank() || part.none { it.isLetter() }) return part
        val translated = translateSentences(part.trim(), sourceFlores, targetFlores)
        return part.takeWhile { it.isWhitespace() } + translated + part.takeLastWhile { it.isWhitespace() }
    }

    // --- tokenization (SentencePiece ids -> NLLB ids) ---

    private fun tokenize(text: String, sourceFlores: String): IntArray {
        val sp = tokenizer!!
        val raw = sp.encode(text)
        val adjusted = IntArray(raw.size) { i ->
            when (val id = raw[i] + 1) {
                1 -> 3 // <unk>
                2 -> 0 // <s>
                3 -> 2 // </s>
                else -> id
            }
        }
        val srcToken = NllbLanguages.tokenIdOf(sourceFlores)!!
        return intArrayOf(srcToken, *adjusted, EOS)
    }

    private fun detokenize(ids: List<Int>): String {
        val sp = tokenizer!!
        val out = StringBuilder()
        for (id in ids) {
            if (id in 4 until 256000) out.append(sp.pieceOf(id - 1))
        }
        return out.toString().removePrefix("▁").replace('▁', ' ')
    }

    // --- inference ---

    private fun translateSentences(text: String, sourceFlores: String, targetFlores: String): String {
        // Split into sentences, then rejoin neighbours while they fit the model's input budget.
        val pieces = ArrayList<String>()
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(text)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            pieces.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        if (pieces.isEmpty()) pieces.add(text)
        var joined = true
        while (joined) {
            joined = false
            var i = 1
            while (i < pieces.size) {
                val left = tokenize(pieces[i - 1], sourceFlores).size
                val right = tokenize(pieces[i], sourceFlores).size
                if (left + right < 200 || right < 5) {
                    pieces[i - 1] = pieces[i - 1] + pieces[i]
                    pieces.removeAt(i)
                    joined = true
                } else {
                    i++
                }
            }
        }
        return pieces.joinToString(" ") { piece ->
            translateChunk(tokenize(piece.trim(), sourceFlores), targetFlores)
        }.trim()
    }

    // --- ONNX helpers ---

    private fun env() = sessions!!.env

    private fun int64Tensor(values: IntArray, shape: LongArray = longArrayOf(1, values.size.toLong())): OnnxTensor =
            OnnxTensor.createTensor(env(), LongBuffer.wrap(LongArray(values.size) { values[it].toLong() }), shape)

    private fun emptyFloatTensor(shape: LongArray): OnnxTensor {
        val flat = shape.fold(1L) { acc, d -> acc * d }.toInt()
        return OnnxTensor.createTensor(env(), FloatBuffer.allocate(flat), shape)
    }

    private fun boolTensor(value: Boolean): OnnxTensor =
            OnnxTensor.createTensor(env(), java.nio.ByteBuffer.wrap(byteArrayOf(if (value) 1 else 0)), longArrayOf(1), ai.onnxruntime.OnnxJavaType.BOOL)

    private fun embed(idsTensor: OnnxTensor): OnnxTensor {
        val preLogits = emptyFloatTensor(longArrayOf(1, 1, 1024))
        val useLmHead = boolTensor(false)
        try {
            val input = mapOf("input_ids" to idsTensor, "pre_logits" to preLogits, "use_lm_head" to useLmHead)
            sessions!!.embedAndLmHead.run(input, setOf("embed_matrix")).use { result ->
                // Copy out so the Result can be closed while the value stays usable.
                @Suppress("UNCHECKED_CAST")
                val value = (result.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
                return OnnxTensor.createTensor(env(), value)
            }
        } finally {
            preLogits.close()
            useLmHead.close()
        }
    }

    /** One decoder step for one sequence; returns the step's Result (pre_logits + kv cache). */
    private fun decoderStep(
            tokenId: Int,
            attentionTensor: OnnxTensor,
            initResult: OrtSession.Result,
            past: OrtSession.Result?,
    ): OrtSession.Result {
        val stepTensor = int64Tensor(intArrayOf(tokenId))
        val stepEmbed = embed(stepTensor)
        val decoderInput = HashMap<String, OnnxTensor>()
        decoderInput["input_ids"] = stepTensor
        decoderInput["encoder_attention_mask"] = attentionTensor
        decoderInput["embed_matrix"] = stepEmbed
        val emptyPast = if (past == null) emptyFloatTensor(longArrayOf(1, 16, 0, 64)) else null
        for (layer in 0 until 12) {
            if (past == null) {
                decoderInput["past_key_values.$layer.decoder.key"] = emptyPast!!
                decoderInput["past_key_values.$layer.decoder.value"] = emptyPast
            } else {
                decoderInput["past_key_values.$layer.decoder.key"] = past.get("present.$layer.decoder.key").get() as OnnxTensor
                decoderInput["past_key_values.$layer.decoder.value"] = past.get("present.$layer.decoder.value").get() as OnnxTensor
            }
            decoderInput["past_key_values.$layer.encoder.key"] = initResult.get("present.$layer.encoder.key").get() as OnnxTensor
            decoderInput["past_key_values.$layer.encoder.value"] = initResult.get("present.$layer.encoder.value").get() as OnnxTensor
        }
        val result = sessions!!.decoder.run(decoderInput)
        stepTensor.close()
        stepEmbed.close()
        emptyPast?.close()
        return result
    }

    private fun logitsOf(stepResult: OrtSession.Result): FloatArray {
        val dummyIds = int64Tensor(IntArray(2), longArrayOf(1, 2))
        val useLmHead = boolTensor(true)
        try {
            sessions!!.embedAndLmHead.run(
                    mapOf(
                            "input_ids" to dummyIds,
                            "pre_logits" to stepResult.get("pre_logits").get() as OnnxTensor,
                            "use_lm_head" to useLmHead,
                    ),
                    setOf("logits"),
            ).use { lmResult ->
                @Suppress("UNCHECKED_CAST")
                return ((lmResult.get(0) as OnnxTensor).value as Array<Array<FloatArray>>)[0][0]
            }
        } finally {
            dummyIds.close()
            useLmHead.close()
        }
    }

    private fun maxStepsFor(inputLength: Int) = when {
        inputLength > 30 -> 3 * inputLength
        inputLength > 20 -> 4 * inputLength
        inputLength > 10 -> 5 * inputLength
        inputLength > 5 -> 8 * inputLength
        else -> 64
    }

    private fun translateChunk(inputIds: IntArray, targetFlores: String): String {
        val attentionTensor = int64Tensor(IntArray(inputIds.size) { 1 })
        val inputIdsTensor = int64Tensor(inputIds)
        val embedMatrix = embed(inputIdsTensor)
        val encoderResult = sessions!!.encoder.run(
                mapOf("input_ids" to inputIdsTensor, "attention_mask" to attentionTensor, "embed_matrix" to embedMatrix)
        )
        embedMatrix.close()
        val encoderHidden = encoderResult.get("last_hidden_state").get() as OnnxTensor
        val initResult = sessions!!.cacheInit.run(mapOf("encoder_hidden_states" to encoderHidden))
        val tgtToken = NllbLanguages.tokenIdOf(targetFlores)!!
        try {
            return beamDecode(inputIds.size, attentionTensor, initResult, tgtToken, BEAM_SIZE)
        } finally {
            initResult.close()
            encoderResult.close()
            inputIdsTensor.close()
            attentionTensor.close()
        }
    }

    private class Beam(
            val tokens: List<Int>,
            val score: Double,
            val past: OrtSession.Result,
            val nextInput: Int,
            val finished: Boolean,
    )

    // Beam search with one batch-1 decoder call per live beam per step; siblings share their
    // parent's kv-cache Result, so Results are refcounted and closed when no beam references them.
    private fun beamDecode(inputLength: Int, attentionTensor: OnnxTensor, initResult: OrtSession.Result, tgtToken: Int, beamSize: Int): String {
        val maxSteps = maxStepsFor(inputLength)
        val refs = HashMap<OrtSession.Result, Int>()
        fun retain(r: OrtSession.Result) { refs[r] = (refs[r] ?: 0) + 1 }
        fun release(r: OrtSession.Result) {
            val n = (refs[r] ?: return) - 1
            if (n <= 0) { refs.remove(r); r.close() } else refs[r] = n
        }

        // Steps 1-2 are forced ([eos], then the target-language token), so all beams share them.
        val first = decoderStep(EOS, attentionTensor, initResult, null)
        val second = decoderStep(tgtToken, attentionTensor, initResult, first)
        first.close()
        retain(second)
        var beams = listOf(Beam(emptyList(), 0.0, second, -1, false))
        var expandFromLogits = true // beams[0].past's logits give the first real token candidates

        var step = 0
        try {
            while (step < maxSteps && beams.any { !it.finished }) {
                data class Candidate(val parent: Beam, val past: OrtSession.Result, val token: Int, val score: Double)
                val candidates = ArrayList<Candidate>()
                val stepResults = ArrayList<OrtSession.Result>()
                for (beam in beams) {
                    if (beam.finished) {
                        candidates.add(Candidate(beam, beam.past, -1, beam.score))
                        continue
                    }
                    val past = if (expandFromLogits) beam.past else decoderStep(beam.nextInput, attentionTensor, initResult, beam.past).also {
                        retain(it)
                        stepResults.add(it)
                    }
                    val logits = logitsOf(past)
                    // log-softmax over the vocabulary
                    var maxLogit = Float.NEGATIVE_INFINITY
                    for (v in logits) if (v > maxLogit) maxLogit = v
                    var sumExp = 0.0
                    for (v in logits) sumExp += kotlin.math.exp((v - maxLogit).toDouble())
                    val logSum = kotlin.math.ln(sumExp)
                    // top-beamSize tokens of this beam
                    val top = IntArray(beamSize) { -1 }
                    for (i in logits.indices) {
                        var slot = -1
                        for (k in beamSize - 1 downTo 0) {
                            if (top[k] == -1 || logits[i] > logits[top[k]]) slot = k else break
                        }
                        if (slot >= 0) {
                            for (k in beamSize - 1 downTo slot + 1) top[k] = top[k - 1]
                            top[slot] = i
                        }
                    }
                    for (token in top) {
                        if (token < 0) continue
                        val lp = (logits[token] - maxLogit) - logSum
                        candidates.add(Candidate(beam, past, token, beam.score + lp))
                    }
                }
                val chosen = candidates.sortedByDescending { it.score }.take(beamSize)
                val next = chosen.map { c ->
                    if (c.token < 0) {
                        c.parent
                    } else {
                        retain(c.past)
                        Beam(c.parent.tokens + c.token, c.score, c.past, c.token, c.token == EOS)
                    }
                }
                // Drop references held by the previous generation and unchosen step results.
                beams.forEach { release(it.past) }
                stepResults.forEach { release(it) }
                beams = next
                expandFromLogits = false
                step++
            }
            // Length-normalized pick, preferring finished hypotheses.
            val pool = beams.filter { it.finished }.ifEmpty { beams }
            val best = pool.maxByOrNull { it.score / (it.tokens.size.coerceAtLeast(1)) } ?: return ""
            return detokenize(best.tokens)
        } finally {
            beams.forEach { release(it.past) }
            refs.keys.toList().forEach { it.close() }
            refs.clear()
        }
    }

    companion object {
        private const val EOS = 2
        private const val BEAM_SIZE = 3
    }
}
