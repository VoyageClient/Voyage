/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.content.Context
import android.content.res.Resources
import android.graphics.Point
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import im.vector.app.features.location.STATIC_MAP_PIN_HEIGHT_DP
import im.vector.app.features.settings.VectorPreferences
import javax.inject.Inject
import kotlin.math.roundToInt

@ActivityScoped
class TimelineMediaSizeProvider @Inject constructor(
        @ApplicationContext private val context: Context,
        private val resources: Resources,
        private val vectorPreferences: VectorPreferences
) {

    var recyclerView: RecyclerView? = null
        set(value) {
            field?.removeOnLayoutChangeListener(layoutListener)
            field = value
            viewportWidth = 0
            cachedSize = null
            value?.addOnLayoutChangeListener(layoutListener)
            // addOnLayoutChangeListener only reports future passes; if the view is re-attached already
            // laid out, seed from its current bounds so we don't fall back to display metrics forever.
            value?.takeIf { it.width > 0 }?.let { adoptWidth(it.width) }
        }

    // Written only on the main thread (layout listener / setter); read on the background model-build
    // thread via cachedSize, hence @Volatile.
    private var viewportWidth = 0

    @Volatile
    private var cachedSize: Pair<Int, Int>? = null

    /** Called on the main thread when the cap changes after it was first established. */
    var onMaxSizeChanged: (() -> Unit)? = null

    private val layoutListener = View.OnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
        adoptWidth(right - left)
    }

    private fun adoptWidth(width: Int) {
        if (width <= 0 || width == viewportWidth) return
        viewportWidth = width
        val previous = cachedSize
        val size = computeMaxSize(width)
        cachedSize = size
        // Built models carry the cap they were made with. Rotating leaves it alone, so this is a real
        // window resize (split screen).
        if (previous != null && previous != size) onMaxSizeChanged?.invoke()
    }

    /** A location message's square map: [LOCATION_MAP_SIZE_DP], or the media width cap when that is narrower. */
    fun getLocationMapSize(): Int =
            minOf(getMaxSize().first, (LOCATION_MAP_SIZE_DP * resources.displayMetrics.density).roundToInt())

    /** How far below a static map's centre its location lands, under the tip of the pin centred over it. */
    fun getLocationPinTipOffset(): Int = (STATIC_MAP_PIN_HEIGHT_DP * resources.displayMetrics.density / 2).roundToInt()

    fun getMaxSize(): Pair<Int, Int> {
        cachedSize?.let { return it }
        // Not laid out yet: fall back to the display size so media built during the initial pass gets
        // a sensible cap instead of 0. Don't cache it — the layout listener installs the real size.
        return computeMaxSize(resources.displayMetrics.widthPixels)
    }

    /**
     * Taken from the display rather than the list, as Telegram does: the list's height follows the
     * keyboard and changes on every pass of a rotation, and each change of cap resizes and reloads all
     * media. The width comes from the short side and the height from the long one, so rotating keeps it.
     */
    private fun computeMaxSize(viewportWidth: Int): Pair<Int, Int> {
        val shortSide = (resources.configuration.smallestScreenWidthDp * resources.displayMetrics.density).roundToInt()
        val width = if (shortSide > 0) minOf(viewportWidth, shortSide) else viewportWidth
        val maxImageWidth = (width * 0.7f).roundToInt()
        // Roughly half the portrait timeline.
        val maxImageHeight = (displayLongSide() * 0.4f).roundToInt()
        return if (vectorPreferences.useMessageBubblesLayout()) {
            Pair(maxImageWidth.coerceAtMost(bubbleContentMaxWidth(resources, width)), maxImageHeight)
        } else {
            Pair(maxImageWidth, maxImageHeight)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayLongSide(): Int {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val size = Point()
        // Below 17 only the size minus the system bars is available, which can differ by orientation on
        // tablets; a rotation there then rebuilds once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) display.getRealSize(size) else display.getSize(size)
        return maxOf(size.x, size.y)
    }
}

/**
 * The widest a bubble can show its content on this screen: everything between the screen edges and
 * the bubble's content — the avatar column, the send-state column, the bubble's own margins and its
 * inner padding — taken off the screen width. Content wider than this is clipped by the bubble.
 */
fun bubbleContentMaxWidth(resources: Resources, availableWidth: Int = resources.displayMetrics.widthPixels): Int {
    val density = resources.displayMetrics.density
    val chrome = ((AVATAR_COLUMN_DP + SEND_STATE_MARGINS_DP) * density).roundToInt() +
            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.item_event_message_state_size) +
            2 * resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_wrap_margin_horizontal) +
            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.dual_bubble_one_side_without_avatar_margin) +
            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_inner_padding_long_side) +
            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_inner_padding_short_side)
    return (availableWidth - chrome)
            .coerceAtMost(resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.chat_bubble_fixed_size))
}

/** Avatar plus its start margin, as laid out in item_timeline_event_base. */
private const val AVATAR_COLUMN_DP = 44f + 8f
private const val SEND_STATE_MARGINS_DP = 8f + 8f

private const val LOCATION_MAP_SIZE_DP = 200
