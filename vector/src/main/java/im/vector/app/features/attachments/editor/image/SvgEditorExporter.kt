/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.image

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Xml
import androidx.core.content.FileProvider
import im.vector.app.features.attachments.editor.FrameTransform
import org.matrix.android.sdk.api.util.MimeTypes
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.util.UUID
import kotlin.math.roundToInt

internal object SvgEditorExporter {
    fun export(context: Context, source: Uri, edits: ImageEditorEdits, displayName: String?, sourceSize: Pair<Int, Int>?): ImageEditorExporter.Result {
        val picture = ImageEditorExporter.readSvgPicture(context, source) ?: error("Unable to read SVG dimensions")
        val size = sourceSize ?: (picture.width to picture.height)
        val (width, height) = size
        val sideways = edits.userRotation % 180 != 0
        val frameWidth = if (sideways) height else width
        val frameHeight = if (sideways) width else height
        val left = edits.crop.left * frameWidth
        val top = edits.crop.top * frameHeight
        val outputWidth = (edits.crop.width() * frameWidth).roundToInt().coerceAtLeast(1)
        val outputHeight = (edits.crop.height() * frameHeight).roundToInt().coerceAtLeast(1)
        val transform = FrameTransform.sourceToFrame(width, height, edits.userRotation, edits.tiltDegrees).apply {
            postTranslate(-left, -top)
        }
        val values = FloatArray(9).also { transform.getValues(it) }
        val clipId = "editorClip" + UUID.randomUUID().toString().replace("-", "")
        val cropClipId = clipId + "Crop"
        val destination = ImageEditorExporter.createOutputFile(context, displayName, ".svg")
        try {
            destination.outputStream().buffered().use { output ->
                val xml = Xml.newSerializer().apply { setOutput(output, "UTF-8") }
                xml.startDocument("UTF-8", true)
                xml.startTag(null, "svg")
                xml.attribute(null, "xmlns", "http://www.w3.org/2000/svg")
                xml.attribute(null, "width", outputWidth.toString())
                xml.attribute(null, "height", outputHeight.toString())
                xml.attribute(null, "viewBox", "0 0 $outputWidth $outputHeight")
                xml.startTag(null, "defs")
                xml.startTag(null, "clipPath").attribute(null, "id", clipId)
                xml.rectangle(0f, 0f, width.toFloat(), height.toFloat())
                xml.endTag(null, "clipPath")
                xml.startTag(null, "clipPath").attribute(null, "id", cropClipId)
                xml.rectangle(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat())
                xml.endTag(null, "clipPath")
                xml.endTag(null, "defs")
                xml.startTag(null, "g").attribute(null, "clip-path", "url(#$cropClipId)")
                xml.startTag(null, "g").attribute(null, "transform", "matrix(${values[Matrix.MSCALE_X]} ${values[Matrix.MSKEW_Y]} " +
                        "${values[Matrix.MSKEW_X]} ${values[Matrix.MSCALE_Y]} ${values[Matrix.MTRANS_X]} ${values[Matrix.MTRANS_Y]})")
                xml.startTag(null, "g").attribute(null, "transform", "scale(${width.toFloat() / picture.width} ${height.toFloat() / picture.height})")
                context.contentResolver.openInputStream(source)?.use { input ->
                    val parser = Xml.newPullParser().apply {
                        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                        setInput(input, null)
                    }
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                xml.startTag(null, parser.name)
                                for (index in 0 until parser.attributeCount) {
                                    val name = parser.getAttributeName(index)
                                    if (parser.depth == 1 && name in setOf("width", "height", "x", "y")) continue
                                    xml.attribute(null, name, parser.getAttributeValue(index))
                                }
                                if (parser.depth == 1) {
                                    xml.attribute(null, "width", picture.width.toString())
                                    xml.attribute(null, "height", picture.height.toString())
                                    xml.attribute(null, "x", "0")
                                    xml.attribute(null, "y", "0")
                                }
                            }
                            XmlPullParser.END_TAG -> xml.endTag(null, parser.name)
                            XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (parser.depth > 0) xml.text(parser.text)
                        }
                        event = parser.next()
                    }
                } ?: error("Unable to read SVG")
                xml.endTag(null, "g")
                xml.startTag(null, "g").attribute(null, "clip-path", "url(#$clipId)")
                edits.censors.forEach { censor ->
                    xml.rectangle(censor.rect.left * width, censor.rect.top * height, censor.rect.width() * width,
                            censor.rect.height() * height, censor.color)
                }
                edits.strokes.filter { it.points.size >= 2 }.forEach { stroke ->
                    var lastX = stroke.points[0] * width
                    var lastY = stroke.points[1] * height
                    val path = StringBuilder("M $lastX $lastY ")
                    for (index in 2 until stroke.points.size step 2) {
                        val x = stroke.points[index] * width
                        val y = stroke.points[index + 1] * height
                        path.append("Q $lastX $lastY ${(lastX + x) / 2} ${(lastY + y) / 2} ")
                        lastX = x
                        lastY = y
                    }
                    path.append("L $lastX $lastY")
                    xml.startTag(null, "path").attribute(null, "d", path.toString())
                    xml.color("stroke", stroke.color, "fill:none;stroke-width:${stroke.widthIn(width.toFloat(), height.toFloat())};" +
                            "stroke-linecap:round;stroke-linejoin:round;")
                    xml.endTag(null, "path")
                }
                xml.endTag(null, "g")
                xml.endTag(null, "g")
                xml.endTag(null, "g")
                xml.endTag(null, "svg")
                xml.endDocument()
            }
            return ImageEditorExporter.Result(
                    FileProvider.getUriForFile(context, context.packageName + ImageEditorExporter.FILE_PROVIDER_SUFFIX, destination),
                    outputWidth, outputHeight, destination.length(), MimeTypes.Svg
            )
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun XmlSerializer.rectangle(x: Float, y: Float, width: Float, height: Float, color: Int? = null) {
        startTag(null, "rect")
        attribute(null, "x", x.toString())
        attribute(null, "y", y.toString())
        attribute(null, "width", width.toString())
        attribute(null, "height", height.toString())
        color?.let { color("fill", it) }
        endTag(null, "rect")
    }

    private fun XmlSerializer.color(attribute: String, color: Int, extraStyle: String = "") {
        val rgb = "#" + Integer.toHexString(color and 0xffffff).padStart(6, '0')
        val opacity = Color.alpha(color) / 255f
        attribute(null, "style", "$attribute:$rgb;$attribute-opacity:$opacity;$extraStyle")
    }
}
