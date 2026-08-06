/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network.parsing

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.matrix.android.sdk.api.session.room.model.TopicContent
import org.matrix.android.sdk.api.session.room.model.TopicRepresentation
import java.lang.reflect.Type

/**
 * Reads an MSC3765 topic block in either shape: the spec's `{"m.text": [...]}` wrapper, or the bare
 * `[...]` array matrix-js-sdk emitted until v38.1.0, which is still what most topics in the wild carry.
 * Anything else yields null rather than throwing, so a malformed block can't take the legacy `topic`
 * field down with it.
 */
internal object TopicContentJsonAdapterFactory : JsonAdapter.Factory {

    private const val M_TEXT = "m.text"

    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (annotations.isNotEmpty() || Types.getRawType(type) != TopicContent::class.java) return null
        val representations = moshi.adapter<List<TopicRepresentation>?>(
                Types.newParameterizedType(List::class.java, TopicRepresentation::class.java)
        )
        return object : JsonAdapter<TopicContent>() {

            override fun fromJson(reader: JsonReader): TopicContent? = when (reader.peek()) {
                JsonReader.Token.BEGIN_ARRAY -> TopicContent(representations.fromJson(reader))
                JsonReader.Token.BEGIN_OBJECT -> {
                    var textRepresentations: List<TopicRepresentation>? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == M_TEXT) {
                            textRepresentations = representations.fromJson(reader)
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                    TopicContent(textRepresentations)
                }
                else -> {
                    reader.skipValue()
                    null
                }
            }

            override fun toJson(writer: JsonWriter, value: TopicContent?) {
                if (value == null) {
                    writer.nullValue()
                    return
                }
                writer.beginObject()
                writer.name(M_TEXT)
                representations.toJson(writer, value.textRepresentations)
                writer.endObject()
            }
        }
    }
}
