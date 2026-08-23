/*
 * Copyright 2017 Jared Rummler
 * Copyright 2026 New Vector Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.core.ui.colorpicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Parcelable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.max
import kotlin.math.min

/**
 * Saturation/value square plus a hue slider. Ported from color-picker-android (Daniel Nilsson /
 * Jared Rummler) without the alpha channel and XML attributes.
 *
 * The sat/val gradient is a ComposeShader, which hardware acceleration can't draw on every
 * platform; it's rendered into a cached software bitmap instead, so it works on ICS.
 */
class HsvColorPickerView(context: Context) : View(context) {

    fun interface OnColorChangedListener {
        fun onColorChanged(@ColorInt newColor: Int)
    }

    private class BitmapCache {
        var canvas: Canvas? = null
        var bitmap: Bitmap? = null
        var value = 0f
    }

    private val huePanelWidthPx = dp(30)
    private val panelSpacingPx = dp(10)
    private val circleTrackerRadiusPx = dp(5)
    private val sliderTrackerSizePx = dp(4)
    private val sliderTrackerOffsetPx = dp(2)
    private val requiredPadding = dp(6)

    private val satValPaint = Paint()
    private val satValTrackerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        isAntiAlias = true
    }
    private val hueTrackerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        isAntiAlias = true
    }
    private val borderPaint = Paint()

    private var valShader: Shader? = null
    private var satValBackgroundCache: BitmapCache? = null
    private var hueBackgroundCache: BitmapCache? = null

    private var hue = 360f
    private var sat = 0f
    private var value = 0f

    private var drawingRect = Rect()
    private var satValRect = Rect()
    private var hueRect = Rect()
    private var startTouchPoint: Point? = null

    private var borderColor: Int
    private var listener: OnColorChangedListener? = null

    init {
        val typed = TypedValue()
        val a = context.obtainStyledAttributes(typed.data, intArrayOf(android.R.attr.textColorSecondary))
        borderColor = a.getColor(0, 0xFF6E6E6E.toInt())
        a.recycle()
        hueTrackerPaint.color = borderColor
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    fun setOnColorChangedListener(listener: OnColorChangedListener?) {
        this.listener = listener
    }

    @ColorInt
    fun getColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun setColor(@ColorInt color: Int, callback: Boolean = false) {
        val hsv = FloatArray(3)
        Color.RGBToHSV(Color.red(color), Color.green(color), Color.blue(color), hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        if (callback) listener?.onColorChanged(getColor())
        invalidate()
    }

    override fun onSaveInstanceState(): Parcelable {
        return Bundle().apply {
            putParcelable("instanceState", super.onSaveInstanceState())
            putFloat("hue", hue)
            putFloat("sat", sat)
            putFloat("val", value)
        }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        var superState = state
        if (state is Bundle) {
            hue = state.getFloat("hue")
            sat = state.getFloat("sat")
            value = state.getFloat("val")
            @Suppress("DEPRECATION")
            superState = state.getParcelable("instanceState")
        }
        super.onRestoreInstanceState(superState)
    }

    override fun onDraw(canvas: Canvas) {
        if (drawingRect.width() <= 0 || drawingRect.height() <= 0) return
        drawSatValPanel(canvas)
        drawHuePanel(canvas)
    }

    private fun drawSatValPanel(canvas: Canvas) {
        val rect = satValRect
        borderPaint.color = borderColor
        canvas.drawRect(
                drawingRect.left.toFloat(), drawingRect.top.toFloat(),
                (rect.right + BORDER_WIDTH_PX).toFloat(), (rect.bottom + BORDER_WIDTH_PX).toFloat(),
                borderPaint
        )

        val valShader = valShader ?: LinearGradient(
                rect.left.toFloat(), rect.top.toFloat(), rect.left.toFloat(), rect.bottom.toFloat(),
                0xFFFFFFFF.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP
        ).also { valShader = it }

        val cache = satValBackgroundCache ?: BitmapCache().also { satValBackgroundCache = it }
        if (cache.bitmap == null || cache.value != hue) {
            val bitmap = cache.bitmap ?: Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888).also {
                cache.bitmap = it
                cache.canvas = Canvas(it)
            }
            val rgb = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            val satShader = LinearGradient(
                    rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.top.toFloat(),
                    0xFFFFFFFF.toInt(), rgb, Shader.TileMode.CLAMP
            )
            satValPaint.shader = ComposeShader(valShader, satShader, PorterDuff.Mode.MULTIPLY)
            cache.canvas!!.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), satValPaint)
            cache.value = hue
        }
        canvas.drawBitmap(cache.bitmap!!, null, rect, null)

        val p = satValToPoint(sat, value)
        satValTrackerPaint.color = 0xFF000000.toInt()
        canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), (circleTrackerRadiusPx - dp(1)).toFloat(), satValTrackerPaint)
        satValTrackerPaint.color = 0xFFDDDDDD.toInt()
        canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), circleTrackerRadiusPx.toFloat(), satValTrackerPaint)
    }

    private fun drawHuePanel(canvas: Canvas) {
        val rect = hueRect
        borderPaint.color = borderColor
        canvas.drawRect(
                (rect.left - BORDER_WIDTH_PX).toFloat(), (rect.top - BORDER_WIDTH_PX).toFloat(),
                (rect.right + BORDER_WIDTH_PX).toFloat(), (rect.bottom + BORDER_WIDTH_PX).toFloat(),
                borderPaint
        )

        val cache = hueBackgroundCache ?: BitmapCache().also { created ->
            val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            val bitmapCanvas = Canvas(bitmap)
            val linePaint = Paint().apply { strokeWidth = 0f }
            val lines = rect.height()
            var h = 360f
            for (i in 0 until lines) {
                linePaint.color = Color.HSVToColor(floatArrayOf(h, 1f, 1f))
                bitmapCanvas.drawLine(0f, i.toFloat(), bitmap.width.toFloat(), i.toFloat(), linePaint)
                h -= 360f / lines
            }
            created.bitmap = bitmap
            created.canvas = bitmapCanvas
            hueBackgroundCache = created
        }
        canvas.drawBitmap(cache.bitmap!!, null, rect, null)

        val p = hueToPoint(hue)
        val r = RectF(
                (rect.left - sliderTrackerOffsetPx).toFloat(),
                p.y - sliderTrackerSizePx / 2f,
                (rect.right + sliderTrackerOffsetPx).toFloat(),
                p.y + sliderTrackerSizePx / 2f
        )
        canvas.drawRoundRect(r, 2f, 2f, hueTrackerPaint)
    }

    private fun hueToPoint(hue: Float): Point {
        val height = hueRect.height().toFloat()
        return Point(hueRect.left, (height - hue * height / 360f + hueRect.top).toInt())
    }

    private fun satValToPoint(sat: Float, value: Float): Point {
        val rect = satValRect
        return Point((sat * rect.width() + rect.left).toInt(), ((1f - value) * rect.height() + rect.top).toInt())
    }

    private fun pointToSatVal(px: Float, py: Float): FloatArray {
        val rect = satValRect
        val width = rect.width().toFloat()
        val height = rect.height().toFloat()
        val x = when {
            px < rect.left -> 0f
            px > rect.right -> width
            else -> px - rect.left
        }
        val y = when {
            py < rect.top -> 0f
            py > rect.bottom -> height
            else -> py - rect.top
        }
        return floatArrayOf(1f / width * x, 1f - 1f / height * y)
    }

    private fun pointToHue(py: Float): Float {
        val rect = hueRect
        val height = rect.height().toFloat()
        val y = when {
            py < rect.top -> 0f
            py > rect.bottom -> height
            else -> py - rect.top
        }
        return 360f - y * 360f / height
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val update = when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startTouchPoint = Point(event.x.toInt(), event.y.toInt())
                if (hueRect.contains(event.x.toInt(), event.y.toInt()) || satValRect.contains(event.x.toInt(), event.y.toInt())) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                moveTrackersIfNeeded(event)
            }
            MotionEvent.ACTION_MOVE -> moveTrackersIfNeeded(event)
            MotionEvent.ACTION_UP -> {
                val moved = moveTrackersIfNeeded(event)
                startTouchPoint = null
                moved
            }
            else -> false
        }
        if (update) {
            listener?.onColorChanged(getColor())
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun moveTrackersIfNeeded(event: MotionEvent): Boolean {
        val start = startTouchPoint ?: return false
        return when {
            hueRect.contains(start.x, start.y) -> {
                hue = pointToHue(event.y)
                true
            }
            satValRect.contains(start.x, start.y) -> {
                val result = pointToSatVal(event.x, event.y)
                sat = result[0]
                value = result[1]
                true
            }
            else -> false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val widthAllowed = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val heightAllowed = MeasureSpec.getSize(heightMeasureSpec) - paddingBottom - paddingTop
        val finalWidth: Int
        val finalHeight: Int
        if (widthMode == MeasureSpec.EXACTLY || heightMode == MeasureSpec.EXACTLY) {
            if (widthMode == MeasureSpec.EXACTLY && heightMode != MeasureSpec.EXACTLY) {
                finalHeight = min(widthAllowed - panelSpacingPx - huePanelWidthPx, heightAllowed)
                finalWidth = widthAllowed
            } else if (heightMode == MeasureSpec.EXACTLY && widthMode != MeasureSpec.EXACTLY) {
                finalWidth = min(heightAllowed + panelSpacingPx + huePanelWidthPx, widthAllowed)
                finalHeight = heightAllowed
            } else {
                finalWidth = widthAllowed
                finalHeight = heightAllowed
            }
        } else {
            val widthNeeded = heightAllowed + panelSpacingPx + huePanelWidthPx
            val heightNeeded = widthAllowed - panelSpacingPx - huePanelWidthPx
            val widthOk = widthNeeded <= widthAllowed
            val heightOk = heightNeeded <= heightAllowed
            when {
                widthOk && heightOk -> {
                    finalWidth = widthAllowed
                    finalHeight = heightNeeded
                }
                !heightOk && widthOk -> {
                    finalHeight = heightAllowed
                    finalWidth = widthNeeded
                }
                !widthOk && heightOk -> {
                    finalHeight = heightNeeded
                    finalWidth = widthAllowed
                }
                else -> {
                    finalHeight = heightAllowed
                    finalWidth = widthAllowed
                }
            }
        }
        setMeasuredDimension(finalWidth + paddingLeft + paddingRight, finalHeight + paddingTop + paddingBottom)
    }

    override fun getPaddingTop() = max(super.getPaddingTop(), requiredPadding)
    override fun getPaddingBottom() = max(super.getPaddingBottom(), requiredPadding)
    override fun getPaddingLeft() = max(super.getPaddingLeft(), requiredPadding)
    override fun getPaddingRight() = max(super.getPaddingRight(), requiredPadding)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawingRect = Rect(paddingLeft, paddingTop, w - paddingRight, h - paddingBottom)
        valShader = null
        satValBackgroundCache = null
        hueBackgroundCache = null
        satValRect = Rect(
                drawingRect.left + BORDER_WIDTH_PX,
                drawingRect.top + BORDER_WIDTH_PX,
                drawingRect.right - BORDER_WIDTH_PX - panelSpacingPx - huePanelWidthPx,
                drawingRect.bottom - BORDER_WIDTH_PX
        )
        hueRect = Rect(
                drawingRect.right - huePanelWidthPx + BORDER_WIDTH_PX,
                drawingRect.top + BORDER_WIDTH_PX,
                drawingRect.right - BORDER_WIDTH_PX,
                drawingRect.bottom - BORDER_WIDTH_PX
        )
    }

    companion object {
        private const val BORDER_WIDTH_PX = 1
    }
}
