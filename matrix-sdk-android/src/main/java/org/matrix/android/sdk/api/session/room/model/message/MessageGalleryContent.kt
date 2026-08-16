/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.api.util.JsonDict

/**
 * MSC4274 inline media gallery: several media items in one message.
 * Each element of [itemtypes] is a regular media message content
 * (m.image / m.video / m.audio / m.file shape) with `msgtype` renamed to `itemtype`.
 */
@JsonClass(generateAdapter = true)
data class MessageGalleryContent(
        @Json(name = MessageContent.MSG_TYPE_JSON_KEY) override val msgType: String,

        /**
         * The gallery caption, or a textual fallback listing the items.
         */
        @Json(name = "body") override val body: String,

        @Json(name = "itemtypes") val itemtypes: List<JsonDict> = emptyList(),

        @Json(name = "format") override val format: String? = null,
        @Json(name = "formatted_body") override val formattedBody: String? = null,

        @Json(name = "m.relates_to") override val relatesTo: RelationDefaultContent? = null,
        @Json(name = "m.new_content") override val newContent: Content? = null,
        @Json(name = "m.mentions") val mentions: Mentions? = null,
) : MessageContentWithFormattedBody {

    /**
     * The items as regular attachment contents (itemtype remapped back to msgtype).
     * Unparseable items are dropped. Parsing is not free — a caller that also needs the caption or
     * the tiles should hold on to the result and pass it back rather than asking twice per bind.
     */
    fun galleryItems(): List<MessageWithAttachmentContent> = itemtypes.toAttachmentContents()

    companion object {
        const val ITEM_TYPE_JSON_KEY = "itemtype"
    }
}

fun List<JsonDict>.toAttachmentContents(): List<MessageWithAttachmentContent> = mapNotNull { it.toAttachmentContent() }

fun JsonDict.toAttachmentContent(): MessageWithAttachmentContent? {
    val itemType = this[MessageGalleryContent.ITEM_TYPE_JSON_KEY] as? String ?: return null
    val remapped = this - MessageGalleryContent.ITEM_TYPE_JSON_KEY + (MessageContent.MSG_TYPE_JSON_KEY to itemType)
    return remapped.toModel<MessageContent>() as? MessageWithAttachmentContent
}

/**
 * The user caption, or null when [MessageGalleryContent.body] is only the compatibility fallback listing.
 */
fun MessageGalleryContent.galleryCaption(items: List<MessageWithAttachmentContent> = galleryItems()): String? {
    return body.takeIf { it.isNotBlank() && it != galleryFallbackBody(items) }
}

/**
 * Body shown by clients without gallery support when the sender typed no caption
 * (Sable-compatible newline-joined listing).
 */
fun galleryFallbackBody(items: List<MessageWithAttachmentContent>): String {
    return items.joinToString("\n") { "[${it.getFileName()}: ${it.url ?: "file"}]" }
}

@Suppress("UNCHECKED_CAST")
fun Content.toGalleryItem(): JsonDict? {
    val msgType = this[MessageContent.MSG_TYPE_JSON_KEY] as? String ?: return null
    val remapped = this - MessageContent.MSG_TYPE_JSON_KEY + (MessageGalleryContent.ITEM_TYPE_JSON_KEY to msgType)
    return coerceGalleryJsonNumbers(remapped) as JsonDict
}

/**
 * Moshi's Any adapter round-trips every JSON number as Double, so re-serializing an item dict
 * would emit "size":123.0 — Synapse strictly rejects that (M_BAD_JSON "Bad JSON value: float").
 */
fun coerceGalleryJsonNumbers(value: Any?): Any? = when (value) {
    is Double -> if (value.isFinite() && value % 1.0 == 0.0 &&
            value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
        value.toLong()
    } else value
    is Map<*, *> -> value.mapValues { coerceGalleryJsonNumbers(it.value) }
    is List<*> -> value.map { coerceGalleryJsonNumbers(it) }
    else -> value
}

/**
 * Serialize via the concrete class — toContent() reified on the interface has no Moshi adapter.
 */
fun MessageWithAttachmentContent.toAttachmentContentDict(): Content? {
    return when (this) {
        is MessageImageContent -> toContent()
        is MessageVideoContent -> toContent()
        is MessageAudioContent -> toContent()
        is MessageFileContent -> toContent()
        else -> null
    }
}

fun MessageWithAttachmentContent.toGalleryItem(): JsonDict? = toAttachmentContentDict()?.toGalleryItem()
