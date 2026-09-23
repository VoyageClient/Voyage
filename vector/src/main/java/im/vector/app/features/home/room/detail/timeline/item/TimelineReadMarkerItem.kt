/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.airbnb.epoxy.VisibilityState
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.features.home.room.detail.timeline.helper.ReadMarkerFadeTracker
import org.matrix.android.sdk.api.extensions.orFalse

@EpoxyModelClass
abstract class TimelineReadMarkerItem : VectorEpoxyModel<TimelineReadMarkerItem.Holder>(R.layout.item_timeline_read_marker) {

    @EpoxyAttribute var fadeKey: String? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var fadeTracker: ReadMarkerFadeTracker? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        cancelAnimation(holder)
        val faded = fadeTracker?.isFadedOut.orFalse()
        applyFaded(holder, faded)
        if (!faded) {
            scheduleAnimation(holder)
        }
    }

    override fun unbind(holder: Holder) {
        cancelAnimation(holder)
        applyFaded(holder, fadeTracker?.isFadedOut.orFalse())
        super.unbind(holder)
    }

    override fun onVisibilityStateChanged(visibilityState: Int, view: Holder) {
        super.onVisibilityStateChanged(visibilityState, view)
        if (visibilityState != VisibilityState.VISIBLE) return
        val tracker = fadeTracker ?: return
        if (tracker.isFadedOut) return
        tracker.onSeen(SystemClock.elapsedRealtime())
        scheduleAnimation(view)
    }

    private fun scheduleAnimation(holder: Holder) {
        if (holder.animator != null) return
        val tracker = fadeTracker ?: return
        val remaining = tracker.remainingMs(SystemClock.elapsedRealtime()) ?: return
        if (remaining <= 0L) {
            tracker.markFadedOut()
            applyFaded(holder, true)
            return
        }
        // Resume at the deadline's position, so a rebind mid-animation doesn't restart it.
        val from = (TOTAL_DURATION_MS - remaining).toFloat()
        holder.animator = ValueAnimator.ofFloat(from, TOTAL_DURATION_MS.toFloat()).apply {
            duration = remaining
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val elapsed = animator.animatedValue as Float
                applyBurn(holder, 1f - (elapsed / FUSE_DURATION_MS).coerceIn(0f, 1f))
                applyFade(holder, 1f - ((elapsed - FADE_START_MS) / FADE_DURATION_MS).coerceIn(0f, 1f))
                applyCollapse(holder, 1f - ((elapsed - FUSE_DURATION_MS) / COLLAPSE_DURATION_MS).coerceIn(0f, 1f))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    holder.animator = null
                    tracker.markFadedOut()
                    applyFaded(holder, true)
                }
            })
            start()
        }
    }

    /** The lines on either side of the label burn away from the outer edges toward it. */
    private fun applyBurn(holder: Holder, remainingFraction: Float) {
        holder.lineStart.pivotX = holder.lineStart.width.toFloat()
        holder.lineStart.scaleX = remainingFraction
        holder.lineEnd.pivotX = 0f
        holder.lineEnd.scaleX = remainingFraction
    }

    private fun applyFade(holder: Holder, remainingFraction: Float) {
        holder.view.alpha = remainingFraction
    }

    private fun applyCollapse(holder: Holder, remainingFraction: Float) {
        if (holder.fullHeight == 0 && remainingFraction == 1f) {
            holder.fullHeight = holder.view.height
        }
        if (holder.fullHeight > 0) {
            setViewHeight(holder, (holder.fullHeight * remainingFraction).toInt())
        }
    }

    private fun cancelAnimation(holder: Holder) {
        holder.animator?.removeAllListeners()
        holder.animator?.cancel()
        holder.animator = null
    }

    private fun applyFaded(holder: Holder, faded: Boolean) {
        applyFade(holder, if (faded) 0f else 1f)
        applyBurn(holder, if (faded) 0f else 1f)
        setViewHeight(holder, if (faded) 0 else ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun setViewHeight(holder: Holder, height: Int) {
        val params = holder.view.layoutParams ?: return
        if (params.height != height) {
            params.height = height
            holder.view.layoutParams = params
        }
    }

    companion object {
        private const val FUSE_DURATION_MS = 5_000f
        private const val FADE_DURATION_MS = 250f

        // The fade lands exactly as the fuse burns out; the row only collapses once it is gone.
        private const val FADE_START_MS = FUSE_DURATION_MS - FADE_DURATION_MS
        private const val COLLAPSE_DURATION_MS = 200f
        const val TOTAL_DURATION_MS = (FUSE_DURATION_MS + COLLAPSE_DURATION_MS).toLong()
    }

    class Holder : VectorEpoxyHolder() {
        var animator: Animator? = null
        var fullHeight: Int = 0
        val lineStart by bind<View>(R.id.readMarkerLineStart)
        val lineEnd by bind<View>(R.id.readMarkerLineEnd)
    }
}
