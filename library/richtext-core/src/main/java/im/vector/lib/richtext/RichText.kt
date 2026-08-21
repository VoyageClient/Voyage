/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

/** Flat rendered text plus the styles applied to ranges of it, in the shape Markwon produces on Android. */
data class RichText(val text: String, val spans: List<RichSpan>) {
    companion object {
        val EMPTY = RichText("", emptyList())
    }
}

data class RichSpan(val start: Int, val end: Int, val style: RichStyle)

data class ImageDimension(val value: Float, val unit: String?)

data class ImageSize(val width: ImageDimension?, val height: ImageDimension?)

data class PillTarget(
        val kind: PillKind,
        val matrixId: String,
        val displayName: String?,
        val avatarUrl: String?,
        // The name the avatar's letter placeholder is drawn from; for @room this is the room name,
        // not the "@room" label. Defaults to [displayName] (MatrixItem.firstLetterOfDisplayName).
        val avatarName: String? = null,
)

sealed class RichStyle {
    object Bold : RichStyle()
    object Italic : RichStyle()
    object Underline : RichStyle()
    object Strikethrough : RichStyle()
    object Subscript : RichStyle()
    object Superscript : RichStyle()
    data class Code(val isBlock: Boolean) : RichStyle()
    data class Heading(val level: Int) : RichStyle()
    data class Link(val url: String) : RichStyle()

    /** A link re-created or auto-detected by the client's linkifier (Android `URLSpan`). */
    data class Url(val url: String) : RichStyle()

    /** A bare Matrix identifier / permalink detected in plain text (Android `MatrixPermalinkSpan`). */
    data class MatrixPermalink(val url: String) : RichStyle()
    data class Image(val destination: String, val size: ImageSize?) : RichStyle()
    data class Emote(val mxcUrl: String, val shortcode: String, val body: String?) : RichStyle()
    data class Pill(val target: PillTarget) : RichStyle()
    data class Spoiler(val reason: String?) : RichStyle()
    data class Color(val argb: Int) : RichStyle()
    data class BackgroundColor(val argb: Int) : RichStyle()
    object Blockquote : RichStyle()
    data class VerticalPadding(val topDp: Int, val bottomDp: Int) : RichStyle()
    data class LeadingMargin(val dp: Int) : RichStyle()
    data class ListMarker(val source: String) : RichStyle()
    data class RelativeSize(val proportion: Float) : RichStyle()
    data class Maths(val latex: String, val isBlock: Boolean) : RichStyle()

    /** Markwon list-item span; replaced by literal [ListMarker] text before the result is returned. */
    internal data class ListItem(val number: Int?, val level: Int) : RichStyle()

    /** Marks `<code>` until the root handler knows whether a `<pre>` wraps it. */
    internal class IntermediateCode(var isBlock: Boolean) : RichStyle()

    override fun toString(): String = this::class.java.simpleName
}
