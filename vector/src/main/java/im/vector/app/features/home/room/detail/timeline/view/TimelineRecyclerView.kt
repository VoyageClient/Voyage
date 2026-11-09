/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.view

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.CancellationSignal
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScrollCaptureCallback
import android.view.ScrollCaptureSession
import android.view.ScrollCaptureTarget
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView
import timber.log.Timber
import java.util.function.Consumer

class TimelineRecyclerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    /**
     * While set, rows never receive touches (no press ripple, clicks or long-presses) and the list
     * scrolls itself. Item touch listeners still see every event: RecyclerView offers each MOVE and UP
     * to them from onTouchEvent until one intercepts.
     */
    var blocksChildTouches = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        val intercepted = super.onInterceptTouchEvent(e)
        return intercepted || blocksChildTouches
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onScrollCaptureSearch(localVisibleRect: Rect, windowOffset: Point, targets: Consumer<ScrollCaptureTarget>) {
        super.onScrollCaptureSearch(localVisibleRect, windowOffset, SafeScrollCapture.wrapTargets(this, targets))
    }
}

// The platform's long-screenshot helper crashes if the scroll it starts applies a queued adapter update
// that removes the child it anchored on.
@RequiresApi(Build.VERSION_CODES.S)
internal object SafeScrollCapture {

    fun wrapTargets(list: RecyclerView, targets: Consumer<ScrollCaptureTarget>) = Consumer<ScrollCaptureTarget> { target ->
        targets.accept(
                if (target.containingView === list) {
                    ScrollCaptureTarget(list, target.localVisibleRect, target.positionInWindow, Callback(list, target.callback))
                } else {
                    target
                }
        )
    }

    class Callback(
            private val list: RecyclerView,
            private val delegate: ScrollCaptureCallback,
    ) : ScrollCaptureCallback by delegate {

        override fun onScrollCaptureImageRequest(
                session: ScrollCaptureSession,
                signal: CancellationSignal,
                captureArea: Rect,
                onComplete: Consumer<Rect>,
        ) {
            // A zero scroll still flushes pending adapter updates.
            if (list.hasPendingAdapterUpdates()) list.scrollBy(0, 0)
            try {
                delegate.onScrollCaptureImageRequest(session, signal, captureArea, onComplete)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Scroll capture lost its anchor, ending capture")
                // An empty area tells the platform there is no more content in this direction.
                onComplete.accept(Rect())
            }
        }
    }
}
