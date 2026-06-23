/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.content.res.Resources
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.scopes.ActivityScoped
import im.vector.app.features.settings.VectorPreferences
import javax.inject.Inject
import kotlin.math.roundToInt

@ActivityScoped
class TimelineMediaSizeProvider @Inject constructor(
        private val resources: Resources,
        private val vectorPreferences: VectorPreferences
) {

    var recyclerView: RecyclerView? = null
        set(value) {
            field?.removeOnLayoutChangeListener(layoutListener)
            field = value
            cachedSize = null
            value?.addOnLayoutChangeListener(layoutListener)
        }

    private var cachedSize: Pair<Int, Int>? = null

    // Drop the cache when the RecyclerView is resized (e.g. first layout pass, rotation) so a
    // pre-layout 0-size measurement is never kept.
    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            cachedSize = null
        }
    }

    fun getMaxSize(): Pair<Int, Int> {
        cachedSize?.let { return it }
        val computed = computeMaxSize()
        // Only cache once the RecyclerView has actually been laid out. Caching a pre-layout 0-size
        // would stick permanently and collapse every sized image to 0 height (integer division in
        // ImageContentRenderer.processSize), while unsized images still render.
        if ((recyclerView?.width ?: 0) > 0 && (recyclerView?.height ?: 0) > 0) {
            cachedSize = computed
        }
        return computed
    }

    private fun computeMaxSize(): Pair<Int, Int> {
        // Fall back to the display size before the RecyclerView is measured, so images built during
        // the initial pass get a sensible max instead of 0.
        val width = (recyclerView?.width ?: 0).takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = (recyclerView?.height ?: 0).takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val maxImageWidth: Int
        val maxImageHeight: Int
        // landscape / portrait
        if (width < height) {
            maxImageWidth = (width * 0.7f).roundToInt()
            maxImageHeight = (height * 0.5f).roundToInt()
        } else {
            maxImageWidth = (width * 0.7f).roundToInt()
            maxImageHeight = (height * 0.7f).roundToInt()
        }
        return if (vectorPreferences.useMessageBubblesLayout()) {
            val bubbleMaxImageWidth = maxImageWidth.coerceAtMost(resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.chat_bubble_fixed_size))
            Pair(bubbleMaxImageWidth, maxImageHeight)
        } else {
            Pair(maxImageWidth, maxImageHeight)
        }
    }
}
