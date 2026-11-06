/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.CancellationSignal
import android.os.Parcelable
import android.view.ScrollCaptureCallback
import android.view.ScrollCaptureSession
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.databinding.FragmentMatrixProfileBinding
import im.vector.app.features.themes.ThemeUtils
import timber.log.Timber
import java.util.function.Consumer

object ProfileScrollCapture {

    fun install(views: FragmentMatrixProfileBinding, header: View): (() -> Unit)? {
        // Older Android versions keep their existing screenshot behavior.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Callback(views, header).install()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class Callback(
            private val views: FragmentMatrixProfileBinding,
            private val header: View,
    ) : ScrollCaptureCallback {

        private val root = views.root
        private val list = views.matrixProfileRecyclerView
        private val backgroundColor = ThemeUtils.getColor(root.context, android.R.attr.colorBackground)
        private var active = false
        private var originalState: Parcelable? = null
        private var originalScrollbars = false
        private var originalOverscroll = View.OVER_SCROLL_ALWAYS
        private var listOrigin = 0
        private var scrollDelta = 0
        private var headerBottom: Int? = null
        private var pendingDraw: OneShotPreDrawListener? = null

        private val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                scrollDelta += dy
            }
        }

        fun install(): () -> Unit {
            val originalHint = root.scrollCaptureHint
            root.scrollCaptureHint = View.SCROLL_CAPTURE_HINT_INCLUDE or View.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS
            root.setScrollCaptureCallback(this)
            return {
                finish()
                root.setScrollCaptureCallback(null)
                root.scrollCaptureHint = originalHint
            }
        }

        override fun onScrollCaptureSearch(signal: CancellationSignal, onReady: Consumer<Rect>) {
            val bounds = Rect()
            if (!signal.isCanceled && root.isShown && header.height > 0 && list.height > 0 &&
                    list.layoutManager != null && !views.matrixProfileLoadingView.isShown && !views.waitingView.root.isShown) {
                root.getLocalVisibleRect(bounds)
            }
            onReady.accept(bounds)
        }

        override fun onScrollCaptureStart(session: ScrollCaptureSession, signal: CancellationSignal, onReady: Runnable) {
            if (signal.isCanceled) return
            finish()
            list.stopScroll()
            originalState = list.layoutManager?.onSaveInstanceState()
            originalScrollbars = list.isVerticalScrollBarEnabled
            originalOverscroll = list.overScrollMode
            list.isVerticalScrollBarEnabled = false
            list.overScrollMode = View.OVER_SCROLL_NEVER
            val bounds = Rect(0, 0, list.width, list.height)
            root.offsetDescendantRectToMyCoords(list, bounds)
            listOrigin = bounds.top - session.scrollBounds.top
            scrollDelta = 0
            headerBottom = null
            list.addOnScrollListener(scrollListener)
            active = true
            updateHeaderPosition()
            onReady.run()
        }

        override fun onScrollCaptureImageRequest(
                session: ScrollCaptureSession,
                signal: CancellationSignal,
                captureArea: Rect,
                onComplete: Consumer<Rect>,
        ) {
            pendingDraw?.removeListener()
            pendingDraw = null
            if (!active || signal.isCanceled || captureArea.isEmpty) {
                onComplete.accept(Rect())
                return
            }
            val request = Rect(captureArea)
            // scrollBy bypasses nested scrolling, leaving the AppBar at its original offset.
            list.scrollBy(0, request.top - (listOrigin + scrollDelta))
            pendingDraw = root.doOnPreDraw {
                pendingDraw = null
                if (active && !signal.isCanceled) {
                    updateHeaderPosition()
                    val available = availableArea(session, request)
                    if (!available.isEmpty) {
                        try {
                            render(session, available)
                        } catch (failure: RuntimeException) {
                            Timber.w(failure, "Unable to render profile scroll capture")
                            available.setEmpty()
                        }
                    }
                    onComplete.accept(available)
                }
            }
            root.invalidate()
        }

        private fun updateHeaderPosition() {
            if (!list.canScrollVertically(-1)) {
                headerBottom = listOrigin + scrollDelta
            }
        }

        private fun availableArea(session: ScrollCaptureSession, request: Rect): Rect {
            val listTop = listOrigin + scrollDelta
            val top = if (!list.canScrollVertically(-1)) (headerBottom ?: listTop) - header.height else listTop
            val bottom = listTop + if (list.canScrollVertically(1)) list.height else listContentBottom()
            return Rect(0, top, session.scrollBounds.width(), bottom).apply {
                if (!intersect(request)) setEmpty()
            }
        }

        private fun listContentBottom(): Int {
            val manager = list.layoutManager ?: return 0
            val bounds = Rect()
            var bottom = 0
            for (index in 0 until list.childCount) {
                manager.getDecoratedBoundsWithMargins(list.getChildAt(index), bounds)
                bottom = maxOf(bottom, bounds.bottom)
            }
            return (bottom + list.paddingBottom).coerceAtMost(list.height)
        }

        private fun render(session: ScrollCaptureSession, available: Rect) {
            val canvas = session.surface.lockHardwareCanvas()
            try {
                canvas.drawColor(backgroundColor)
                canvas.clipRect(0, 0, available.width(), available.height())
                canvas.translate(-available.left.toFloat(), -available.top.toFloat())
                headerBottom?.let { bottom ->
                    // Draw in local coordinates, bypassing the AppBar's parallax and collapsed visibility.
                    drawView(canvas, header, -session.scrollBounds.left, bottom - header.height)
                }
                drawView(canvas, list, list.left - session.scrollBounds.left, listOrigin + scrollDelta)
            } finally {
                session.surface.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawView(canvas: Canvas, view: View, left: Int, top: Int) {
            val save = canvas.save()
            try {
                canvas.translate(left.toFloat(), top.toFloat())
                canvas.clipRect(0, 0, view.width, view.height)
                view.draw(canvas)
            } finally {
                canvas.restoreToCount(save)
            }
        }

        override fun onScrollCaptureEnd(onReady: Runnable) {
            finish()
            if (root.isAttachedToWindow) {
                root.doOnPreDraw { onReady.run() }
                root.invalidate()
            } else {
                onReady.run()
            }
        }

        private fun finish() {
            pendingDraw?.removeListener()
            pendingDraw = null
            if (!active) return
            active = false
            list.removeOnScrollListener(scrollListener)
            list.isVerticalScrollBarEnabled = originalScrollbars
            list.overScrollMode = originalOverscroll
            originalState?.let { list.layoutManager?.onRestoreInstanceState(it) }
            originalState = null
        }
    }
}
