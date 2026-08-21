/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

import io.noties.markwon.html.HtmlTag

internal const val ROOT_TAG_NAME = "div"
internal const val ROOT_ATTRIBUTE = "data-root"

internal fun String.isMxcUrl() = startsWith("mxc://")

/** Ports of the Markwon/Element tag handlers, emitting [RichStyle]s instead of Android spans. */
internal class HtmlRenderContext(val builder: SpanBuffer, handlers: List<TagHandler>) {

    private val handlerMap = HashMap<String, TagHandler>()

    init {
        handlers.forEach { handler -> handler.supportedTags.forEach { handlerMap[it] = handler } }
    }

    fun handler(tag: String): TagHandler? = handlerMap[tag]

    /** Markwon's `SpannableBuilder.setSpans`: silently ignored for an invalid range. */
    fun setSpans(styles: List<RichStyle>?, start: Int, end: Int) {
        if (styles == null) return
        if (!(end > start && start >= 0 && end <= builder.length)) return
        styles.forEach { builder.setSpan(it, start, end) }
    }

    fun setSpans(style: RichStyle?, start: Int, end: Int) = setSpans(style?.let { listOf(it) }, start, end)

    fun visitChildren(block: HtmlTag.Block) {
        for (child in block.children()) {
            if (!child.isClosed) continue
            val handler = handler(child.name())
            if (handler != null) handler.handle(this, child) else visitChildren(child)
        }
    }

    fun applyInline(tags: List<HtmlTag.Inline>) {
        for (inline in tags) {
            if (!inline.isClosed) continue
            handler(inline.name())?.handle(this, inline)
        }
    }

    fun applyBlocks(tags: List<HtmlTag.Block>) {
        for (block in tags) {
            if (!block.isClosed) continue
            val handler = handler(block.name())
            if (handler != null) handler.handle(this, block) else applyBlocks(block.children())
        }
    }
}

internal abstract class TagHandler {
    abstract val supportedTags: Collection<String>
    abstract fun handle(ctx: HtmlRenderContext, tag: HtmlTag)
}

internal abstract class SimpleTagHandler : TagHandler() {
    abstract fun getStyles(ctx: HtmlRenderContext, tag: HtmlTag): List<RichStyle>?

    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        if (tag.isBlock) ctx.visitChildren(tag.asBlock)
        ctx.setSpans(getStyles(ctx, tag), tag.start(), tag.end())
    }
}

internal class SingleStyleHandler(override val supportedTags: Collection<String>, private val style: RichStyle) : SimpleTagHandler() {
    override fun getStyles(ctx: HtmlRenderContext, tag: HtmlTag): List<RichStyle> = listOf(style)
}

internal class HeadingHandler : SimpleTagHandler() {
    override val supportedTags = listOf("h1", "h2", "h3", "h4", "h5", "h6")
    override fun getStyles(ctx: HtmlRenderContext, tag: HtmlTag): List<RichStyle>? {
        val level = tag.name().substring(1).toIntOrNull() ?: 0
        if (level < 1 || level > 6) return null
        return listOf(RichStyle.Heading(level))
    }
}

internal class LinkHandler : SimpleTagHandler() {
    override val supportedTags = listOf("a")
    override fun getStyles(ctx: HtmlRenderContext, tag: HtmlTag): List<RichStyle>? {
        val destination = tag.attributes()["href"]
        if (destination.isNullOrEmpty()) return null
        return listOf(RichStyle.Link(destination))
    }
}

internal class BlockquoteHandler : TagHandler() {
    override val supportedTags = listOf("blockquote")
    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        if (tag.isBlock) ctx.visitChildren(tag.asBlock)
        ctx.setSpans(RichStyle.Blockquote, tag.start(), tag.end())
    }
}

internal class ListHandlerWithInitialStart : TagHandler() {
    override val supportedTags = listOf("ol", "ul")
    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        if (!tag.isBlock) return
        val block = tag.asBlock
        val ol = block.name() == "ol"
        val ul = block.name() == "ul"
        if (!ol && !ul) return
        // Integer.parseInt on a malformed start attribute throws, failing the whole render like Android.
        var number = Integer.parseInt(block.attributes()["start"] ?: "1")
        val bulletLevel = currentBulletListLevel(block)
        for (child in block.children()) {
            ctx.visitChildren(child)
            if (child.name() == "li") {
                val style = if (ol) RichStyle.ListItem(number++, bulletLevel) else RichStyle.ListItem(null, bulletLevel)
                ctx.setSpans(style, child.start(), child.end())
            }
        }
    }

    private fun currentBulletListLevel(block: HtmlTag.Block): Int {
        var level = 0
        var parent = block.parent()
        while (parent != null) {
            if (parent.name() == "ul" || parent.name() == "ol") level++
            parent = parent.parent()
        }
        return level
    }
}

