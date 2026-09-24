/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.animations

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.os.Build
import android.util.Property
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.R
import im.vector.app.features.settings.VectorPreferences
import java.util.WeakHashMap
import kotlin.math.max

class ScrollAnimationManager(private val vectorPreferences: VectorPreferences) {

    private val listeners = mutableMapOf<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private val pendingEntries = WeakHashMap<RecyclerView, MutableList<View>>()

    fun install(activity: Activity) {
        if (listeners.containsKey(activity)) return

        val root = activity.window.decorView
        val listener = ViewTreeObserver.OnGlobalLayoutListener { installRecyclerViews(root) }
        listeners[activity] = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        root.post { installRecyclerViews(root) }
    }

    fun uninstall(activity: Activity) {
        val listener = listeners.remove(activity) ?: return
        val observer = activity.window.decorView.viewTreeObserver
        if (!observer.isAlive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            observer.removeOnGlobalLayoutListener(listener)
        } else {
            @Suppress("DEPRECATION")
            observer.removeGlobalOnLayoutListener(listener)
        }
    }

    private fun installRecyclerViews(view: View) {
        if (view is RecyclerView && view.getTag(R.id.scroll_animation_installed) != true) {
            view.setTag(R.id.scroll_animation_installed, true)
            view.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                // Children attached while idle (initial fill, relayout, arriving message) are not entering:
                // animating them would burst on the next scroll, long after they appeared.
                override fun onChildViewAttachedToWindow(child: View) {
                    if (view.scrollState != RecyclerView.SCROLL_STATE_IDLE) pendingOf(view).add(child)
                }

                override fun onChildViewDetachedFromWindow(child: View) {
                    cancel(child)
                    pendingOf(view).remove(child)
                }
            })
            view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dx != 0 || dy != 0) animateEnteringChildren(recyclerView)
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) pendingOf(recyclerView).clear()
                }
            })
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                installRecyclerViews(view.getChildAt(index))
            }
        }
    }

    private fun animateEnteringChildren(recyclerView: RecyclerView) {
        val pending = pendingOf(recyclerView)
        if (pending.isEmpty()) return
        val entering = pending.filter { it.parent === recyclerView }.sortedBy { it.top }
        pending.clear()
        entering.forEachIndexed { index, child -> animate(child, index * STAGGER_MS) }
    }

    private fun pendingOf(recyclerView: RecyclerView): MutableList<View> = pendingEntries.getOrPut(recyclerView) { mutableListOf() }

    // Not view.animate(): DefaultItemAnimator cancels that on item changes, freezing the entry mid-way.
    private fun animate(view: View, delay: Long) {
        val style = vectorPreferences.scrollAnimationStyle()
        if (style == NONE) return
        cancel(view)

        val verticalDistance = max(view.height, (view.resources.displayMetrics.density * 48).toInt()).toFloat()
        val horizontalDistance = max(view.width, (view.resources.displayMetrics.density * 48).toInt()).toFloat()
        val animator = when (style) {
            FADE -> enter(view, View.ALPHA to 0f)
            FLIP -> enter(view, View.ROTATION_X to 25f, View.ALPHA to 0.7f)
            BOUNCE -> enter(view, View.TRANSLATION_Y to verticalDistance).apply { interpolator = OvershootInterpolator() }
            GLITCH -> enter(view, View.ALPHA to 0.35f, View.TRANSLATION_X to horizontalDistance * 0.12f)
            HELIX -> enter(view, View.ROTATION_Y to 25f, View.TRANSLATION_Y to verticalDistance * 0.15f)
            ROTATE -> enter(view, View.ROTATION to 180f)
            ZOOM -> enter(view, View.SCALE_X to 0.6f, View.SCALE_Y to 0.6f, View.ALPHA to 0f)
            DROP -> enter(view, View.TRANSLATION_Y to -verticalDistance)
            STRETCH -> enter(view, View.SCALE_Y to 0.5f)
            SLIDE -> enter(view, View.TRANSLATION_X to horizontalDistance)
            SHAKE -> ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -horizontalDistance * 0.08f, horizontalDistance * 0.08f, -horizontalDistance * 0.04f, 0f)
            SWING -> enter(view, View.ROTATION to -20f).apply { interpolator = OvershootInterpolator() }
            JIGGLE -> ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -5f, 5f, -3f, 3f, 0f)
            else -> return
        }
        animator.duration = DURATION_MS
        animator.startDelay = delay
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (view.getTag(R.id.scroll_animation_running) === animation) view.setTag(R.id.scroll_animation_running, null)
            }
        })
        view.setTag(R.id.scroll_animation_running, animator)
        animator.start()
    }

    private fun enter(view: View, vararg from: Pair<Property<View, Float>, Float>): ObjectAnimator {
        val holders = from.map { (property, start) ->
            property.set(view, start)
            val rest = if (property == View.ALPHA || property == View.SCALE_X || property == View.SCALE_Y) 1f else 0f
            PropertyValuesHolder.ofFloat(property, start, rest)
        }
        return ObjectAnimator.ofPropertyValuesHolder(view, *holders.toTypedArray())
    }

    private fun cancel(view: View) {
        (view.getTag(R.id.scroll_animation_running) as? Animator)?.cancel()
        view.setTag(R.id.scroll_animation_running, null)
        reset(view)
    }

    private fun reset(view: View) {
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
        view.rotationX = 0f
        view.rotationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    companion object {
        private const val DURATION_MS = 250L
        private const val STAGGER_MS = 20L
        private const val NONE = "none"
        private const val FADE = "fade"
        private const val FLIP = "flip"
        private const val BOUNCE = "bounce"
        private const val GLITCH = "glitch"
        private const val HELIX = "helix"
        private const val ROTATE = "rotate"
        private const val ZOOM = "zoom"
        private const val DROP = "drop"
        private const val STRETCH = "stretch"
        private const val SLIDE = "slide"
        private const val SHAKE = "shake"
        private const val SWING = "swing"
        private const val JIGGLE = "jiggle"
    }
}
