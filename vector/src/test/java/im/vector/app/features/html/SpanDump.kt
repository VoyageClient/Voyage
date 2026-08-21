/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import io.noties.markwon.core.spans.BulletListItemSpan
import io.noties.markwon.core.spans.EmphasisSpan
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.core.spans.LinkSpan
import io.noties.markwon.core.spans.OrderedListItemSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import io.noties.markwon.html.span.SubScriptSpan
import io.noties.markwon.html.span.SuperScriptSpan
import io.noties.markwon.image.AsyncDrawableSpan
import me.gujun.android.span.style.CustomTypefaceSpan
import me.gujun.android.span.style.VerticalPaddingSpan
import org.matrix.android.sdk.api.session.permalinks.MatrixPermalinkSpan

/**
 * Canonical, platform-neutral description of a rendered [Spanned]: the text plus every span as a
 * semantic `kind` with only the attributes that matter for rendering. Shared by the golden-dump
 * harness and any comparison test; see library/richtext-core/GOLDEN.md for the schema.
 */
object SpanDump {

    /** Pixel → dp conversion for padding/margin spans; the golden must not depend on the test density. */
    var density: Float = 1f

    fun dump(spanned: Spanned): Map<String, Any?> = linkedMapOf(
            "text" to spanned.toString(),
            "spans" to spans(spanned),
    )

    fun spans(spanned: Spanned, only: Collection<Any>? = null): List<Map<String, Any?>> {
        val all = spanned.getSpans(0, spanned.length, Any::class.java)
                .filter { only == null || only.any { o -> o === it } }
                .filterNot { isBindInternal(it) }
        return all.map { span ->
            val entry = LinkedHashMap<String, Any?>()
            entry["start"] = spanned.getSpanStart(span)
            entry["end"] = spanned.getSpanEnd(span)
            entry.putAll(describe(span))
            val flags = spanned.getSpanFlags(span) and Spanned.SPAN_POINT_MARK_MASK
            if (flags != Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) entry["flags"] = flagName(flags)
            entry
        }.sortedWith(
                compareBy<Map<String, Any?>> { it["start"] as Int }
                        .thenByDescending { it["end"] as Int }
                        .thenBy { it["kind"] as String }
                        .thenBy { it.toString() }
        )
    }

    // TextView.setText bookkeeping, not content: the TextView's ChangeWatcher and Markwon's TextViewSpan.
    private fun isBindInternal(span: Any): Boolean {
        val name = span.javaClass.name
        return name == "android.widget.TextView\$ChangeWatcher" || name == "io.noties.markwon.core.spans.TextViewSpan"
    }

    private fun flagName(flags: Int) = when (flags) {
        Spanned.SPAN_INCLUSIVE_EXCLUSIVE -> "inclusiveExclusive"
        Spanned.SPAN_INCLUSIVE_INCLUSIVE -> "inclusiveInclusive"
        Spanned.SPAN_EXCLUSIVE_INCLUSIVE -> "exclusiveInclusive"
        else -> flags.toString()
    }

    private fun describe(span: Any): Map<String, Any?> = when (span) {
        is StrongEmphasisSpan -> kind("bold")
        is EmphasisSpan -> kind("italic")
        is StyleSpan -> when (span.style) {
            Typeface.BOLD -> kind("bold")
            Typeface.ITALIC -> kind("italic")
            Typeface.BOLD_ITALIC -> kind("boldItalic")
            else -> kind("style", "style" to span.style)
        }
        is CustomTypefaceSpan -> {
            val tf = field<Typeface>(span, "tf")
            when {
                tf != null && tf.isItalic && tf.isBold -> kind("boldItalic")
                tf != null && tf.isBold -> kind("bold")
                // EventHtmlRenderer's italicPlugin is the only producer of this span, always italic.
                else -> kind("italic")
            }
        }
        is UnderlineSpan -> kind("underline")
        is StrikethroughSpan -> kind("strikethrough")
        is SubScriptSpan -> kind("subscript")
        is SuperScriptSpan -> kind("superscript")
        is HtmlCodeSpan -> if (span.isBlock) kind("codeBlock") else kind("code")
        is IntermediateCodeSpan -> kind("intermediateCode", "block" to span.isBlock)
        is HeadingSpan -> kind("heading", "level" to span.level)
        is SourceOrderedListItemSpan -> kind("ordered", "number" to span.number)
        is OrderedListItemSpan -> kind("ordered", "number" to field<String>(span, "number"))
        is BulletListItemSpan -> kind("bullet", "level" to field<Int>(span, "level"))
        is ListMarkerSpan -> kind("listMarker", "source" to span.source)
        is RelativeSizeSpan -> kind("relativeSize", "proportion" to span.sizeChange.toDouble())
        is QuoteMarginSpan -> kind("blockquote")
        is SpoilerSpan -> kind("spoiler")
        is PillImageSpan -> kind(
                "pill",
                "id" to span.matrixItem.id,
                "itemType" to span.matrixItem.javaClass.simpleName,
                "displayName" to span.matrixItem.displayName,
        )
        is EmoteImageSpan -> kind("emote", "mxcUrl" to span.mxcUrl, "shortcode" to span.shortcode, "body" to span.body)
        is AsyncDrawableSpan -> {
            val drawable = span.drawable
            if (span.javaClass.name.startsWith("io.noties.markwon.ext.latex.")) {
                kind("maths", "latex" to drawable.destination, "display" to if (span.javaClass.simpleName.contains("Inline")) "inline" else "block")
            } else {
                val size = drawable.imageSize
                kind(
                        "image",
                        "destination" to drawable.destination,
                        "width" to size?.width?.let { "${it.value}${it.unit ?: ""}" },
                        "height" to size?.height?.let { "${it.value}${it.unit ?: ""}" },
                )
            }
        }
        is LinkSpan -> kind("link", "url" to span.link)
        is MatrixPermalinkSpan -> kind("permalink", "url" to field<String>(span, "url"))
        is URLSpan -> kind("url", "url" to span.url)
        is ForegroundColorSpan -> kind("color", "color" to hex(span.foregroundColor))
        is BackgroundColorSpan -> kind("bgColor", "color" to hex(span.backgroundColor))
        is VerticalPaddingSpan -> kind(
                "verticalPadding",
                "top" to dp(field<Int>(span, "paddingTop")),
                "bottom" to dp(field<Int>(span, "paddingBottom")),
        )
        is LeadingMarginSpan.Standard -> kind("leadingMargin", "margin" to dp(span.getLeadingMargin(true)))
        is HiddenImageSpan -> kind("hiddenImage")
        else -> kind(span.javaClass.simpleName.ifEmpty { span.javaClass.name }, "raw" to true, "class" to span.javaClass.name)
    }

    private fun kind(kind: String, vararg attrs: Pair<String, Any?>): Map<String, Any?> =
            linkedMapOf<String, Any?>("kind" to kind).apply { attrs.forEach { (k, v) -> if (v != null) put(k, v) } }

    private fun hex(color: Int) = String.format("#%08X", color)

    private fun dp(px: Int?): Double? = px?.let { Math.round(it / density * 100.0) / 100.0 }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            runCatching {
                val f = cls!!.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target) as T?
            }
            cls = cls.superclass
        }
        return null
    }
}
