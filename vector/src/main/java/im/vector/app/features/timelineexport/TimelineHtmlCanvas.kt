/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.NinePatch
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import im.vector.app.features.home.avatar.AvatarShapes
import im.vector.app.features.settings.AvatarShape
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor

/** Records native drawing as selectable HTML text and decorative assets, never flattened rows. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class TimelineHtmlCanvas(private val outputWidth: Int, private val outputHeight: Int, private val artwork: Artwork, private val bitmapSources: Map<Bitmap, String> = emptyMap()) : Canvas() {
    internal class Artwork(private val directory: File) {
        val files = linkedSetOf<String>()
        private val pending = linkedMapOf<String, Bitmap>()
        private val stored = WeakHashMap<Bitmap, Pair<Int, String>>()
        private val sources = mutableMapOf<Triple<String, Int, Int>, Boolean>()
        private val classes = linkedMapOf<String, String>()

        /** Path to the class whose background is that image, written once into the page's image stylesheet. */
        val imageClasses: Map<String, String> get() = classes
        fun imageClass(path: String): String? = path.takeIf { SAFE_PATH.matches(it) }?.let { classes.getOrPut(it) { "i" + classes.size.toString(36) } }
        fun source(path: String, bitmap: Bitmap): String? = path.takeIf { sources.getOrPut(Triple(path, bitmap.width, bitmap.height)) {
            val file = File(directory, path)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth != bitmap.width || bounds.outHeight != bitmap.height) return@getOrPut false
            val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            orientation == 1
        } }
        fun store(bitmap: Bitmap): String {
            val generation = bitmap.generationId
            stored[bitmap]?.takeIf { it.first == generation }?.let { return it.second }
            val pixels = ByteBuffer.allocate(bitmap.rowBytes * bitmap.height)
            bitmap.copyPixelsToBuffer(pixels)
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update("${bitmap.width}x${bitmap.height}".toByteArray(Charsets.UTF_8)); update(pixels.array())
            }.digest().joinToString("") { "%02x".format(it) }
            val path = "assets/art-$digest.png"
            if (files.add(path)) pending[path] = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            stored[bitmap] = generation to path
            return path
        }
        fun flush() {
            try { pending.forEach { (path, bitmap) -> File(directory, path).also { it.parentFile?.mkdirs() }.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } } finally { clearReferences() }
        }
        fun clearReferences() { pending.values.forEach { it.recycle() }; pending.clear() }

        private companion object {
            val SAFE_PATH = Regex("[A-Za-z0-9._/-]+")
        }
    }

    // A class keeps each image in one stylesheet rule, which a single-file export can inline without JavaScript.
    private fun imageAttributes(path: String): Pair<String, String> = artwork.imageClass(path)
            ?.let { " $it" to "" } ?: ("" to "background-image:url('${TimelineExportHtml.escape(path)}');")

    private data class State(val matrix: Matrix, val clips: List<Pair<Path, Region.Op>>, val bounds: RectF, val alpha: Int)
    private var state = State(Matrix(), emptyList(), RectF(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat()), 255)
    private data class Saved(val state: State, val flags: Int)
    private val saved = mutableListOf<Saved>()
    val html = StringBuilder()
    override fun getWidth() = outputWidth
    override fun getHeight() = outputHeight
    override fun getDensity() = Bitmap.DENSITY_NONE
    override fun getSaveCount() = saved.size + 1
    override fun save(): Int = saveState(ALL_SAVE_FLAG)
    private fun saveState(flags: Int): Int {
        val count = super.save()
        saved.add(Saved(state.copy(matrix = Matrix(state.matrix), bounds = RectF(state.bounds)), flags))
        return count
    }

    // Older framework drawing code calls the removed save(flags) overload.
    fun save(flags: Int): Int = saveState(flags)
    override fun restore() {
        if (saved.isEmpty()) { super.restore(); return }
        val restored = saved.removeAt(saved.lastIndex)
        val current = state
        super.restore()
        state = restored.state.copy(
                matrix = if (restored.flags and 1 != 0) restored.state.matrix else current.matrix,
                clips = if (restored.flags and 2 != 0) restored.state.clips else current.clips,
                bounds = if (restored.flags and 2 != 0) restored.state.bounds else current.bounds)
        super.setMatrix(state.matrix)
    }
    override fun restoreToCount(saveCount: Int) { while (this.saveCount > saveCount) restore() }
    override fun saveLayer(bounds: RectF?, paint: Paint?, flags: Int) = saveLayerAlpha(bounds, paint?.alpha ?: 255, flags)
    override fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: Paint?, flags: Int) = saveLayer(RectF(left, top, right, bottom), paint, flags)
    override fun saveLayerAlpha(bounds: RectF?, alpha: Int, flags: Int): Int = save().also { state = state.copy(alpha = state.alpha * alpha / 255) }
    override fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int, flags: Int) = saveLayerAlpha(RectF(left, top, right, bottom), alpha, flags)
    override fun saveLayer(bounds: RectF?, paint: Paint?) = saveLayer(bounds, paint, ALL_SAVE_FLAG)
    override fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: Paint?) = saveLayer(left, top, right, bottom, paint, ALL_SAVE_FLAG)
    override fun saveLayerAlpha(bounds: RectF?, alpha: Int) = saveLayerAlpha(bounds, alpha, ALL_SAVE_FLAG)
    override fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int) = saveLayerAlpha(left, top, right, bottom, alpha, ALL_SAVE_FLAG)
    override fun translate(dx: Float, dy: Float) { super.translate(dx, dy); super.getMatrix(state.matrix) }
    override fun scale(sx: Float, sy: Float) { super.scale(sx, sy); super.getMatrix(state.matrix) }
    override fun rotate(degrees: Float) { super.rotate(degrees); super.getMatrix(state.matrix) }
    override fun skew(sx: Float, sy: Float) { super.skew(sx, sy); super.getMatrix(state.matrix) }
    override fun concat(matrix: Matrix?) { super.concat(matrix); super.getMatrix(state.matrix) }
    override fun setMatrix(matrix: Matrix?) { super.setMatrix(matrix); super.getMatrix(state.matrix) }
    override fun getMatrix(matrix: Matrix) { matrix.set(state.matrix) }
    override fun getClipBounds(bounds: Rect): Boolean {
        val inverse = Matrix()
        val local = RectF(state.bounds)
        if (state.matrix.invert(inverse)) inverse.mapRect(local)
        local.roundOut(bounds)
        return !bounds.isEmpty
    }
    override fun clipRect(rect: RectF) = clipRect(rect.left, rect.top, rect.right, rect.bottom)
    override fun clipRect(rect: Rect) = clipRect(RectF(rect))
    override fun clipRect(left: Int, top: Int, right: Int, bottom: Int) = clipRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean = clipPath(Path().apply { addRect(left, top, right, bottom, Path.Direction.CW) })
    override fun clipRect(rect: RectF, op: Region.Op) = clipRect(rect.left, rect.top, rect.right, rect.bottom, op)
    override fun clipRect(rect: Rect, op: Region.Op) = clipRect(RectF(rect), op)
    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float, op: Region.Op) = clipPath(Path().apply { addRect(left, top, right, bottom, Path.Direction.CW) }, op)
    override fun clipOutRect(rect: RectF) = clipRect(rect, Region.Op.DIFFERENCE)
    override fun clipOutRect(rect: Rect) = clipRect(rect, Region.Op.DIFFERENCE)
    override fun clipOutRect(left: Float, top: Float, right: Float, bottom: Float) = clipRect(left, top, right, bottom, Region.Op.DIFFERENCE)
    override fun clipOutPath(path: Path) = clipPath(path, Region.Op.DIFFERENCE)
    override fun clipPath(path: Path) = clipPath(path, Region.Op.INTERSECT)
    override fun clipPath(path: Path, op: Region.Op): Boolean {
        val transformed = Path(path).apply { transform(state.matrix) }
        val bounds = RectF().also { transformed.computeBounds(it, true) }
        if (op == Region.Op.INTERSECT && !bounds.intersect(state.bounds)) bounds.setEmpty()
        if (op == Region.Op.DIFFERENCE) bounds.set(state.bounds)
        state = state.copy(clips = state.clips + (transformed to op), bounds = bounds)

        return !bounds.isEmpty
    }
    override fun quickReject(rect: RectF) = false
    override fun quickReject(path: Path) = false
    override fun quickReject(left: Float, top: Float, right: Float, bottom: Float) = false
    override fun quickReject(rect: RectF, type: EdgeType) = false
    override fun quickReject(path: Path, type: EdgeType) = false
    override fun quickReject(left: Float, top: Float, right: Float, bottom: Float, type: EdgeType) = false

    private fun number(value: Float): String = if (value == value.toInt().toFloat()) value.toInt().toString() else java.lang.Float.toString(value)
    private fun color(paint: Paint) = "rgba(${Color.red(paint.color)},${Color.green(paint.color)},${Color.blue(paint.color)},${paint.alpha * state.alpha / 65025f})"
    private fun transformValues(matrix: Matrix) = FloatArray(9).also(matrix::getValues)
    private fun matrix(matrix: Matrix = state.matrix): String {
        val values = transformValues(matrix)
        return "matrix(${number(values[0])},${number(values[3])},${number(values[1])},${number(values[4])},${number(values[2])},${number(values[5])})"
    }
    private fun layer(content: String) {
        if (state.bounds.isEmpty || state.alpha == 0) return
        val b = state.bounds
        html.append("<span class=\"ink\" style=\"clip:rect(${number(b.top)}px,${number(b.right)}px,${number(b.bottom)}px,${number(b.left)}px)\">").append(content).append("</span>")
    }
    private fun text(text: String, x: Float, y: Float, rtl: Boolean, paint: Paint) {
        if (text.isEmpty() || paint.alpha == 0 || paint.textSize <= 0f) return
        if (paint.maskFilter != null) {
            val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
            val area = RectF(bounds).apply { offset(x, y); inset(-paint.textSize * 2, -paint.textSize * 2) }
            raster(area, paint) { canvas ->
                if (Build.VERSION.SDK_INT >= 23) canvas.drawTextRun(text, 0, text.length, 0, text.length, x, y, rtl, paint)
                else canvas.drawText(text, x, y, paint)
            }
            return
        }
        val font = paint.typeface ?: Typeface.DEFAULT
        val family = if (font == Typeface.create("monospace", font.style)) "VoyageMono,monospace" else if (font == Typeface.SERIF) "serif" else "Voyage,Roboto,sans-serif"
        val weight = when {
            Build.VERSION.SDK_INT >= 28 -> font.weight
            font.isBold -> 700
            Build.VERSION.SDK_INT >= 21 && font != Typeface.create("sans-serif", font.style) && font == Typeface.create("sans-serif-medium", font.style) -> 500
            Build.VERSION.SDK_INT >= 16 && font != Typeface.create("sans-serif", font.style) && font == Typeface.create("sans-serif-light", font.style) -> 300
            else -> 400
        }
        val metrics = paint.fontMetrics
        val width = paint.measureText(text)
        val anchor = when { paint.textAlign == Paint.Align.RIGHT -> width; paint.textAlign == Paint.Align.CENTER -> width / 2; else -> 0f }
        val transform = Matrix(state.matrix).apply { preTranslate(x - anchor, y); preSkew(paint.textSkewX, 0f); preScale(paint.textScaleX, 1f); preTranslate(0f, metrics.ascent) }
        val decoration = listOfNotNull("underline".takeIf { paint.isUnderlineText }, "line-through".takeIf { paint.isStrikeThruText }).joinToString(" ")
        val widths = FloatArray(text.length)
        if (Build.VERSION.SDK_INT >= 29) paint.getTextRunAdvances(text.toCharArray(), 0, text.length, 0, text.length, rtl, widths, 0)
        else paint.getTextWidths(text, widths)
        val positioned = !rtl && text.all { it.code < 0x530 || it.code in 0x1e00..0x1eff }
        val spacing = (if (Build.VERSION.SDK_INT >= 21) paint.letterSpacing * paint.textSize else 0f) + if (positioned) fitSpacing(text, paint, width) else 0f
        layer("<span class=\"text\" data-advance=\"${paint.measureText(text) / paint.textScaleX}\" data-advances=\"${if (positioned) widths.joinToString(",") { number(it / paint.textScaleX) } else ""}\" dir=\"${if (rtl) "rtl" else "auto"}\" style=\"transform:${matrix(transform)};-webkit-transform:${matrix(transform)};font-family:$family;font-size:${number(paint.textSize)}px;line-height:${number(metrics.descent - metrics.ascent)}px;font-weight:$weight;-webkit-text-stroke:${if (paint.isFakeBoldText) paint.textSize / 30f else 0f}px;font-style:${if (font.isItalic) "italic" else "normal"};color:${color(paint)};letter-spacing:${number(spacing)}px;text-decoration:$decoration\">${TimelineExportHtml.escape(text)}</span>")
    }

    // Android rounds glyph advances to whole pixels and browsers don't, so without JavaScript to place each glyph a long
    // line overruns its TextView clip; the difference is spread over its letters instead. Browsers drop ligatures under
    // letter-spacing, so the reference is measured without them (API 21+: before that a line may stay a pixel long).
    private fun fitSpacing(text: String, paint: Paint, width: Float): Float {
        val reference = Paint(paint).apply {
            textSize = UNHINTED_TEXT_SIZE
            if (Build.VERSION.SDK_INT >= 21) fontFeatureSettings = listOfNotNull(fontFeatureSettings?.takeIf { it.isNotBlank() }, "'liga' 0, 'clig' 0").joinToString(", ")
        }
        val natural = reference.measureText(text) * paint.textSize / UNHINTED_TEXT_SIZE
        val characters = BreakIterator.getCharacterInstance().run {
            setText(text)
            var count = 0
            while (next() != BreakIterator.DONE) count++
            count
        }
        return if (characters == 0) 0f else (width - natural) / paint.textScaleX / characters
    }

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) = text(text, x, y, false, paint)
    override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) = text(text.substring(start, end), x, y, false, paint)
    override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) = text(text.subSequence(start, end).toString(), x, y, false, paint)
    override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) = text(String(text, index, count), x, y, false, paint)
    override fun drawTextRun(text: CharSequence, start: Int, end: Int, contextStart: Int, contextEnd: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) = text(text.subSequence(start, end).toString(), x, y, isRtl, paint)
    override fun drawTextRun(text: CharArray, index: Int, count: Int, contextIndex: Int, contextCount: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) = text(String(text, index, count), x, y, isRtl, paint)

    private fun vector(bounds: RectF, paint: Paint, shape: String, draw: (Canvas) -> Unit) {
        // layer() only clips to the bounding box, so rounded or subtracted clips need the raster path.
        val shapedClip = state.clips.any { (clip, op) -> op != Region.Op.INTERSECT || !clip.isRect(null) }
        if (shapedClip || paint.shader != null || paint.maskFilter != null || paint.colorFilter != null || paint.pathEffect != null || paint.xfermode != null) { raster(bounds, paint, draw = draw); return }
        val style = if (paint.style == Paint.Style.STROKE) "fill=\"none\" stroke=\"${color(paint)}\" stroke-width=\"${paint.strokeWidth}\"" else "fill=\"${color(paint)}\""
        layer("<svg class=\"drawing\" width=\"$outputWidth\" height=\"$outputHeight\" style=\"transform:${matrix()};-webkit-transform:${matrix()}\" aria-hidden=\"true\"><g $style>$shape</g></svg>")
    }
    fun artwork(drawable: Drawable) { raster(RectF(drawable.bounds), null) { drawable.draw(it) } }
    fun animatedImage(path: String, bounds: RectF, shape: AvatarShape? = null) {
        raster(bounds, null, path) { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            if (shape == null) canvas.drawRect(bounds, paint)
            else canvas.drawPath(AvatarShapes.path(shape, bounds), paint)
        }
    }
    private fun raster(local: RectF, paint: Paint?, animated: String? = null, imageBounds: RectF = local, draw: (Canvas) -> Unit) {
        val bounds = RectF(local).apply { inset(-2f - (paint?.strokeWidth ?: 0f), -2f - (paint?.strokeWidth ?: 0f)); state.matrix.mapRect(this) }
        if (!bounds.intersect(state.bounds)) return
        val left = floor(bounds.left).toInt(); val top = floor(bounds.top).toInt()
        val width = ceil(bounds.right).toInt() - left; val height = ceil(bounds.bottom).toInt() - top
        if (width <= 0 || height <= 0) return
        require(width.toLong() * height <= 16 * 1024 * 1024) { "Timeline artwork exceeds rendering budget ($width x $height)" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(-left.toFloat(), -top.toFloat()); canvas.clipRect(state.bounds); state.clips.forEach { (path, op) -> canvas.clipPath(path, op) }; canvas.concat(state.matrix); draw(canvas)
            if (animated != null) {
                val bytes = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
                val path = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                val full = RectF(imageBounds).apply { state.matrix.mapRect(this) }
                val (imageClass, image) = imageAttributes(animated)
                layer("<span class=\"drawing$imageClass\" aria-hidden=\"true\" style=\"left:${left}px;top:${top}px;width:${width}px;height:${height}px;opacity:${state.alpha * (paint?.alpha ?: 255) / 65025f};${image}background-repeat:no-repeat;background-size:${full.width()}px ${full.height()}px;background-position:${full.left - left}px ${full.top - top}px;-webkit-mask-image:url('$path');mask-image:url('$path');-webkit-mask-size:100% 100%;mask-size:100% 100%\"></span>")
            } else {
                val (imageClass, image) = imageAttributes(artwork.store(bitmap))
                layer("<span class=\"drawing$imageClass\" aria-hidden=\"true\" style=\"left:${left}px;top:${top}px;width:${width}px;height:${height}px;opacity:${state.alpha / 255f};${image}background-size:100% 100%\"></span>")
            }
        } finally { bitmap.recycle() }
    }
    override fun drawRect(rect: RectF, paint: Paint) = drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
    override fun drawRect(rect: Rect, paint: Paint) = drawRect(RectF(rect), paint)
    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) = vector(RectF(left, top, right, bottom), paint,
            "<rect x=\"$left\" y=\"$top\" width=\"${right - left}\" height=\"${bottom - top}\"/>") { it.drawRect(left, top, right, bottom, paint) }
    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) = vector(rect, paint, "<rect x=\"${rect.left}\" y=\"${rect.top}\" width=\"${rect.width()}\" height=\"${rect.height()}\" rx=\"$rx\" ry=\"$ry\"/>") { it.drawRoundRect(rect, rx, ry, paint) }
    override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) = drawRoundRect(RectF(left, top, right, bottom), rx, ry, paint)
    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) = vector(RectF(cx - radius, cy - radius, cx + radius, cy + radius), paint, "<circle cx=\"$cx\" cy=\"$cy\" r=\"$radius\"/>") { it.drawCircle(cx, cy, radius, paint) }
    override fun drawOval(oval: RectF, paint: Paint) = vector(oval, paint, "<ellipse cx=\"${oval.centerX()}\" cy=\"${oval.centerY()}\" rx=\"${oval.width() / 2}\" ry=\"${oval.height() / 2}\"/>") { it.drawOval(oval, paint) }
    override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) = drawOval(RectF(left, top, right, bottom), paint)
    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) = raster(RectF(minOf(startX, stopX), minOf(startY, stopY), maxOf(startX, stopX), maxOf(startY, stopY)), paint) { it.drawLine(startX, startY, stopX, stopY, paint) }
    override fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: Paint) { for (i in offset until offset + count step 4) drawLine(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], paint) }
    override fun drawLines(pts: FloatArray, paint: Paint) = drawLines(pts, 0, pts.size, paint)
    override fun drawPath(path: Path, paint: Paint) { val bounds = RectF().also { path.computeBounds(it, true) }; raster(bounds, paint) { it.drawPath(path, paint) } }
    override fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) = raster(oval, paint) { it.drawArc(oval, startAngle, sweepAngle, useCenter, paint) }
    override fun drawArc(left: Float, top: Float, right: Float, bottom: Float, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) = drawArc(RectF(left, top, right, bottom), startAngle, sweepAngle, useCenter, paint)
    override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) = drawBitmap(bitmap, null, RectF(left, top, left + bitmap.width, top + bitmap.height), paint)
    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) = drawBitmap(bitmap, src, RectF(dst), paint)
    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        val values = transformValues(state.matrix)
        if (paint?.colorFilter != null || paint?.xfermode != null || paint?.maskFilter != null ||
                values[1] != 0f || values[3] != 0f || values[0] <= 0f || values[4] <= 0f) {
            raster(dst, paint) { it.drawBitmap(bitmap, src, dst, paint) }
        } else {
            val source = src ?: Rect(0, 0, bitmap.width, bitmap.height)
            if (source.isEmpty || dst.isEmpty) return
            val scaleX = dst.width() / source.width()
            val scaleY = dst.height() / source.height()
            val imageBounds = RectF(dst.left - source.left * scaleX, dst.top - source.top * scaleY,
                    dst.left + (bitmap.width - source.left) * scaleX, dst.top + (bitmap.height - source.top) * scaleY)
            val path = bitmapSources[bitmap] ?: artwork.store(bitmap)
            raster(dst, paint, path, imageBounds) { it.drawRect(dst, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }) }
        }
    }
    override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) { val count = save(); concat(matrix); drawBitmap(bitmap, 0f, 0f, paint); restoreToCount(count) }
    override fun drawPatch(patch: NinePatch, dst: Rect, paint: Paint?) = drawPatch(patch, RectF(dst), paint)
    override fun drawPatch(patch: NinePatch, dst: RectF, paint: Paint?) = raster(dst, paint) { canvas -> patch.draw(canvas, Rect().also(dst::roundOut), paint) }
    override fun drawPoint(x: Float, y: Float, paint: Paint) = drawCircle(x, y, maxOf(.5f, paint.strokeWidth / 2), paint)
    override fun drawPoints(points: FloatArray, offset: Int, count: Int, paint: Paint) { for (i in offset until offset + count step 2) drawPoint(points[i], points[i + 1], paint) }
    override fun drawPoints(points: FloatArray, paint: Paint) = drawPoints(points, 0, points.size, paint)
    override fun drawBitmap(colors: IntArray, offset: Int, stride: Int, x: Float, y: Float, width: Int, height: Int, hasAlpha: Boolean, paint: Paint?) {
        val bitmap = Bitmap.createBitmap(colors, offset, stride, width, height, if (hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
        try { drawBitmap(bitmap, x, y, paint) } finally { bitmap.recycle() }
    }
    override fun drawBitmap(colors: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int, hasAlpha: Boolean, paint: Paint?) = drawBitmap(colors, offset, stride, x.toFloat(), y.toFloat(), width, height, hasAlpha, paint)
    override fun drawBitmapMesh(bitmap: Bitmap, meshWidth: Int, meshHeight: Int, vertices: FloatArray, vertexOffset: Int, colors: IntArray?, colorOffset: Int, paint: Paint?) {
        val count = (meshWidth + 1) * (meshHeight + 1) * 2
        val xs = (vertexOffset until vertexOffset + count step 2).map { vertices[it] }
        val ys = (vertexOffset + 1 until vertexOffset + count step 2).map { vertices[it] }
        val bounds = RectF(xs.minOrNull() ?: 0f, ys.minOrNull() ?: 0f, xs.maxOrNull() ?: 0f, ys.maxOrNull() ?: 0f)
        raster(bounds, paint) { it.drawBitmapMesh(bitmap, meshWidth, meshHeight, vertices, vertexOffset, colors, colorOffset, paint) }
    }
    override fun drawPicture(picture: Picture) = raster(RectF(0f, 0f, picture.width.toFloat(), picture.height.toFloat()), null) { it.drawPicture(picture) }
    override fun drawPicture(picture: Picture, dst: RectF) = raster(dst, null) { it.drawPicture(picture, dst) }
    override fun drawPicture(picture: Picture, dst: Rect) = drawPicture(picture, RectF(dst))
    override fun drawColor(color: Int) = drawRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), Paint().apply { this.color = color })
    override fun drawColor(color: Int, mode: PorterDuff.Mode) = drawColor(color)
    override fun drawPaint(paint: Paint) = drawRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), paint)
    override fun drawRGB(r: Int, g: Int, b: Int) = drawColor(Color.rgb(r, g, b))
    override fun drawARGB(a: Int, r: Int, g: Int, b: Int) = drawColor(Color.argb(a, r, g, b))

    private companion object {
        // Large enough that per-glyph rounding is a negligible share of each advance.
        const val UNHINTED_TEXT_SIZE = 1024f
    }
}
