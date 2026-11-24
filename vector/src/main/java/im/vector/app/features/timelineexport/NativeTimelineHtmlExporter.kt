/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import im.vector.app.R
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.features.home.room.detail.timeline.render.TimelineRenderingScope
import im.vector.app.features.settings.AvatarShape
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.profile.IdentityAliases
import org.matrix.android.sdk.api.util.MatrixJsonParser
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class NativeTimelineHtmlExporter @Inject constructor(private val renderer: TimelineExportRenderer) {
    data class Layout(val width: Int, val rows: String)
    private data class Media(val x: Float, val y: Float, val width: Int, val height: Int, val path: String, val mime: String)
    private enum class LinkKind { WEB, REPLY, PANEL }
    private data class Link(val x: Float, val y: Float, val width: Float, val height: Int, val url: String, val kind: LinkKind = LinkKind.WEB)
    private data class RowContent(val media: List<Media>, val links: List<Link>)

    /** A bottom sheet the page opens over the timeline, as the timeline opens its own. */
    private data class PanelSource(val sheet: TimelineExportRenderer.Sheet, val events: List<ExportEvent>)

    private fun bitmapSources(document: TimelineExportRenderer.Document, artwork: TimelineHtmlCanvas.Artwork): Map<Bitmap, String> = document.images.snapshot().mapNotNull { (path, bitmap) ->
        val asset = document.assetsByPath[path] ?: return@mapNotNull null
        if (!asset.mime.startsWith("image/")) return@mapNotNull null
        if (asset.animated) return@mapNotNull bitmap to path
        artwork.source(path, bitmap)?.let { bitmap to it }
    }.toMap()

    suspend fun write(context: Context, snapshot: TimelineExportSnapshot, directory: File, progress: (Int, Int) -> Unit): List<String> {
        withContext(Dispatchers.IO) { File(directory, "assets").mkdirs() }
        val fonts = withContext(Dispatchers.IO) { TimelineHtmlFonts.write(context, directory) }
        val artwork = TimelineHtmlCanvas.Artwork(directory)
        try {
            val layouts = mutableListOf<Layout>()
            val panels = LinkedHashMap<String, PanelSource>()
            val widths = listOf(snapshot.options.phoneWidth, 768, 1200).distinct().sorted()
            var groupCount = 0
            for ((layoutIndex, width) in widths.withIndex()) {
                coroutineContext.ensureActive()
                val options = snapshot.options.copy(layoutWidth = width, expandAll = true)
                val expanded = renderer.layout(context, snapshot.copy(options = options), directory, progress = { current, total ->
                    progress(layoutIndex * 1000 + current * 200 / total.coerceAtLeast(1), widths.size * 1000)
                })
                var collapsed: TimelineExportRenderer.Document? = null
                try {
                    if (expanded.blocks.any { it.groupId != null }) collapsed = renderer.layout(context,
                            snapshot.copy(options = options.copy(expandAll = false, expandedGroups = emptySet())), directory, progress = { current, total ->
                                progress(layoutIndex * 1000 + 200 + current * 100 / total.coerceAtLeast(1), widths.size * 1000)
                            })
                    val groupIds = expanded.blocks.filter { it.groupId != null }.associate { it.groupId!! to it.events.map { event -> event.id }.toSet() }
                    val output = StringBuilder()
                    var activeGroup: String? = null
                    fun closeGroup() { if (activeGroup != null) { output.append("</div></section>"); activeGroup = null } }
                    for ((index, row) in expanded.rows.withIndex()) {
                        coroutineContext.ensureActive()
                        progress(layoutIndex * 1000 + 300 + index * 700 / expanded.rows.size.coerceAtLeast(1), widths.size * 1000)
                        if (row.groupId != null) {
                            closeGroup()
                            activeGroup = row.groupId
                            val open = snapshot.options.expandAll || row.groupId in snapshot.options.expandedGroups
                            // A checkbox toggles the group, so it still expands in viewers that run no JavaScript.
                            val toggle = "g" + groupCount++
                            output.append("<section class=\"group\" data-group=\"").append(escape(row.groupId)).append("\">")
                            output.append("<input type=\"checkbox\" class=\"toggle\" id=\"").append(toggle).append("\" onchange=\"resizeLayouts()\"")
                                    .append(if (open) " checked" else "").append("><label class=\"collapsed\" for=\"").append(toggle).append("\">")
                            val closedIndex = collapsed?.rows?.indexOfFirst { it.groupId == row.groupId } ?: -1
                            if (closedIndex >= 0) {
                                output.append(renderRow(collapsed!!, closedIndex, artwork, panels))
                                val receipt = collapsed.rows.getOrNull(closedIndex + 1)
                                if (receipt?.implicit == true) output.append(renderRow(collapsed, closedIndex + 1, artwork, panels))
                            }
                            output.append("</label><div class=\"expanded\">")
                            output.append("<label for=\"").append(toggle).append("\">")
                            output.append(renderRow(expanded, index, artwork, panels))
                            output.append("</label>")
                        } else {
                            if (activeGroup != null && row.events.none { it.id in groupIds[activeGroup].orEmpty() }) closeGroup()
                            output.append(renderRow(expanded, index, artwork, panels))
                        }
                    }
                    closeGroup()
                    layouts.add(Layout(width, output.toString()))
                    Timber.d("ExportTrace native HTML layout complete width=%d rows=%d", width, expanded.rows.size)
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) { expanded.release(); collapsed?.release() }
                }
            }
            val sheets = StringBuilder()
            for ((key, panel) in panels) {
                coroutineContext.ensureActive()
                sheets.append(renderPanel(context, snapshot, directory, key, panel, artwork))
            }
            withContext(Dispatchers.IO) { File(directory, "messages.html").writeText(document(snapshot, layouts, sheets.toString(), artwork.imageClasses)) }
            progress(widths.size * 1000, widths.size * 1000)
            withContext(Dispatchers.IO) {
                val retained = artwork.files + snapshot.assets.map { it.path }
                File(directory, "assets").listFiles().orEmpty().filter { it.name.startsWith("art-") && "assets/${it.name}" !in retained }.forEach { it.delete() }
            }
            return listOf("messages.html") + fonts.map { it.path } + artwork.files
        } finally { artwork.clearReferences() }
    }

    // Laid out once at phone width: the sheet is phone-sized at any page width.
    private suspend fun renderPanel(context: Context, snapshot: TimelineExportSnapshot, directory: File, key: String,
                                    panel: PanelSource, artwork: TimelineHtmlCanvas.Artwork): String {
        val (events, options, title) = when (panel.sheet) {
            TimelineExportRenderer.Sheet.Revisions -> Triple(ExportEditHistory.revisions(panel.events.single()),
                    ExportEditHistory.revisionOptions(snapshot.options), context.getString(CommonStrings.message_edits))
            TimelineExportRenderer.Sheet.Receipts -> Triple(panel.events,
                    snapshot.options.copy(roomHeader = false, dates = false), context.getString(CommonStrings.seen_by))
        }
        val width = snapshot.options.phoneWidth
        val document = renderer.layout(context, snapshot.copy(events = events, options = options.copy(layoutWidth = width, expandAll = true)), directory, sheet = panel.sheet)
        try {
            val (surface, text) = withContext(Dispatchers.Main) { sheetColors(document.context) }
            // Without JavaScript the hotspot's fragment shows the panel through :target, and the close link clears it.
            val output = StringBuilder("<div class=\"panel\" id=\"").append(panelId(key)).append("\" data-panel=\"").append(escape(key)).append("\" data-width=\"").append(width)
                    .append("\" style=\"max-width:").append(width).append("px;background:").append(TimelineExportHtml.cssColor(surface))
                    .append("\"><a class=\"panel-close\" href=\"#_\" aria-label=\"").append(escape(context.getString(CommonStrings.action_close))).append("\" style=\"color:")
                    .append(TimelineExportHtml.cssColor(text)).append("\">&#215;</a><div class=\"panel-title\" style=\"color:")
                    .append(TimelineExportHtml.cssColor(text)).append("\">").append(escape(title)).append("</div><div class=\"panel-body\"><div class=\"panel-rows\" style=\"width:")
                    .append(width).append("px\">")
            document.rows.indices.forEach { output.append(renderRow(document, it, artwork, panels = null)) }
            return output.append("</div></div></div>").toString()
        } finally { withContext(NonCancellable + Dispatchers.Main) { document.release() } }
    }

    /** @param panels where a timeline row registers the sheets its hotspots open; null for a sheet's own rows. */
    private suspend fun renderRow(document: TimelineExportRenderer.Document, index: Int, artwork: TimelineHtmlCanvas.Artwork,
                                  panels: MutableMap<String, PanelSource>?): String {
        var row = document.rows[index]
        if (row.height <= 0) return ""
        renderer.prepareAssets(document, top = row.top, bottom = row.top + row.height)
        row = document.rows[index]
        val width = document.logicalWidth / document.density
        val height = row.height / document.density
        val sources = withContext(Dispatchers.IO) { bitmapSources(document, artwork) }
        val (content, drawing) = withContext(Dispatchers.Main) {
            val root = renderer.readyView(document, index)
            val canvas = TimelineHtmlCanvas(kotlin.math.ceil(width.toDouble()).toInt(), kotlin.math.ceil(height.toDouble()).toInt(), artwork, sources)
            canvas.scale(1f / document.density, 1f / document.density)
            val images = mutableListOf<Pair<ImageView, Drawable>>()
            val backgrounds = mutableListOf<Triple<View, Drawable, IntArray>>()
            fun wrap(view: View) {
                view.background?.let { original ->
                    val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                    backgrounds.add(Triple(view, original, padding))
                    view.backgroundCompat = HtmlArtworkDrawable(original)
                    view.setPadding(padding[0], padding[1], padding[2], padding[3])
                }
                if (view is ImageView) view.drawable?.let { original ->
                    images.add(view to original)
                    val source = document.context.imagePath(view)
                    var path = source?.takeIf { document.assetsByPath[it]?.animated == true }
                    val shape = if (document.context.isAvatar(view)) AvatarShape.values().firstOrNull { it.name == document.snapshot.options.avatarShape } else null
                    if (path == null && shape != null && shape.effect == null) document.bitmap(source)?.let { bitmap ->
                        val size = minOf(bitmap.width, bitmap.height)
                        val square = Bitmap.createBitmap(bitmap, (bitmap.width - size) / 2, (bitmap.height - size) / 2, size, size)
                        try { path = sources[square] ?: artwork.store(square) } finally { if (square !== bitmap) square.recycle() }
                    }
                    view.setImageDrawable(HtmlArtworkDrawable(original, path, shape))
                }
                if (view is ViewGroup) for (child in 0 until view.childCount) wrap(view.getChildAt(child))
            }
            try {
                wrap(root)
                renderer.readyView(document, index)
                TimelineRenderingScope.render(document.context) { root.draw(canvas) }
            } finally {
                images.forEach { (view, original) -> view.setImageDrawable(original) }
                backgrounds.asReversed().forEach { (view, original, padding) -> view.backgroundCompat = original; view.setPadding(padding[0], padding[1], padding[2], padding[3]) }
            }
            if (canvas.html.isEmpty()) Timber.w("ExportTrace HTML row index=%d drew nothing; exported as blank space", index)
            describe(document, root, row, panels) to canvas.html.toString()
        }
        withContext(Dispatchers.IO) { artwork.flush() }
        val density = document.density
        val output = StringBuilder("<article")
        if (panels != null && !row.implicit && row.groupId == null) row.events.firstOrNull()?.let { output.append(" data-event=\"").append(escape(it.id)).append("\"") }
        output.append(" style=\"height:").append(height).append("px\"><div class=\"native-row\" style=\"width:").append(width).append("px;height:").append(height).append("px\">")
        output.append(drawing)
        content.media.forEach { media ->
            output.append("<a class=\"hotspot\" href=\"").append(escape(media.path)).append("\" data-path=\"").append(escape(media.path)).append("\" data-mime=\"")
                    .append(escape(media.mime)).append("\" aria-label=\"").append(escape(row.events.firstOrNull()?.attachmentName ?: media.mime)).append("\" onclick=\"return openMedia(event,this)\" style=\"left:").append(media.x / density).append("px;top:").append(media.y / density).append("px;width:").append(media.width / density).append("px;height:").append(media.height / density).append("px\"></a>")
        }
        content.links.forEach { link ->
            output.append("<a class=\"hotspot\" href=\"").append(if (link.kind == LinkKind.PANEL) "#" + panelId(link.url) else escape(link.url)).append("\"")
            when (link.kind) {
                LinkKind.REPLY -> output.append(" data-reply=\"").append(escape(link.url)).append("\" onclick=\"return navigateReply(event,this)\"")
                LinkKind.PANEL -> output.append(" data-panel=\"").append(escape(link.url)).append("\" onclick=\"return openPanel(event,this)\"")
                LinkKind.WEB -> output.append(" target=\"_blank\" rel=\"noopener noreferrer\"")
            }
            output.append(" style=\"left:").append(link.x / density).append("px;top:").append(link.y / density)
                    .append("px;width:").append(link.width / density).append("px;height:").append(link.height / density).append("px\"></a>")
        }
        return output.append("</div></article>").toString()
    }

    private fun describe(document: TimelineExportRenderer.Document, root: View, row: TimelineExportRenderer.Row, panels: MutableMap<String, PanelSource>?): RowContent {
        val media = mutableListOf<Media>()
        val links = mutableListOf<Link>()
        val event = row.events.firstOrNull()
        val historyTarget = event?.takeIf { panels != null && !row.implicit && ExportEditHistory.hasHistory(document.snapshot.options, it) }?.let { edited ->
            ("edits:" + edited.id).also { panels!![it] = PanelSource(TimelineExportRenderer.Sheet.Revisions, listOf(edited)) }
        }
        val editedSuffix = document.context.getString(CommonStrings.edited_suffix)
        fun visit(view: View, x: Float, y: Float) {
            if (view.visibility != View.VISIBLE || view.alpha == 0f) return
            if (view is TextView) view.layout?.let { layout ->
                val spanned = view.text as? Spanned
                val edited = if (historyTarget == null || spanned == null) null else ExportEditHistory.suffixRange(spanned, editedSuffix)
                for (index in 0 until layout.lineCount) {
                    val start = layout.getLineStart(index).coerceAtMost(view.text.length)
                    val end = layout.getLineEnd(index).coerceAtMost(view.text.length)
                    fun addLink(spanStart: Int, spanEnd: Int, url: String, kind: LinkKind) {
                        val from = maxOf(start, spanStart)
                        val to = minOf(end, spanEnd)
                        if (from >= to) return
                        val left = layout.getPrimaryHorizontal(from)
                        val right = layout.getPrimaryHorizontal(to)
                        links.add(Link(x + view.totalPaddingLeft - view.scrollX + minOf(left, right),
                                y + view.totalPaddingTop - view.scrollY + layout.getLineTop(index), kotlin.math.abs(right - left),
                                layout.getLineBottom(index) - layout.getLineTop(index), url, kind))
                    }
                    spanned?.getSpans(start, end, URLSpan::class.java)?.forEach { span ->
                        val url = span.url
                        if (url.substringBefore(':').lowercase() !in WEB_SCHEMES) return@forEach
                        addLink(spanned.getSpanStart(span), spanned.getSpanEnd(span), url, LinkKind.WEB)
                    }
                    if (edited != null && historyTarget != null) addLink(edited.first, edited.second, historyTarget, LinkKind.PANEL)
                }
            }
            if (view.id == R.id.readReceiptsView && panels != null && row.implicit && renderer.receiptsOf(row.events).isNotEmpty()) {
                // A collapsed group's row and its last event's row both end on the same event, with different readers.
                val key = "receipts:" + Integer.toHexString(row.events.joinToString(" ") { it.id }.hashCode()) + ":" + row.events.last().id
                panels[key] = PanelSource(TimelineExportRenderer.Sheet.Receipts, row.events)
                links.add(Link(x, y, view.width.toFloat(), view.height, key, LinkKind.PANEL))
            }
            if (view.id == R.id.inReplyToContainer && event?.replyTo != null) links.add(Link(x, y, view.width.toFloat(), view.height, event.replyTo, LinkKind.REPLY))
            if (view.id == R.id.messageUrlPreview) event?.linkPreviews?.firstOrNull()?.url?.takeIf { it.substringBefore(':').lowercase() in WEB_SCHEMES }
                    ?.let { links.add(Link(x, y, view.width.toFloat(), view.height, it)) }
            if (view.id == R.id.messageGalleryGrid && view is ViewGroup) event?.extraAttachments?.forEachIndexed { index, item ->
                val tile = view.getChildAt(index)
                if (tile != null && item.path != null) media.add(Media(x + tile.left, y + tile.top, tile.width, tile.height, item.path, item.mime ?: "application/octet-stream"))
            }
            val path = when (view.id) {
                R.id.messageMediaShowButton -> event?.attachment
                R.id.messageThumbnailView, R.id.audioPlaybackControlButton, R.id.voicePlaybackControlButton, R.id.messageFilenameView -> event?.attachment?.takeUnless { event.mediaHidden }
                else -> null
            }
            if (path != null) media.add(Media(x, y, view.width, view.height, path,
                    document.snapshot.assets.firstOrNull { it.path == path }?.mime ?: event?.attachmentMime ?: "application/octet-stream"))
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                visit(child, x + child.left + child.translationX - view.scrollX, y + child.top + child.translationY - view.scrollY)
            }
        }
        visit(root, 0f, 0f)
        return RowContent(media, links)
    }

    private class HtmlArtworkDrawable(private val original: Drawable, private val animated: String? = null, private val shape: AvatarShape? = null) : Drawable() {
        override fun draw(target: Canvas) {
            val previous = Rect(original.bounds)
            try {
                original.bounds = bounds
                when {
                    target is TimelineHtmlCanvas && animated != null -> target.animatedImage(animated, RectF(bounds), shape)
                    target is TimelineHtmlCanvas && original !is BitmapDrawable -> target.artwork(original)
                    else -> original.draw(target)
                }
            } finally { original.bounds = previous }
        }
        override fun getIntrinsicWidth() = original.intrinsicWidth
        override fun getIntrinsicHeight() = original.intrinsicHeight
        override fun getMinimumWidth() = original.minimumWidth
        override fun getMinimumHeight() = original.minimumHeight
        override fun getPadding(padding: Rect) = original.getPadding(padding)
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private val WEB_SCHEMES = setOf("https", "http", "mailto", "tel", "matrix")
        private fun escape(value: String) = TimelineExportHtml.escape(value)

        /** The surface and title colors of the timeline's bottom sheets in [context]'s theme. */
        internal fun sheetColors(context: Context): Pair<Int, Int> {
            val value = TypedValue()
            val sheetTheme = if (context.theme.resolveAttribute(com.google.android.material.R.attr.bottomSheetDialogTheme, value, true)) value.resourceId else 0
            val themed = if (sheetTheme != 0) ContextThemeWrapper(context, sheetTheme) else context
            fun color(attribute: Int): Int {
                val resolved = TypedValue()
                if (!themed.theme.resolveAttribute(attribute, resolved, true)) return Color.TRANSPARENT
                return if (resolved.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) resolved.data
                else ContextCompat.getColor(themed, resolved.resourceId)
            }
            return color(com.google.android.material.R.attr.colorSurface) to color(im.vector.lib.ui.styles.R.attr.vctr_content_primary)
        }

        private fun panelId(key: String) = "p" + IdentityAliases.digest(key).take(16)

        private val FONT_FACES = listOf("regular" to "", "bold" to ";font-weight:700", "italic" to ";font-style:italic", "bold-italic" to ";font-weight:700;font-style:italic",
                "medium" to ";font-weight:500", "light" to ";font-weight:300")

        // Every url() here is double-quoted, which TimelineExportHtml.singleFile relies on to inline them.
        private fun imageStyles(images: Map<String, String>): String = buildString {
            append(TimelineExportHtml.embeddedImagesTag)
            FONT_FACES.forEach { (name, descriptors) -> append("@font-face{font-family:Voyage;src:url(\"assets/timeline-").append(name).append(".ttf\")").append(descriptors).append("}\n") }
            append("@font-face{font-family:VoyageMono;src:url(\"assets/timeline-mono.ttf\")}\n")
            images.forEach { (path, name) -> append('.').append(name).append("{background-image:url(\"").append(path).append("\")}\n") }
            append("</style>")
        }

        fun document(snapshot: TimelineExportSnapshot, layouts: List<Layout>, panels: String = "", images: Map<String, String> = emptyMap()): String = buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self' data:; media-src 'self'; font-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'\">")
            append("<title>").append(escape(snapshot.roomName)).append("</title><style>")
            append("body{margin:0;background:")
                    .append(TimelineExportHtml.color(snapshot.options.background, "#000000")).append(";color:")
                    .append(TimelineExportHtml.color(snapshot.options.foreground, "#ffffff")).append(";font-family:Voyage,Roboto,sans-serif}")
            append(":root{color-scheme:").append(if (ExportTheme.light(snapshot.options.theme)) "light" else "dark").append("}")
            append("button{font:inherit;padding:8px;margin:0 4px}article{display:block;position:relative;width:100%;margin:0}.native-row{position:relative;overflow:hidden}.ink{position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none}.text,.drawing{position:absolute;left:0;top:0;transform-origin:0 0;-webkit-transform-origin:0 0}.text{white-space:pre;pointer-events:auto;user-select:text}.drawing{pointer-events:none}.hotspot{position:absolute;display:block}.text::selection{color:white;background:#2867bc}.layout{display:none;margin:auto}.collapsed,.expanded{cursor:default}.group label{display:block}.toggle{position:absolute;opacity:0;pointer-events:none}.toggle:checked~.collapsed,.toggle:not(:checked)~.expanded,[hidden]{display:none}.viewer{position:fixed;inset:0;top:0;left:0;bottom:0;right:0;z-index:4;background:rgba(0,0,0,.96);overflow:auto;text-align:center;color:white}.viewer img,.viewer video{max-width:100%;max-height:90vh}.viewer button{display:block;margin:12px auto}.viewer audio{max-width:90%;margin:40px auto}")
            // The timeline's bottom sheets: Material's 32% scrim, a flat surface sliding up, title as in bottom_sheet_generic_list_with_title.
            append(".scrim{position:fixed;top:0;left:0;right:0;bottom:0;z-index:3;background:rgba(0,0,0,.32);opacity:0;-webkit-transition:opacity .2s;transition:opacity .2s}.scrim.shown{opacity:1}")
            append(".bottom-sheet{position:absolute;left:0;right:0;bottom:0;margin:0 auto;max-height:90%;overflow-x:hidden;overflow-y:auto;-webkit-overflow-scrolling:touch;padding-top:8px;-webkit-transform:translateY(100%);transform:translateY(100%);-webkit-transition:-webkit-transform .2s ease-out;transition:transform .2s ease-out}.scrim.shown .bottom-sheet{-webkit-transform:none;transform:none}")
            append(".panel{display:none}.panel:target{display:block;position:fixed;left:0;right:0;bottom:0;z-index:3;margin:0 auto;max-height:90%;overflow-y:auto;padding-top:8px;box-shadow:0 0 0 100vmax rgba(0,0,0,.32)}")
            append(".panel-close{float:right;padding:8px 16px;font-size:20px;line-height:24px;text-decoration:none}.scrim .panel-close{display:none}")
            append(".panel-title{font-size:16px;line-height:24px;padding:8px}.panel-body{overflow:hidden}.panel-rows{position:relative;transform-origin:0 0;-webkit-transform-origin:0 0}")
            layouts.forEachIndexed { index, layout ->
                val low = if (index == 0) 0 else layouts[index - 1].width + (layout.width - layouts[index - 1].width) / 2
                val high = layouts.getOrNull(index + 1)?.let { layout.width + (it.width - layout.width) / 2 - 1 }
                append("@media(min-width:").append(low).append("px)")
                if (high != null) append(" and (max-width:").append(high).append("px)")
                append("{.layout-").append(index).append("{display:block;max-width:").append(layout.width).append("px}}")
            }
            append("</style></head><body>")
            layouts.forEachIndexed { index, layout -> append("<main class=\"layout layout-").append(index).append("\">").append("<div class=\"sheet\" style=\"width:").append(layout.width).append("px\">").append(layout.rows).append("</div></main>") }
            append(panels)
            append(imageStyles(images))
            append(TimelineExportHtml.snapshotTag).append(MatrixJsonParser.getMoshi().adapter(TimelineExportSnapshot::class.java).toJson(snapshot.forArchive()).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")).append("</script>")
            append("<script>").append(SCRIPT).append("</script></body></html>")
        }
        internal val SCRIPT = """
            function resolveAsset(path){return window.voyageAssets&&window.voyageAssets[path]||path;}
            function positionText(sheet){var runs=sheet.querySelectorAll('.text[data-advances]');for(var i=0;i<runs.length;i++){var run=runs[i],raw=run.getAttribute('data-advances');if(run.getAttribute('data-positioned'))continue;if(!raw){var advance=parseFloat(run.getAttribute('data-advance'));if(!run.offsetWidth)continue;if(advance>0){run.style.transform+=' scaleX('+(advance/run.offsetWidth)+')';run.style.webkitTransform=run.style.transform;}run.setAttribute('data-positioned','true');continue;}var widths=raw.split(','),text=run.textContent,x=0;run.textContent='';run.style.letterSpacing='0px';for(var j=0;j<text.length;){var start=j,advance=parseFloat(widths[j++]);while(j<text.length&&parseFloat(widths[j])===0){j++;}var glyph=document.createElement('span');glyph.style.position='absolute';glyph.style.left=x+'px';glyph.style.top='0';glyph.style.textDecoration=run.style.textDecoration;glyph.textContent=text.substring(start,j);run.appendChild(glyph);x+=advance;}run.setAttribute('data-positioned','true');}}
            function resizeLayouts(){var mains=document.querySelectorAll('.layout');for(var i=0;i<mains.length;i++){var sheet=mains[i].firstChild;if(!mains[i].clientWidth)continue;var scale=Math.min(1,mains[i].clientWidth/parseFloat(sheet.style.width));positionText(sheet);sheet.style.transformOrigin='0 0';sheet.style.webkitTransformOrigin='0 0';sheet.style.transform='scale('+scale+')';sheet.style.webkitTransform=sheet.style.transform;mains[i].style.height=(sheet.offsetHeight*scale)+'px';}var open=document.querySelectorAll('.scrim');for(var k=0;k<open.length;k++)scaleSheet(open[k]);}window.onresize=resizeLayouts;window.onload=resizeLayouts;
            function scaleSheet(scrim){var sheet=scrim.firstChild,body=sheet.querySelector('.panel-body'),rows=body.firstChild,width=parseFloat(rows.style.width),scale=Math.min(1,sheet.clientWidth/width);positionText(rows);rows.style.transform='scale('+scale+')';rows.style.webkitTransform=rows.style.transform;body.style.height=(rows.offsetHeight*scale)+'px';}
            function closePanel(scrim){scrim.className='scrim';setTimeout(function(){if(scrim.parentNode)scrim.parentNode.removeChild(scrim);},200);}
            function openPanel(e,a){e.stopPropagation();var key=a.getAttribute('data-panel'),panels=document.querySelectorAll('.panel'),panel=null;for(var i=0;i<panels.length;i++){if(panels[i].getAttribute('data-panel')===key){panel=panels[i];break;}}if(!panel)return false;var scrim=document.createElement('div'),sheet=document.createElement('div');scrim.className='scrim';sheet.className='bottom-sheet';sheet.style.background=panel.style.background;sheet.style.maxWidth=panel.getAttribute('data-width')+'px';sheet.innerHTML=panel.innerHTML;scrim.appendChild(sheet);scrim.onclick=function(ev){if(ev.target===scrim)closePanel(scrim);};document.body.appendChild(scrim);scaleSheet(scrim);scrim.offsetHeight;scrim.className='scrim shown';return false;}
            document.onkeydown=function(e){if(e.key==='Escape'||e.keyCode===27){var open=document.querySelectorAll('.scrim.shown');if(open.length)closePanel(open[open.length-1]);}};

            function navigateReply(e,a){e.stopPropagation();var id=a.getAttribute('data-reply'),rows=document.querySelectorAll('article[data-event]');for(var i=0;i<rows.length;i++){if(rows[i].getAttribute('data-event')!==id)continue;var p=rows[i].parentNode;while(p&&p!==document.body){if(p.className==='group')p.firstChild.checked=true;p=p.parentNode;}if(rows[i].getBoundingClientRect().width){resizeLayouts();rows[i].scrollIntoView();break;}}return false;}
            function openMedia(e,a){e.stopPropagation();var name=a.getAttribute('data-path'),path=resolveAsset(name),mime=a.getAttribute('data-mime'),box=document.createElement('div'),close=document.createElement('button'),media;box.className='viewer';close.textContent='×';close.onclick=function(){document.body.removeChild(box);};box.appendChild(close);if(mime.indexOf('image/')===0)media=document.createElement('img');else if(mime.indexOf('video/')===0||mime.indexOf('audio/')===0){media=document.createElement(mime.indexOf('video/')===0?'video':'audio');media.controls=true;media.autoplay=true;}else{media=document.createElement('a');media.href=path;media.download='';media.textContent=name;}media.src=path;box.appendChild(media);document.body.appendChild(box);return false;}
        """.trimIndent()
    }
}
