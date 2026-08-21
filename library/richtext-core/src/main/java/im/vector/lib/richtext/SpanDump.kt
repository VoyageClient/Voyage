/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

/** Canonical description of a [RichText] in the shape of the Markwon goldens (see GOLDEN.md). */
object SpanDump {

    fun dump(text: RichText): Map<String, Any?> = linkedMapOf(
            "text" to text.text,
            "spans" to spans(text),
    )

    fun spans(text: RichText): List<Map<String, Any?>> = text.spans.map { span ->
        val entry = LinkedHashMap<String, Any?>()
        entry["start"] = span.start
        entry["end"] = span.end
        entry.putAll(describe(span.style))
        entry
    }.sortedWith(
            compareBy<Map<String, Any?>> { it["start"] as Int }
                    .thenByDescending { it["end"] as Int }
                    .thenBy { it["kind"] as String }
                    .thenBy { it.toString() }
    )

    private fun describe(style: RichStyle): Map<String, Any?> = when (style) {
        RichStyle.Bold -> kind("bold")
        RichStyle.Italic -> kind("italic")
        RichStyle.Underline -> kind("underline")
        RichStyle.Strikethrough -> kind("strikethrough")
        RichStyle.Subscript -> kind("subscript")
        RichStyle.Superscript -> kind("superscript")
        is RichStyle.Code -> kind(if (style.isBlock) "codeBlock" else "code")
        is RichStyle.IntermediateCode -> kind("intermediateCode", "block" to style.isBlock)
        is RichStyle.Heading -> kind("heading", "level" to style.level)
        is RichStyle.ListItem -> if (style.number != null) kind("ordered", "number" to style.number) else kind("bullet", "level" to style.level)
        is RichStyle.ListMarker -> kind("listMarker", "source" to style.source)
        is RichStyle.RelativeSize -> kind("relativeSize", "proportion" to style.proportion.toDouble())
        RichStyle.Blockquote -> kind("blockquote")
        is RichStyle.Spoiler -> kind("spoiler")
        is RichStyle.Pill -> kind(
                "pill",
                "id" to style.target.matrixId,
                "itemType" to when (style.target.kind) {
                    PillKind.USER -> "UserItem"
                    PillKind.ROOM -> "RoomItem"
                    PillKind.ROOM_ALIAS -> "RoomAliasItem"
                    PillKind.SPACE -> "SpaceItem"
                    PillKind.EVERYONE -> "EveryoneInRoomItem"
                },
                "displayName" to style.target.displayName,
        )
        is RichStyle.Emote -> kind("emote", "mxcUrl" to style.mxcUrl, "shortcode" to style.shortcode, "body" to style.body)
        is RichStyle.Image -> kind(
                "image",
                "destination" to style.destination,
                "width" to style.size?.width?.let { "${it.value}${it.unit ?: ""}" },
                "height" to style.size?.height?.let { "${it.value}${it.unit ?: ""}" },
        )
        is RichStyle.Maths -> kind("maths", "latex" to style.latex, "display" to if (style.isBlock) "block" else "inline")
        is RichStyle.Link -> kind("link", "url" to style.url)
        is RichStyle.Url -> kind("url", "url" to style.url)
        is RichStyle.MatrixPermalink -> kind("permalink", "url" to style.url)
        is RichStyle.Color -> kind("color", "color" to hex(style.argb))
        is RichStyle.BackgroundColor -> kind("bgColor", "color" to hex(style.argb))
        is RichStyle.VerticalPadding -> kind("verticalPadding", "top" to style.topDp.toDouble(), "bottom" to style.bottomDp.toDouble())
        is RichStyle.LeadingMargin -> kind("leadingMargin", "margin" to style.dp.toDouble())
    }

    private fun kind(kind: String, vararg attrs: Pair<String, Any?>): Map<String, Any?> =
            linkedMapOf<String, Any?>("kind" to kind).apply { attrs.forEach { (k, v) -> if (v != null) put(k, v) } }

    private fun hex(color: Int) = String.format("#%08X", color)
}
