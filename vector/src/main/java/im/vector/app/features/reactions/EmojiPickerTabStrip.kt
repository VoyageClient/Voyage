/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import im.vector.app.R
import im.vector.app.core.glide.GlideApp
import im.vector.app.features.themes.ThemeUtils

/**
 * Compact, horizontally scrollable tab strip for the emoji/emote picker. Unlike Material's TabLayout it
 * keeps tabs narrow (so more fit), renders glyph / drawable / async-image tabs reliably, and never
 * centres the selected tab — it only scrolls a tab into view when it is partly clipped at an edge.
 */
class EmojiPickerTabStrip @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val container = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val tabViews = mutableListOf<View>()
    private var selectedIndex = -1

    var onTabClicked: ((Int) -> Unit)? = null

    // Overrides the indicator's `?attr/colorAccent` — needed inside a bottom sheet, whose dialog theme
    // resolves colorAccent to the default (green) rather than the host activity's chosen accent.
    private var indicatorColor: Int? = null

    fun setIndicatorColor(@androidx.annotation.ColorInt color: Int) {
        indicatorColor = color
        tabViews.forEach { it.findViewById<View>(R.id.emojiTabIndicator).setBackgroundColor(color) }
    }

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun setTabs(sections: List<EmojiPickerSection>) {
        container.removeAllViews()
        tabViews.clear()
        selectedIndex = -1
        sections.forEachIndexed { index, section ->
            val tab = LayoutInflater.from(context).inflate(R.layout.item_emoji_picker_tab, container, false)
            val icon = tab.findViewById<ImageView>(R.id.emojiTabIcon)
            val glyph = tab.findViewById<TextView>(R.id.emojiTabGlyph)
            when {
                section.tabIconRes != null -> {
                    glyph.isVisible = false
                    icon.isVisible = true
                    icon.setImageResource(section.tabIconRes)
                    ImageViewCompat.setImageTintList(
                            icon,
                            ColorStateList.valueOf(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
                    )
                }
                section.tabImageUrl != null -> {
                    glyph.isVisible = false
                    icon.isVisible = true
                    ImageViewCompat.setImageTintList(icon, null)
                    GlideApp.with(icon).load(section.tabImageUrl).into(icon)
                }
                else -> {
                    val sprite = section.tabGlyph?.let { EmojiDrawView.twemojiResolver?.invoke(it) }
                    if (sprite != null) {
                        glyph.isVisible = false
                        icon.isVisible = true
                        ImageViewCompat.setImageTintList(icon, null)
                        icon.setImageBitmap(sprite)
                    } else {
                        icon.isVisible = false
                        glyph.isVisible = true
                        glyph.text = section.tabGlyph
                    }
                }
            }
            tab.contentDescription = section.name
            tab.setOnClickListener { onTabClicked?.invoke(index) }
            indicatorColor?.let { tab.findViewById<View>(R.id.emojiTabIndicator).setBackgroundColor(it) }
            container.addView(tab)
            tabViews += tab
        }
    }

    fun setSelectedTab(index: Int) {
        if (index < 0 || index >= tabViews.size) return
        if (index != selectedIndex) {
            tabViews.getOrNull(selectedIndex)?.findViewById<View>(R.id.emojiTabIndicator)?.isVisible = false
            selectedIndex = index
            tabViews[index].findViewById<View>(R.id.emojiTabIndicator).isVisible = true
        }
        ensureVisible(index)
    }

    private fun ensureVisible(index: Int) {
        val tab = tabViews.getOrNull(index) ?: return
        post {
            val left = tab.left
            val right = tab.right
            when {
                left < scrollX -> smoothScrollTo(left, 0)
                right > scrollX + width -> smoothScrollTo(right - width, 0)
            }
        }
    }
}
