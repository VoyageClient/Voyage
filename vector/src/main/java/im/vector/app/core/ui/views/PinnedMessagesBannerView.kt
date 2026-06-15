/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.databinding.ViewPinnedMessagesBannerBinding
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

class PinnedMessagesBannerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Callback {
        fun onPinnedMessageClicked(eventId: String)
        fun onViewAllPinnedMessagesClicked()
    }

    private val views: ViewPinnedMessagesBannerBinding
    var callback: Callback? = null

    private val dimensionConverter = DimensionConverter(resources)
    private var eventIds: List<String> = emptyList()
    private var previewProvider: (String) -> CharSequence = { "" }
    private var currentIndex: Int = 0

    init {
        orientation = VERTICAL
        inflate(context, R.layout.view_pinned_messages_banner, this)
        views = ViewPinnedMessagesBannerBinding.bind(this)
        views.pinnedMessagesBannerMain.setOnClickListener { onBannerClicked() }
        views.pinnedMessagesViewAll.setOnClickListener { callback?.onViewAllPinnedMessagesClicked() }
    }

    /**
     * @param eventIds pinned event ids, in server (oldest first) order
     * @param previewProvider one-line preview text for a given event id
     */
    fun render(eventIds: List<String>, previewProvider: (String) -> CharSequence) {
        val pinsChanged = eventIds != this.eventIds
        this.eventIds = eventIds
        this.previewProvider = previewProvider
        if (eventIds.isEmpty()) {
            isVisible = false
            return
        }
        isVisible = true
        // Anchor to the most recent pinned message (like element-web) when the pin set is first
        // populated or changes; otherwise preserve the tap/scroll-driven selection.
        if (pinsChanged || currentIndex !in eventIds.indices) {
            currentIndex = eventIds.lastIndex
        }
        updateUi()
    }

    /**
     * Select which pinned message the banner shows, e.g. driven by the timeline scroll position.
     */
    fun setSelectedEventId(eventId: String?) {
        val index = eventIds.indexOf(eventId)
        if (index >= 0 && index != currentIndex) {
            currentIndex = index
            updateUi()
        }
    }

    /**
     * Set the banner as if the user had just jumped to [eventId]: suggest the next (older) pin,
     * matching tap-to-cycle. Used when opening the room directly at a pinned event.
     */
    fun advancePast(eventId: String?) {
        val index = eventIds.indexOf(eventId)
        if (index < 0) return
        currentIndex = if (index - 1 < 0) eventIds.lastIndex else index - 1
        updateUi()
    }

    private fun updateUi() {
        val eventId = eventIds.getOrNull(currentIndex) ?: return
        views.pinnedMessagesPreview.text = previewProvider(eventId)
        views.pinnedMessagesLabel.text = if (eventIds.size > 1) {
            resources.getString(CommonStrings.pinned_messages_title) + " · ${currentIndex + 1}/${eventIds.size}"
        } else {
            resources.getString(CommonStrings.pin_action)
        }
        views.pinnedMessagesViewAll.isVisible = eventIds.size > 1
        renderIndicators()
    }

    private fun onBannerClicked() {
        val eventId = eventIds.getOrNull(currentIndex) ?: return
        callback?.onPinnedMessageClicked(eventId)
        // Advance to the previous (older) pinned message so repeated taps cycle through them.
        currentIndex = if (currentIndex - 1 < 0) eventIds.lastIndex else currentIndex - 1
        updateUi()
    }

    private fun renderIndicators() {
        val container = views.pinnedMessagesIndicator
        container.removeAllViews()
        val barCount = minOf(eventIds.size, MAX_INDICATORS)
        if (barCount <= 1) return
        val activeBar = currentIndex.coerceIn(0, barCount - 1)
        val barWidth = dimensionConverter.dpToPx(2)
        val barHeight = dimensionConverter.dpToPx(16)
        val barMargin = dimensionConverter.dpToPx(1)
        for (i in 0 until barCount) {
            val bar = View(context)
            val params = LayoutParams(barWidth, barHeight).apply {
                marginStart = barMargin
                marginEnd = barMargin
                gravity = Gravity.CENTER_VERTICAL
            }
            bar.layoutParams = params
            val colorAttr = if (i == activeBar) {
                com.google.android.material.R.attr.colorPrimary
            } else {
                im.vector.lib.ui.styles.R.attr.vctr_content_quaternary
            }
            bar.setBackgroundColor(ThemeUtils.getColor(context, colorAttr))
            container.addView(bar)
        }
    }

    companion object {
        private const val MAX_INDICATORS = 3
    }
}
