/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import im.vector.app.databinding.ViewEmojiPickerBinding
import im.vector.app.features.themes.ThemeUtils
import kotlinx.coroutines.MainScope

/**
 * The shared emoji + custom-emote picker surface: a [EmojiPickerTabStrip] (Frequently used, emote packs,
 * emoji categories) over the picker grid, with the tab/scroll sync wired up. Used by both the reaction
 * picker and the inline composer keyboard; the latter also shows a trailing backspace key.
 */
class EmojiPickerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    fun interface OnEmojiClickListener {
        fun onEmojiClicked(item: EmojiPickerItem)
    }

    var onEmojiClick: OnEmojiClickListener? = null

    private val views: ViewEmojiPickerBinding
    private val adapter = EmojiRecyclerAdapter()
    private val viewScope = MainScope()
    private var sections: List<EmojiPickerSection> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(ThemeUtils.getColor(context, android.R.attr.colorBackground))
        isClickable = true
        // The grid renders via a shared static paint; configure it (system emoji font) unless something
        // already did with a richer typeface (the reaction picker passes the emoji-compat one).
        if (!EmojiDrawView.configured) EmojiDrawView.configureTextPaint(context, null)
        views = ViewEmojiPickerBinding.inflate(LayoutInflater.from(context), this)

        views.emojiPickerRecycler.adapter = adapter
        views.emojiPickerRecycler.pauseImageAnimationsWhileScrolling()
        adapter.reactionClickListener = object : ReactionClickListener {
            override fun onReactionSelected(reaction: String) = onItemSelected(reaction)
        }
        adapter.interactionListener = object : EmojiRecyclerAdapter.InteractionListener {
            override fun getCoroutineScope() = viewScope
            override fun firstVisibleSectionChange(section: Int) {
                views.emojiPickerTabs.setSelectedTab(section)
            }
        }
        views.emojiPickerTabs.onTabClicked = { position ->
            adapter.scrollToSection(position)
            views.emojiPickerTabs.setSelectedTab(position)
        }
    }

    fun setSections(sections: List<EmojiPickerSection>) {
        this.sections = sections
        adapter.update(sections)
        views.emojiPickerTabs.setTabs(sections)
        views.emojiPickerTabs.setSelectedTab(0)
    }

    /** Keep the grid's last row above the gesture home bar / nav bar (the panel bg fills behind it). */
    fun setBottomInset(px: Int) {
        views.emojiPickerRecycler.setPadding(0, 0, 0, px)
    }

    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Show a trailing action button next to the tabs (the keyboard's backspace). Each press fires
     * immediately on ACTION_DOWN — no click-detection delay — so rapid tapping deletes as fast as you tap;
     * holding then repeats like a hardware key. Non-focusable so it never steals focus from the composer.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setTrailingAction(@DrawableRes iconRes: Int, @StringRes contentDescriptionRes: Int, onClick: () -> Unit) {
        val button = views.emojiPickerTrailing
        views.emojiPickerTrailingDivider.isVisible = true
        button.isVisible = true
        button.setImageResource(iconRes)
        button.contentDescription = context.getString(contentDescriptionRes)
        button.isFocusable = false
        button.isFocusableInTouchMode = false
        // Accelerating auto-repeat while held, like a hardware key: tapping deletes one per tap, holding
        // ramps up to a fast delete so you don't have to spam-tap to clear a lot of text.
        val repeat = object : Runnable {
            var interval = REPEAT_FIRST_MS
            override fun run() {
                onClick()
                interval = (interval * REPEAT_ACCEL).toLong().coerceAtLeast(REPEAT_MIN_MS)
                repeatHandler.postDelayed(this, interval)
            }
        }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    // Keep the scrolling tab strip from stealing fast, slightly-imprecise taps mid-gesture.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    onClick()
                    repeatHandler.removeCallbacks(repeat)
                    repeat.interval = REPEAT_FIRST_MS
                    repeatHandler.postDelayed(repeat, REPEAT_INITIAL_DELAY_MS)
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeatHandler.removeCallbacks(repeat)
                    true
                }
                else -> false
            }
        }
    }

    private fun onItemSelected(reaction: String) {
        val item = sections.flatMap { it.items }.firstOrNull { itemKey(it) == reaction }
                ?: EmojiPickerItem.Unicode(reaction)
        onEmojiClick?.onEmojiClicked(item)
    }

    private fun itemKey(item: EmojiPickerItem) = when (item) {
        is EmojiPickerItem.Unicode -> item.glyph
        is EmojiPickerItem.Emote -> item.key
    }

    companion object {
        private const val REPEAT_INITIAL_DELAY_MS = 250L
        private const val REPEAT_FIRST_MS = 110L
        private const val REPEAT_MIN_MS = 28L
        private const val REPEAT_ACCEL = 0.82
    }
}
