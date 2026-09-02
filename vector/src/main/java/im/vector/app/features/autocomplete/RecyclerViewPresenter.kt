/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.autocomplete

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.database.DataSetObserver
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.annotation.CallSuper
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.otaliastudios.autocomplete.AutocompletePresenter
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.features.themes.ThemeUtils

abstract class RecyclerViewPresenter<T : Any>(context: Context) : AutocompletePresenter<T>(context) {

    // The presenter is built with the application context, whose theme carries none of the vctr_*
    // attributes the row layouts reference — and pre-21 an unresolved ?attr in android:textColor throws.
    private val themedContext: Context = ContextThemeWrapper(context, ThemeUtils.getApplicationThemeRes(context))

    private var recyclerView: RecyclerView? = null
    private var clicks: ClickProvider<T>? = null
    private var observer: RecyclerView.AdapterDataObserver? = null

    // Read from the composer itself at display time rather than resolved from a theme attribute here:
    // this presenter's context is the application's, and the composer paints part of its background at
    // runtime, so the only reliable source of "the composer's color" is the composer view.
    var popupBackgroundColor: () -> Int = { Color.TRANSPARENT }
    var popupDividerColor: () -> Int = { Color.TRANSPARENT }

    /** Raised when the rows actually become visible, and again as they start animating away. */
    var onContentVisibilityChanged: (Boolean) -> Unit = {}

    override fun registerClickProvider(provider: ClickProvider<T>) {
        clicks = provider
    }

    override fun registerDataSetObserver(observer: DataSetObserver) {
        this.observer = Observer(observer)
    }

    @CallSuper
    override fun getView(): ViewGroup {
        val adapter = instantiateAdapter()
        observer?.also {
            adapter.registerAdapterDataObserver(it)
        }
        return instantiateRecyclerView().apply {
            this.adapter = adapter
            this.layoutManager = instantiateLayoutManager()
            this.itemAnimator = null
            recyclerView = this
        }
    }

    /**
     * Provides the recycler hosting the popup's items.
     * This should be a fresh instance every time this is called.
     */
    protected open fun instantiateRecyclerView(): RecyclerView = RecyclerView(themedContext)

    /**
     * A recycler showing at most [maxVisibleItems] rows, the rest scrolling, with a divider above the
     * first row and between the rest. It carries the composer's own background so it can slide out from
     * behind the composer over a transparent popup window. No divider below the last row: the composer
     * already draws one there, and two adjacent lines read as one thick one.
     */
    protected fun dividedRecyclerView(maxVisibleItems: Int): RecyclerView {
        val thickness = (context.resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)
        val background = popupBackgroundColor()
        val divider = popupDividerColor()
        return MaxVisibleItemsRecyclerView(themedContext, maxVisibleItems).apply {
            backgroundCompat = LayerDrawable(
                    arrayOf(
                            ColorDrawable(divider),
                            InsetDrawable(ColorDrawable(background), 0, thickness, 0, 0)
                    )
            )
            addItemDecoration(
                    MaterialDividerItemDecoration(themedContext, MaterialDividerItemDecoration.VERTICAL).apply {
                        isLastItemDecorated = false
                        dividerColor = divider
                        dividerThickness = thickness
                        dividerInsetStart = 0
                        dividerInsetEnd = 0
                    }
            )
        }
    }

    override fun hasContent(): Boolean = (recyclerView?.adapter?.itemCount ?: 0) > 0

    override fun onViewShown() {}

    /**
     * Slides the rows up from the bottom edge of their container, which sits directly on top of the
     * composer and clips them, so they appear to come out from behind it.
     */
    protected fun slideContentUpOnShow() {
        val view = recyclerView ?: return
        val minRowHeight = MIN_ANIMATABLE_HEIGHT_DP * context.resources.displayMetrics.density
        view.alpha = 0f
        var listener: View.OnLayoutChangeListener? = null
        listener = View.OnLayoutChangeListener { v, _, t, _, b, _, _, _, _ ->
            val height = b - t
            if (v.alpha == 0f && height >= minRowHeight) {
                v.removeOnLayoutChangeListener(listener)
                reveal(v, height)
            }
        }
        view.addOnLayoutChangeListener(listener)
    }

    private fun reveal(view: View, height: Int) {
        view.alpha = 1f
        onContentVisibilityChanged(true)
        view.translationY = height.toFloat()
        view.animate()
                .translationY(0f)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
    }

    /** Mirror of [slideContentUpOnShow], sliding back down behind the composer before the popup goes. */
    protected fun slideContentDownOnHide(onEnd: Runnable) {
        onContentVisibilityChanged(false)
        // Nothing visible to animate: dismiss straight away rather than waiting on an invisible view.
        val view = recyclerView?.takeIf { it.height > 0 && it.alpha > 0f } ?: return onEnd.run()
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                onEnd.run()
            }
        }
        view.animate()
                .translationY(view.height.toFloat())
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(AccelerateInterpolator())
                // Cancelling skips withEndAction, which would strand the dismissal and wedge the popup open.
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = finish()
                    override fun onAnimationCancel(animation: Animator) = finish()
                })
                .start()
    }

    @CallSuper
    override fun onViewHidden() {
        // Also covers dismissals that skip the exit animation, so the composer's separator always returns.
        onContentVisibilityChanged(false)
        observer?.also {
            recyclerView?.adapter?.unregisterAdapterDataObserver(it)
        }
        recyclerView = null
        observer = null
    }

    /**
     * Dispatch click event to Autocomplete.Callback.
     * Should be called when items are clicked.
     *
     * @param item the clicked item.
     */
    protected fun dispatchClick(item: T) {
        if (clicks != null) clicks?.click(item)
    }

    /**
     * Request that the popup should recompute its dimensions based on a recent change in
     * the view being displayed.
     *
     * This is already managed internally for [RecyclerView] events.
     * Only use it for changes in other views that you have added to the popup,
     * and only if one of the dimensions for the popup is WRAP_CONTENT .
     */
    protected fun dispatchLayoutChange() {
        if (observer != null) observer!!.onChanged()
    }

    /**
     * Provide an adapter for the recycler.
     * This should be a fresh instance every time this is called.
     *
     * @return a new adapter.
     */
    protected abstract fun instantiateAdapter(): RecyclerView.Adapter<*>

    /**
     * Provides a layout manager for the recycler.
     * This should be a fresh instance every time this is called.
     * Defaults to a vertical LinearLayoutManager, which is guaranteed to work well.
     *
     * @return a new layout manager.
     */
    protected fun instantiateLayoutManager(): RecyclerView.LayoutManager {
        return LinearLayoutManager(themedContext, LinearLayoutManager.VERTICAL, false)
    }

    private class Observer constructor(private val root: DataSetObserver) : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            root.onChanged()
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            root.onChanged()
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            root.onChanged()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            root.onChanged()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            root.onChanged()
        }
    }

    companion object {
        private const val SLIDE_DURATION_MS = 130L

        // Above the empty-list sliver (just the top divider), below any real row.
        private const val MIN_ANIMATABLE_HEIGHT_DP = 8
    }
}