internal class FontTagHandler : SimpleTagHandler() {
    override val supportedTags = listOf("font")
    override fun getStyles(ctx: HtmlRenderContext, tag: HtmlTag): List<RichStyle>? {
        val attributes = tag.attributes()
        val styles = ArrayList<RichStyle>(2)
        HtmlColorParser.foregroundColor(attributes)?.let { styles.add(RichStyle.Color(it)) }
        HtmlColorParser.backgroundColor(attributes)?.let { styles.add(RichStyle.BackgroundColor(it)) }
        return styles.takeIf { it.isNotEmpty() }
    }
}

internal class ParagraphHandler : TagHandler() {
    override val supportedTags = listOf("p")
    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        if (tag.isBlock) ctx.visitChildren(tag.asBlock)
        if (isLoneWrappingParagraph(tag)) return
        ctx.setSpans(RichStyle.VerticalPadding(4, 4), tag.start(), tag.end())
    }

    // A `<p>` that is the sole child of the root wrapper is a markdown→HTML artifact and adds no padding.
    private fun isLoneWrappingParagraph(tag: HtmlTag): Boolean {
        if (!tag.isBlock) return false
        val parent = tag.asBlock.parent() ?: return false
        val parentIsRoot = parent.isRoot || (parent.name() == ROOT_TAG_NAME && parent.attributes().containsKey(ROOT_ATTRIBUTE))
        if (!parentIsRoot) return false
        return parent.children().size == 1
    }
}

internal class DetailsTagHandler : TagHandler() {
    override val supportedTags = listOf("details", "summary")
    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        if (tag.isBlock) ctx.visitChildren(tag.asBlock)
        if (tag.start() == tag.end()) return
        when (tag.name()) {
            "summary" -> ctx.setSpans(RichStyle.Bold, tag.start(), tag.end())
            "details" -> ctx.setSpans(listOf(RichStyle.VerticalPadding(4, 4), RichStyle.LeadingMargin(8)), tag.start(), tag.end())
        }
    }
}

internal class MxReplyTagHandler : TagHandler() {
    override val supportedTags = listOf("mx-reply")
    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        ctx.visitChildren(tag.asBlock)
        val reply = ctx.builder.removeFromEnd(tag.end())
        ctx.builder.append("\n\n")
        ctx.builder.appendWithSpans(reply)
    }
}

internal class CodeTagHandler : TagHandler() {
    override val supportedTags = listOf("code")
    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        ctx.setSpans(RichStyle.IntermediateCode(isBlock = false), tag.start(), tag.end())
    }
}

internal class CodePreTagHandler : TagHandler() {
    override val supportedTags = listOf("pre")
    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        val code = ctx.builder.markwonGetSpans(tag.start(), tag.end()).firstOrNull { it.style is RichStyle.IntermediateCode }
        (code?.style as? RichStyle.IntermediateCode)?.isBlock = true
    }
}

internal class CodePostProcessorTagHandler : TagHandler() {
    override val supportedTags = listOf(ROOT_TAG_NAME)
    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        if (tag.attributes()[ROOT_ATTRIBUTE] == null) return
        if (tag.isBlock) ctx.visitChildren(tag.asBlock)
        ctx.builder.markwonGetSpans(tag.start(), tag.end())
                .filter { it.style is RichStyle.IntermediateCode }
                .forEach { code ->
                    val isBlock = (code.style as RichStyle.IntermediateCode).isBlock
                    ctx.setSpans(RichStyle.Code(isBlock), code.start, code.end)
                }
    }
}

