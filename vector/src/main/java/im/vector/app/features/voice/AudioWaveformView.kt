/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.voice

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class AudioWaveformView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class Alignment(var value: Int) {
        CENTER(0),
        BOTTOM(1),
        TOP(2)
    }

    private enum class Flow(var value: Int) {
        LTR(0),
        RTL(1)
    }

    data class FFT(val value: Float, var color: Int)

    private fun Int.dp() = this * Resources.getSystem().displayMetrics.density

    // Configuration fields
    private var alignment = Alignment.CENTER
    private var flow = Flow.LTR
    private var verticalPadding = 4.dp()
    private var horizontalPadding = 4.dp()
    private var barWidth = 2.dp()
    private var barSpace = 1.dp()
    private var barMinHeight = 1.dp()
    private var isBarRounded = true

    private val rawFftList = mutableListOf<FFT>()
    private var visibleBarHeights = mutableListOf<FFT>()
    private var isSummarized = false
    private var playedColors: Triple<Float, Int, Int>? = null

    private val barPaint = Paint()

    init {
        attrs?.let {
            context
                    .theme
                    .obtainStyledAttributes(
                            attrs,
                            im.vector.lib.ui.styles.R.styleable.AudioWaveformView,
                            0,
                            0
                    )
                    .apply {
                        alignment = Alignment.values().find {
                            it.value == getInt(im.vector.lib.ui.styles.R.styleable.AudioWaveformView_alignment, alignment.value)
                        }!!
                        flow = Flow.values().find { it.value == getInt(im.vector.lib.ui.styles.R.styleable.AudioWaveformView_flow, alignment.value) }!!
                        verticalPadding = getDimension(im.vector.lib.ui.styles.R.styleable.AudioWaveformView_verticalPadding, verticalPadding)
                        horizontalPadding = getDimension(im.vector.lib.ui.styles.R.styleable.AudioWaveformView_horizontalPadding, horizontalPadding)
                        barWidth = getDimension(im.vector.lib.ui.styles.R.styleable.AudioWaveformView_barWidth, barWidth)
                        barSpace = getDimension(im.vector.lib.ui.styles.R.styleable.AudioWaveformView_barSpace, barSpace)
                        barMinHeight = getDimension(im.vector.lib.ui.styles.R.styleable.AudioWaveformView_barMinHeight, barMinHeight)
                        isBarRounded = getBoolean(im.vector.lib.ui.styles.R.styleable.AudioWaveformView_isBarRounded, isBarRounded)
                        setWillNotDraw(false)
                        barPaint.isAntiAlias = true
                    }
                    .apply { recycle() }
                    .also {
                        barPaint.strokeWidth = barWidth
                        barPaint.strokeCap = if (isBarRounded) Paint.Cap.ROUND else Paint.Cap.BUTT
                    }
        }
    }

    fun initialize(fftList: List<FFT>) {
        fftList.forEach { handleNewFft(it) }
        invalidate()
    }

    fun add(fft: FFT) {
        handleNewFft(fft)
        invalidate()
    }

    fun summarize() {
        if (rawFftList.isEmpty()) return
        isSummarized = true
        flow = Flow.LTR
        rebuildVisibleBars()
        invalidate()
    }

    fun updateColors(limitPercentage: Float, colorBefore: Int, colorAfter: Int) {
        playedColors = Triple(limitPercentage, colorBefore, colorAfter)
        applyColors()
        invalidate()
    }

    fun clear() {
        rawFftList.clear()
        visibleBarHeights.clear()
        isSummarized = false
        playedColors = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildVisibleBars()
    }

    private fun rebuildVisibleBars() {
        val maxVisibleBarCount = getMaxVisibleBarCount()
        if (maxVisibleBarCount <= 0 || rawFftList.isEmpty()) {
            visibleBarHeights = mutableListOf()
            return
        }
        val source = if (isSummarized) rawFftList.summarize(maxVisibleBarCount) else rawFftList.takeLast(maxVisibleBarCount)
        visibleBarHeights = source.mapTo(mutableListOf()) { FFT(toBarHeight(it.value), it.color) }
        applyColors()
    }

    private fun applyColors() {
        val (limitPercentage, colorBefore, colorAfter) = playedColors ?: return
        val limitIndex = (visibleBarHeights.size * limitPercentage).toInt()
        visibleBarHeights.forEachIndexed { index, fft ->
            fft.color = if (index < limitIndex) {
                colorBefore
            } else {
                colorAfter
            }
        }
    }

    private fun List<FFT>.summarize(target: Int): List<FFT> = List(target) { i -> get(i * size / target) }

    private fun handleNewFft(fft: FFT) {
        rawFftList.add(fft)
        visibleBarHeights.add(FFT(toBarHeight(fft.value), fft.color))
        val maxVisibleBarCount = getMaxVisibleBarCount()
        if (maxVisibleBarCount > 0 && visibleBarHeights.size > maxVisibleBarCount) {
            visibleBarHeights = visibleBarHeights.takeLast(maxVisibleBarCount).toMutableList()
        }
    }

    private fun toBarHeight(value: Float) = max(value / MAX_FFT * (height - verticalPadding * 2), barMinHeight)

    private fun getMaxVisibleBarCount() = ((width - horizontalPadding * 2) / (barWidth + barSpace)).toInt()

    private fun drawBars(canvas: Canvas) {
        var currentX = horizontalPadding
        val flowableBarHeights = if (flow == Flow.LTR) visibleBarHeights else visibleBarHeights.reversed()

        flowableBarHeights.forEach {
            barPaint.color = it.color
            when (alignment) {
                Alignment.BOTTOM -> {
                    val startY = height - verticalPadding
                    val stopY = startY - it.value
                    canvas.drawLine(currentX, startY, currentX, stopY, barPaint)
                }
                Alignment.CENTER -> {
                    val startY = (height - it.value) / 2
                    val stopY = startY + it.value
                    canvas.drawLine(currentX, startY, currentX, stopY, barPaint)
                }
                Alignment.TOP -> {
                    val startY = verticalPadding
                    val stopY = startY + it.value
                    canvas.drawLine(currentX, startY, currentX, stopY, barPaint)
                }
            }
            currentX += barWidth + barSpace
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBars(canvas)
    }

    companion object {
        const val MAX_FFT = 32760
    }
}
