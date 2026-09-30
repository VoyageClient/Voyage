/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.devtools

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Types
import okio.Buffer
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.MatrixJsonParser

object DevToolsJson {

    val contentAdapter: JsonAdapter<JsonDict> = MatrixJsonParser.getMoshi()
            .adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    // Lenient so minor hand-editing of the JSON isn't rejected outright. Throws on malformed JSON.
    fun parseLeniently(text: String): JsonDict? {
        return contentAdapter.fromJson(JsonReader.of(Buffer().writeUtf8(text)).apply { isLenient = true })
                ?.let { coerceContent(it) }
    }

    @Suppress("UNCHECKED_CAST")
    fun coerceContent(content: JsonDict?): JsonDict? = coerceWholeDoublesToLongs(content) as? JsonDict

    // Moshi's Any adapter parses every JSON number as Double, so re-serializing would emit "w":1080.0 —
    // Synapse strictly rejects that (M_BAD_JSON "Bad JSON value: float"). Round-trip whole-number Doubles
    // back to Long. Same fix as MessageActionsViewModel / LocalEchoEventFactory.
    private fun coerceWholeDoublesToLongs(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite() && value % 1.0 == 0.0 &&
                value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            value.toLong()
        } else value
        is Map<*, *> -> value.mapValues { coerceWholeDoublesToLongs(it.value) }
        is List<*> -> value.map { coerceWholeDoublesToLongs(it) }
        else -> value
    }
}