internal class MxEmoticonTagHandler : SimpleTagHandler() {
    override val supportedTags = listOf("img")
    override fun getStyles(ctx: HtmlRenderContext, tag: HtmlTag): List<RichStyle>? {
        val attributes = tag.attributes()
        val src = attributes["src"]
        val isEmoticon = attributes.containsKey("data-mx-emoticon") ||
                dimensionIs32(attributes["width"]) ||
                dimensionIs32(attributes["height"])
        if (src != null && src.isMxcUrl() && isEmoticon) {
            return listOf(
                    RichStyle.Emote(
                            mxcUrl = src,
                            shortcode = (attributes["title"] ?: attributes["alt"])?.removeSurrounding(":") ?: "",
                            body = attributes["alt"],
                    )
            )
        }
        if (src.isNullOrEmpty()) return null
        return listOf(RichStyle.Image(src, parseImageSize(attributes)))
    }

    private fun dimensionIs32(value: String?): Boolean = value?.takeWhile { it.isDigit() }?.toIntOrNull() == 32

    private fun parseImageSize(attributes: Map<String, String>): ImageSize? {
        var width: ImageDimension? = null
        var height: ImageDimension? = null
        val style = attributes["style"]
        if (!style.isNullOrEmpty()) {
            for ((key, value) in CssStyleParser.parse(style)) {
                when (key) {
                    "width" -> width = dimension(value)
                    "height" -> height = dimension(value)
                }
                if (width != null && height != null) break
            }
        }
        if (width != null && height != null) return ImageSize(width, height)
        if (width == null) width = dimension(attributes["width"])
        if (height == null) height = dimension(attributes["height"])
        if (width == null && height == null) return null
        return ImageSize(width, height)
    }

    private fun dimension(value: String?): ImageDimension? {
        if (value.isNullOrEmpty()) return null
        for (i in value.length - 1 downTo 0) {
            if (value[i].isDigit()) {
                return try {
                    ImageDimension(value.substring(0, i + 1).toFloat(), if (i == value.length - 1) null else value.substring(i + 1))
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }
        return null
    }
}

internal class SpanHandler : TagHandler() {
    override val supportedTags = listOf("span")
    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        val spoiler = tag.attributes()["data-mx-spoiler"]
        if (spoiler != null) {
            ctx.setSpans(RichStyle.Spoiler(spoiler.takeIf { it.isNotEmpty() }), tag.start(), tag.end())
        }
        ColorTagHandler.applyColors(ctx, tag)
    }
}

internal class ColorTagHandler(private val delegate: TagHandler) : TagHandler() {
    override val supportedTags: Collection<String> = delegate.supportedTags
    override fun handle(ctx: HtmlRenderContext, tag: HtmlTag) {
        delegate.handle(ctx, tag)
        applyColors(ctx, tag)
    }

    companion object {
        fun applyColors(ctx: HtmlRenderContext, tag: HtmlTag) {
            if (tag.isEmpty || tag.start() >= tag.end()) return
            val attributes = tag.attributes()
            HtmlColorParser.foregroundColor(attributes)?.let { ctx.setSpans(RichStyle.Color(it), tag.start(), tag.end()) }
            HtmlColorParser.backgroundColor(attributes)?.let { ctx.setSpans(RichStyle.BackgroundColor(it), tag.start(), tag.end()) }
        }
    }
}

internal fun matrixTagHandlers(): List<TagHandler> = listOf(
        ListHandlerWithInitialStart(),
        FontTagHandler(),
        ParagraphHandler(),
        DetailsTagHandler(),
        MxReplyTagHandler(),
        CodePostProcessorTagHandler(),
        CodePreTagHandler(),
        CodeTagHandler(),
        MxEmoticonTagHandler(),
        SpanHandler(),
        ColorTagHandler(SingleStyleHandler(listOf("b", "strong"), RichStyle.Bold)),
        ColorTagHandler(SingleStyleHandler(listOf("i", "em", "cite", "dfn"), RichStyle.Italic)),
        ColorTagHandler(SingleStyleHandler(listOf("u", "ins"), RichStyle.Underline)),
        ColorTagHandler(SingleStyleHandler(listOf("s", "del"), RichStyle.Strikethrough)),
        ColorTagHandler(SingleStyleHandler(listOf("sup"), RichStyle.Superscript)),
        ColorTagHandler(SingleStyleHandler(listOf("sub"), RichStyle.Subscript)),
        ColorTagHandler(HeadingHandler()),
        // Markwon's defaults for the tags nothing above claims.
        LinkHandler(),
        BlockquoteHandler(),
)
