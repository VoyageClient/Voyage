/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Wire formats of the two keyless Google endpoints: the batchexecute RPC translate.google.com itself
 * calls (rpcid MkEWBc), and the Chrome dictionary extension's endpoint, used when the former throttles.
 */
internal object GoogleTranslateWire {
    private const val WEB_RPC_ID = "MkEWBc"
    const val WEB_URL = "https://translate.google.com/_/TranslateWebserverUi/data/batchexecute?rpcids=$WEB_RPC_ID"
    const val WEB_REFERER = "https://translate.google.com/"
    const val DICTIONARY_URL = "https://clients5.google.com/translate_a/t"
    const val DICTIONARY_CLIENT = "dict-chrome-ex"

    /** The site's text box limit, in UTF-16 units; longer input is rejected outright. */
    const val WEB_MAX_LENGTH = 5000

    /** The `f.req` form value, argument-for-argument what the site sends. */
    fun webRequest(text: String, source: String, target: String): String {
        val args = JSONArray()
                .put(JSONArray().put(text).put(source).put(target).put(1).put(JSONObject.NULL).put(2))
                .put(JSONArray())
        val call = JSONArray().put(WEB_RPC_ID).put(args.toString()).put(JSONObject.NULL).put("generic")
        return JSONArray().put(JSONArray().put(call)).toString()
    }

    fun parseWebResponse(body: String): Pair<String, String?> {
        val start = body.indexOf('[')
        if (start < 0) throw IOException("Unexpected response")
        val envelopes = JSONArray(body.substring(start))
        val envelope = (0 until envelopes.length())
                .mapNotNull { envelopes.optJSONArray(it) }
                .firstOrNull { it.stringAt(0) == "wrb.fr" && it.stringAt(1) == WEB_RPC_ID }
                ?: throw IOException("Unexpected response")
        // A null payload is the RPC's error reply (e.g. [3] for rejected input).
        val payload = JSONArray(envelope.stringAt(2) ?: throw IOException("Request rejected (${envelope.opt(5)})"))
        // Gendered translations come as several whole-text candidates; the first is taken.
        val candidate = payload.optJSONArray(1)?.optJSONArray(0)?.optJSONArray(0) ?: throw IOException("Empty response")
        val sentences = candidate.optJSONArray(5)
        val translated = if (sentences != null) {
            buildString {
                for (i in 0 until sentences.length()) {
                    val sentence = sentences.optJSONArray(i) ?: continue
                    // Inter-sentence spaces aren't in the text; index 2 flags a sentence that needs one before it.
                    if (sentence.optBoolean(2) && isNotEmpty() && !last().isWhitespace()) append(' ')
                    append(sentence.stringAt(0).orEmpty())
                }
            }
        } else {
            candidate.stringAt(0) ?: throw IOException("Empty response")
        }
        val detected = payload.stringAt(2) ?: payload.optJSONArray(0)?.stringAt(2)
        return translated to detected?.takeIf { it.isNotEmpty() }
    }

    /** `[["text","lang"]]` when the source is auto-detected, `["text"]` when it was given. */
    fun parseDictionaryResponse(body: String): Pair<String, String?> {
        return when (val first = JSONArray(body).opt(0)) {
            is JSONArray -> (first.stringAt(0) ?: throw IOException("Empty response")) to first.stringAt(1)?.takeIf { it.isNotEmpty() }
            is String -> first to null
            else -> throw IOException("Empty response")
        }
    }

    // org.json's optString() turns JSON null into the string "null".
    private fun JSONArray.stringAt(index: Int): String? = if (isNull(index)) null else opt(index) as? String
}
