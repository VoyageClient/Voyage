/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

/*
 * This file renders the formatted_body of an event to a formatted Android Spannable.
 * The core of this work is done with Markwon, a general-purpose Markdown+HTML formatter.
 * Since formatted_body is HTML only, Markwon is configured to only handle HTML, not Markdown.
 * The EventHtmlRenderer class is next used in the method buildFormattedTextItem
 * in the file MessageItemFactory.kt.
 * Effectively, this is used in the chat messages view and the room list message previews.
 */

package im.vector.app.features.html

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.widget.TextView
import androidx.core.text.toSpannable
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.PrecomputedFutureTextSetterCompat
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.BulletListItemSpan
import io.noties.markwon.core.spans.EmphasisSpan
import io.noties.markwon.core.spans.OrderedListItemSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.latex.JLatexMathTheme
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.html.tag.EmphasisHandler
import io.noties.markwon.html.tag.HeadingHandler
import io.noties.markwon.html.tag.StrikeHandler
import io.noties.markwon.html.tag.StrongEmphasisHandler
import io.noties.markwon.html.tag.SubScriptHandler
import io.noties.markwon.html.tag.SuperScriptHandler
import io.noties.markwon.html.tag.UnderlineHandler
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.glide.GlideImagesPlugin
import io.noties.markwon.inlineparser.EntityInlineProcessor
import io.noties.markwon.inlineparser.HtmlInlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParser
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import me.gujun.android.span.style.CustomTypefaceSpan
import org.commonmark.node.BlockQuote
import org.commonmark.node.Emphasis
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventHtmlRenderer @Inject constructor(
        private val htmlConfigure: MatrixHtmlPluginConfigure,
        private val context: Context,
        private val activeSessionHolder: ActiveSessionHolder,
        private val vectorPreferences: VectorPreferences,
) {

    interface PostProcessor {
        fun afterRender(renderedText: Spannable)
    }

    private val glidePlugin = GlideImagesPlugin.create(object : GlideImagesPlugin.GlideStore {
        override fun load(drawable: AsyncDrawable): RequestBuilder<Drawable> {
            val url = drawable.destination
            val builder = if (url.isMxcUrl()) {
                val contentUrlResolver = activeSessionHolder.getActiveSession().contentUrlResolver()
                val imageUrl = contentUrlResolver.resolveFullSize(url)
                // Override size to avoid crashes for huge pictures
                Glide.with(context).load(imageUrl).override(500)
            } else {
                // We don't want to support other url schemes here, so just return a request for null
                Glide.with(context).load(null as String?)
            }
            // markwon's AsyncDrawable.setResult stores the new drawable without copying the
            // AsyncDrawable's callback onto it, and unlike ImageView it never calls setVisible
            // on the result either. Wire both ourselves so the animated proxy starts its
            // decoder and invalidations route back to the TextView.
            return builder.listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean) = false
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    drawable.callback?.let { resource.callback = it }
                    resource.setVisible(true, true)
                    return false
                }
            })
        }

        override fun cancel(target: Target<*>) {
            Glide.with(context).clear(target)
        }
    })

    // The TeX arrives as an HTML attribute value, so `\frac{a}{b}` where a &lt; b really is escaped.
    private fun String.unescapeHtmlEntities(): String = replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

    // markwon's LaTeX support keys off `$$…$$` in the markdown source, so `data-mx-maths` is rewritten
    // into that shape first. See https://noties.io/Markwon/docs/v4/ext-latex
    private val latexPlugins = listOf(
            object : AbstractMarkwonPlugin() {
                override fun processMarkdown(markdown: String): String {
                    return markdown
                            .replace(Regex("""<span\s[^>]*?data-mx-maths="([^"]*)"[^>]*>.*?</span>""")) { matchResult ->
                                "$$" + matchResult.groupValues[1].unescapeHtmlEntities() + "$$"
                            }
                            .replace(Regex("""<div\s[^>]*?data-mx-maths="([^"]*)"[^>]*>.*?</div>""")) { matchResult ->
                                "\n$$\n" + matchResult.groupValues[1].unescapeHtmlEntities() + "\n$$\n"
                            }
                }
            },
            JLatexMathPlugin.create(44F) { builder ->
                builder.inlinesEnabled(true)
                builder.theme().inlinePadding(JLatexMathTheme.Padding.symmetric(24, 8))
            }
    )

    private val markwonInlineParserPlugin =
            MarkwonInlineParserPlugin.create(
                    /* Configuring the Markwon inline formatting processor.
                     * Default settings are all Markdown features. Turn those off, only using the
                     * inline HTML processor and HTML entities processor.
                     */
                    MarkwonInlineParser.factoryBuilderNoDefaults()
                            .addInlineProcessor(HtmlInlineProcessor()) // use inline HTML processor
                            .addInlineProcessor(EntityInlineProcessor()) // use HTML entities processor
            )

    private val italicPlugin = object : AbstractMarkwonPlugin() {
        override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
            builder.setFactory(
                    Emphasis::class.java
            ) { _, _ -> CustomTypefaceSpan(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)) }
        }

        override fun configureParser(builder: Parser.Builder) {
            /* Configuring the Markwon block formatting processor.
             * Default settings are all Markdown blocks. Turn those off.
             */
            builder.enabledBlockTypes(emptySet())
        }
    }

    private fun resolveCodeBlockBackground() = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.code_block_bg_color)
    private var codeBlockBackground: Int = resolveCodeBlockBackground()

    // SchildiChat colors code blocks/inline code from code_block_bg_color rather than Markwon's auto value.
    private val codeThemePlugin = object : AbstractMarkwonPlugin() {
        override fun configureTheme(builder: MarkwonTheme.Builder) {
            super.configureTheme(builder)
            builder.codeBlockBackgroundColor(codeBlockBackground)
                    .codeBackgroundColor(codeBlockBackground)
                    // Links are coloured (textColorLink) but never underlined, to match the autolink path.
                    .isLinkUnderlined(false)
        }
    }

    // Indent blockquotes to line up with the reply preview bar (8dp text margin, 2dp stripe) rather
    // than Markwon's wider default. Overrides the shared BlockQuote span factory, which both the
    // HTML <blockquote> handler and the markdown blockquote node resolve, leaving theme.blockMargin
    // (used by lists) alone.
    private val blockQuotePlugin = object : AbstractMarkwonPlugin() {
        override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
            val density = context.resources.displayMetrics.density
            val stripeWidth = (4 * density).toInt()
            val margin = (8 * density).toInt()
            builder.setFactory(BlockQuote::class.java) { _, _ -> QuoteMarginSpan(stripeWidth, margin) }
        }
    }

    // Source-exposing subclasses of the stock spans, so rendered text can be mapped back to its
    // markdown source (list markers become literal characters in materializeListMarkers).
    private val sourceSpansPlugin = object : AbstractMarkwonPlugin() {
        override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
            builder.setFactory(ListItem::class.java) { configuration, props ->
                if (CoreProps.LIST_ITEM_TYPE.require(props) == CoreProps.ListItemType.BULLET) {
                    SourceBulletListItemSpan(configuration.theme(), CoreProps.BULLET_LIST_ITEM_LEVEL.require(props))
                } else {
                    SourceOrderedListItemSpan(configuration.theme(), CoreProps.ORDERED_LIST_ITEM_NUMBER.require(props))
                }
            }
        }
    }

    // Bind every custom-emoticon span to the TextView after the text is set, so each starts loading its
    // image and invalidates the view when ready (the span already reserves its size, so no relayout).
    private val emoticonBinderPlugin = object : AbstractMarkwonPlugin() {
        override fun afterSetText(textView: TextView) {
            val text = textView.text as? Spanned ?: return
            text.getSpans(0, text.length, EmoteImageSpan::class.java).forEach { it.bind(textView) }
        }
    }

    private val cleanUpIntermediateCodePlugin = object : AbstractMarkwonPlugin() {
        override fun afterSetText(textView: TextView) {
            super.afterSetText(textView)

            // Remove any intermediate spans
            val text = textView.text.toSpannable()
            text.getSpans(0, text.length, IntermediateCodeSpan::class.java)
                    .forEach { span ->
                        text.removeSpan(span)
                    }
        }
    }

    /**
     * Workaround for https://github.com/noties/Markwon/issues/423
     */
    private val removeLeadingNewlineForInlineElement = object : AbstractMarkwonPlugin() {
        override fun afterSetText(textView: TextView) {
            super.afterSetText(textView)
            // Runs on every bind, so only rewrite when the workaround actually changes something.
            val current = textView.text as? Spanned ?: return
            val fixed = removeLeadingNewlineForInlineElement(current)
            if (fixed !== current) textView.text = fixed
        }
    }

    /**
     * Markwon strands a leading newline in front of an inline element (link/emphasis/etc.) — see the
     * plugin above. That plugin only fires via afterSetText when the text is set on a TextView through
     * Markwon; callers that render to a plain CharSequence (e.g. room topics) must apply it themselves.
     */
    fun removeLeadingNewlineForInlineElement(current: Spanned): CharSequence {
        val length = current.length
        val spans = arrayOf(
                EmphasisSpan::class.java,
                CustomTypefaceSpan::class.java,
                StrongEmphasisSpan::class.java,
                UnderlineSpan::class.java,
                URLSpan::class.java,
                StrikethroughSpan::class.java,
                // An inline image is stranded the same way: a custom emoticon mid-sentence would otherwise
                // start its own line.
                EmoteImageSpan::class.java,
                AsyncDrawableSpan::class.java
        ).flatMap { current.getSpans(0, length, it).asList() }
                .plus(current.getSpans(0, length, HtmlCodeSpan::class.java).filter { !it.isBlock })

        if (spans.none { val start = current.getSpanStart(it); start in 0 until length && current[start] == '\n' }) return current

        val text = SpannableStringBuilder(current)
        spans.forEach { span ->
            val start = text.getSpanStart(span)
            if (start in 0 until text.length && text[start] == '\n') {
                text.replace(start, start + 1, "")
            }
        }
        return text
    }

    private fun buildMarkwon() = Markwon.builder(context)
            .usePlugin(HtmlRootTagPlugin())
            .usePlugin(HtmlPlugin.create(htmlConfigure))
            .usePlugin(sourceSpansPlugin)
            .usePlugin(removeLeadingNewlineForInlineElement)
            .usePlugin(glidePlugin)
            .usePlugin(codeThemePlugin)
            .usePlugin(blockQuotePlugin)
            .apply {
                if (vectorPreferences.latexMathsIsEnabled()) {
                    latexPlugins.forEach(::usePlugin)
                }
            }
            .usePlugin(markwonInlineParserPlugin)
            .usePlugin(italicPlugin)
            .usePlugin(emoticonBinderPlugin)
            .usePlugin(cleanUpIntermediateCodePlugin)
            .textSetter(PrecomputedFutureTextSetterCompat.create())
            .build()

    private var markwonBackingField = buildMarkwon()

    // The Markwon instance holds a single shared, mutable HTML parser (MarkwonHtmlParserImpl). The
    // timeline renders on a background thread while previews render on the main thread, so serialise
    // all parse/render access to keep that parser's state consistent across concurrent callers.
    private val renderLock = Any()

    // Rebuild when the active theme changed the code-block colour (singleton survives Activity recreate).
    private val markwon: Markwon
        get() = synchronized(renderLock) {
            val newCodeBlockBackground = resolveCodeBlockBackground()
            if (newCodeBlockBackground != codeBlockBackground) {
                codeBlockBackground = newCodeBlockBackground
                markwonBackingField = buildMarkwon()
            }
            markwonBackingField
        }

    val plugins: List<MarkwonPlugin> get() = markwon.plugins

    /**
     * Set rendered text on a [TextView] running the Markwon plugins around it, so inline image
     * emoticons get scheduled (AsyncDrawableScheduler) and the #423 newline workaround applies.
     * Plain `textView.text = …` skips both, leaving emoticons as their alt text with a stray newline.
     */
    fun setTextWithPlugins(textView: TextView, text: CharSequence?) {
        val markwonPlugins = plugins
        (text as? Spanned)?.let { spanned -> markwonPlugins.forEach { it.beforeSetText(textView, spanned) } }
        textView.text = text
        markwonPlugins.forEach { it.afterSetText(textView) }
    }

    fun parse(text: String): Node = synchronized(renderLock) {
        im.vector.app.core.utils.PerfTrace.time("html.markwonParse") { markwon.parse(text) }
    }

    /**
     * @param text the text you want to render
     * @param postProcessors an optional array of post processor to add any span if needed
     */
    fun render(text: String, vararg postProcessors: PostProcessor): CharSequence = im.vector.app.core.utils.PerfTrace.time("html.render") {
        try {
            val parsed = parse(text)
            renderAndProcess(parsed, postProcessors)
        } catch (failure: Throwable) {
            Timber.v("Fail to render $text to html")
            text
        }
    }

    /**
     * @param node the node you want to render
     * @param postProcessors an optional array of post processor to add any span if needed
     */
    fun render(node: Node, vararg postProcessors: PostProcessor): CharSequence? = im.vector.app.core.utils.PerfTrace.time("html.renderNode") {
        try {
            renderAndProcess(node, postProcessors)
        } catch (failure: Throwable) {
            Timber.v("Fail to render $node to html")
            null
        }
    }

    private fun renderAndProcess(node: Node, postProcessors: Array<out PostProcessor>): CharSequence = synchronized(renderLock) {
        // Editable so post-processors can collapse pill backing text to a placeholder (see setPillSpan).
        val renderedText = im.vector.app.core.utils.PerfTrace.time("html.markwonRender") { SpannableStringBuilder(markwon.render(node)) }
        collapseBlockQuotePadding(renderedText)
        separateBlockQuoteTrailingContent(renderedText)
        collapsePhantomWhitespaceLines(renderedText)
        materializeListMarkers(renderedText)
        // Block elements (a trailing <p>/<br>) leave a dangling newline/space Markwon doesn't strip. The
        // timeline happens to hide it, but the non-timeline surfaces that set this text directly (long-press,
        // reply header, reply composer) render it as a blank trailing line. Drop the trailing whitespace run
        // here so every surface matches — message trailing whitespace is never significant.
        var end = renderedText.length
        while (end > 0 && renderedText[end - 1].let { it == '\n' || it == ' ' || it == '\t' }) end--
        if (end < renderedText.length) renderedText.delete(end, renderedText.length)
        im.vector.app.core.utils.PerfTrace.time("html.postProcess") {
            postProcessors.forEach {
                it.afterRender(renderedText)
            }
        }
        renderedText
    }

    // The compressor turns the newlines markdown leaves between tags into single spaces
    // (removeIntertagSpaces would corrupt inline content), which Markwon strands on lines of their
    // own around list boundaries — blank-looking lines that detach bullets from their text. Space-only
    // lines are always that artifact (real blank lines are empty, e.g. from a sender's explicit
    // <br />), so drop every list-related run. Looseness itself adds no blank line: it's list-wide in
    // commonmark, so it can't say which gaps were typed blank; its <p> padding carries the spacing,
    // like element-web. A run between two paragraphs of the SAME item keeps its blank line like
    // top-level paragraphs; non-list runs and code spans are untouched.
    // Selection can only cover real characters, so replace the margin-drawn list markers with
    // literal ones ("• " / "1. ", space-indented when nested); the Markwon spans and their
    // margins go away. ListMarkerSpan carries each marker's markdown source for copy.
    private fun materializeListMarkers(text: SpannableStringBuilder) {
        val spans = text.getSpans(0, text.length, BulletListItemSpan::class.java).asList() +
                text.getSpans(0, text.length, OrderedListItemSpan::class.java)
        if (spans.isEmpty()) return
        val markers = spans.map { span ->
            val start = text.getSpanStart(span)
            val depth = spans.count {
                it !== span && text.getSpanStart(it) <= start && text.getSpanEnd(it) >= text.getSpanEnd(span)
            }
            Triple(span, start, depth)
        }.sortedWith(compareByDescending<Triple<Any, Int, Int>> { it.second }.thenByDescending { it.third })
        for ((span, start, depth) in markers) {
            if (start < 0) continue
            text.removeSpan(span)
            val (display, source) = if (span is SourceOrderedListItemSpan) {
                "${span.number}. " to "${span.number}. "
            } else {
                "● " to "- "
            }
            val indent = "   ".repeat(depth)
            text.insert(start, indent + display)
            if (span !is SourceOrderedListItemSpan) {
                // The small • glyph reads undersized next to the old drawn bullet; a scaled ● matches it
                text.setSpan(RelativeSizeSpan(0.8f), start + indent.length, start + indent.length + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text.setSpan(ListMarkerSpan(indent + source), start, start + indent.length + display.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun collapsePhantomWhitespaceLines(text: SpannableStringBuilder) {
        val listRanges = (
                text.getSpans(0, text.length, BulletListItemSpan::class.java).asList() +
                        text.getSpans(0, text.length, OrderedListItemSpan::class.java)
                ).map { text.getSpanStart(it) to text.getSpanEnd(it) }
        if (listRanges.isEmpty()) return
        val codeRanges = text.getSpans(0, text.length, HtmlCodeSpan::class.java)
                .filter { it.isBlock }
                .map { text.getSpanStart(it) to text.getSpanEnd(it) }

        // Runs of consecutive lines that contain only spaces/tabs (at least one), as [start, end).
        val runs = ArrayList<IntArray>()
        var pos = 0
        while (pos < text.length) {
            val nl = text.indexOf('\n', pos).let { if (it == -1) text.length else it }
            val whitespaceOnly = nl > pos &&
                    (pos until nl).all { text[it] == ' ' || text[it] == '\t' } &&
                    codeRanges.none { (s, e) -> s < nl && e > pos }
            if (whitespaceOnly) {
                val lineEnd = (nl + 1).coerceAtMost(text.length)
                val last = runs.lastOrNull()
                if (last != null && last[1] == pos) last[1] = lineEnd else runs.add(intArrayOf(pos, lineEnd))
            }
            pos = nl + 1
        }

        fun Char.isWs() = this == ' ' || this == '\t' || this == '\n'

        // Item spans swallow adjacent phantom whitespace, so classify a run by the items owning the
        // nearest real content on each side, not by span overlap with the run itself.
        fun innermostItem(index: Int) = listRanges.filter { (s, e) -> index in s until e }.minByOrNull { (s, e) -> e - s }

        for (run in runs.asReversed()) {
            val (start, end) = run
            if (start == 0) {
                text.delete(start, end)
                continue
            }
            var p = start - 1
            while (p >= 0 && text[p].isWs()) p--
            var n = end
            while (n < text.length && text[n].isWs()) n++
            val prevItem = if (p >= 0) innermostItem(p) else null
            val nextItem = if (n < text.length) innermostItem(n) else null
            when {
                prevItem != null && prevItem == nextItem -> Unit
                prevItem != null || nextItem != null -> text.delete(start, end)
            }
        }
    }

    // A <blockquote> immediately followed by body text with no wrapping <p> (e.g. `</blockquote>trailing`)
    // renders the trailing content on the quote's last line, because Markwon appends it inline. Match the
    // plaintext body / element-web by separating the quote from the following content with a blank line.
    private fun separateBlockQuoteTrailingContent(text: SpannableStringBuilder) {
        val quotes = text.getSpans(0, text.length, QuoteMarginSpan::class.java)
        if (quotes.isEmpty()) return
        fun Char.isTrimable() = this == '\n' || this == ' ' || this == '\t'
        // Collect edits first, then apply back-to-front so earlier indices stay valid as the buffer grows.
        val edits = ArrayList<Pair<Int, Int>>()
        for (quote in quotes) {
            val spanEnd = text.getSpanEnd(quote).coerceIn(0, text.length)
            var contentStart = spanEnd
            while (contentStart < text.length && text[contentStart].isTrimable()) contentStart++
            if (contentStart >= text.length) continue
            // Skip when the following content is itself inside a quote (nested/adjacent blockquote).
            if (text.getSpans(contentStart, contentStart + 1, QuoteMarginSpan::class.java).isNotEmpty()) continue
            edits.add(spanEnd to contentStart)
        }
        edits.sortByDescending { it.first }
        for ((start, end) in edits) {
            text.replace(start, end, "\n\n")
        }
    }

    // Senders often pad a blockquote with blank leading/trailing lines (e.g. `<blockquote>\n…\n</blockquote>`).
    // Browsers collapse that insignificant whitespace, but Markwon keeps it as empty lines inside the quote
    // stripe, adding vertical padding element-web doesn't show. Drop the trimmable run at each quote's start
    // and end; the block separators sit outside the span so the quote still stays on its own line, and any
    // intentional interior blank lines are preserved.
    private fun collapseBlockQuotePadding(text: SpannableStringBuilder) {
        val quotes = text.getSpans(0, text.length, QuoteMarginSpan::class.java)
        if (quotes.isEmpty()) return
        fun Char.isTrimable() = this == '\n' || this == ' ' || this == '\t'
        val delete = BooleanArray(text.length)
        for (quote in quotes) {
            val start = text.getSpanStart(quote).coerceAtLeast(0)
            val end = text.getSpanEnd(quote).coerceAtMost(text.length)
            var lead = start
            while (lead < end && text[lead].isTrimable()) { delete[lead] = true; lead++ }
            var trail = end
            while (trail > lead && text[trail - 1].isTrimable()) { delete[trail - 1] = true; trail-- }
        }
        // Delete marked runs back-to-front so lower indices stay valid as the buffer shrinks.
        var i = text.length
        while (i > 0) {
            if (delete[i - 1]) {
                val runEnd = i
                while (i > 0 && delete[i - 1]) i--
                text.delete(i, runEnd)
            } else {
                i--
            }
        }
    }
}

class MatrixHtmlPluginConfigure @Inject constructor(
        private val colorProvider: ColorProvider,
        private val resources: Resources,
        private val activeSessionHolder: ActiveSessionHolder,
) : HtmlPlugin.HtmlConfigure {

    override fun configureHtml(plugin: HtmlPlugin) {
        plugin
                .addHandler(ListHandlerWithInitialStart())
                .addHandler(FontTagHandler())
                .addHandler(ParagraphHandler(DimensionConverter(resources)))
                .addHandler(DetailsTagHandler(DimensionConverter(resources)))
                .addHandler(MxReplyTagHandler())
                .addHandler(CodePostProcessorTagHandler())
                .addHandler(CodePreTagHandler())
                .addHandler(CodeTagHandler())
                .addHandler(MxEmoticonTagHandler(activeSessionHolder))
                .addHandler(SpanHandler(colorProvider))
                // Layer colour over each default handler so it works on any element, not just <font>.
                .addHandler(ColorTagHandler(StrongEmphasisHandler()))
                .addHandler(ColorTagHandler(EmphasisHandler()))
                .addHandler(ColorTagHandler(UnderlineHandler()))
                .addHandler(ColorTagHandler(StrikeHandler()))
                .addHandler(ColorTagHandler(SuperScriptHandler()))
                .addHandler(ColorTagHandler(SubScriptHandler()))
                .addHandler(ColorTagHandler(HeadingHandler()))
    }
}
