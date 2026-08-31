/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import im.vector.app.databinding.ViewEmojiPickerBinding
import im.vector.app.features.themes.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shared emoji + custom-emote picker surface: a [PickerTabRow] (Frequently used, emote packs,
 * emoji categories) over the picker grid, with the tab/scroll sync wired up. Used by both the reaction
 * picker, which adds search and freeform reactions, and the inline composer keyboard, which instead
 * shows a trailing backspace key.
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

    /** Narrows the sections to those matching a query, keeping section (category) grouping. */
    var sectionFilter: (suspend (List<EmojiPickerSection>, String) -> List<EmojiPickerSection>)? = null

    /** Sends a typed reaction (the reaction picker only); enables the freeform key when non-null. */
    var onFreeformSubmit: ((String) -> Unit)? = null
        set(value) {
            field = value
            views.emojiPickerTabRow.onFreeformSubmit = value
            views.emojiPickerTabRow.setFreeformEnabled(value != null)
        }

    private val views: ViewEmojiPickerBinding
    private val adapter = EmojiRecyclerAdapter()
    private val viewScope = MainScope()
    private var sections: List<EmojiPickerSection> = emptyList()
    private var searchJob: Job? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(ThemeUtils.getColor(context, android.R.attr.colorBackground))
        isClickable = true
        // The grid renders via a shared static paint; configure it (system emoji font) unless something
        // already did with a richer typeface (the reaction picker passes the emoji-compat one).
        if (!EmojiDrawView.configured) EmojiDrawView.configureTextPaint(context, null)
        views = ViewEmojiPickerBinding.inflate(LayoutInflater.from(context), this)

        views.emojiPickerRecycler.adapter = adapter
        views.emojiPickerRecycler.setHasFixedSize(true)
        views.emojiPickerRecycler.setItemViewCacheSize(GRID_VIEW_CACHE)
        views.emojiPickerRecycler.pauseImageAnimationsWhileScrolling()
        adapter.reactionClickListener = object : ReactionClickListener {
            override fun onReactionSelected(reaction: String) = onItemSelected(reaction)
        }
        adapter.interactionListener = object : EmojiRecyclerAdapter.InteractionListener {
            override fun getCoroutineScope() = viewScope
            override fun firstVisibleSectionChange(section: Int) {
                if (!views.emojiPickerTabRow.isSearching) views.emojiPickerTabRow.tabs.setSelectedTab(section)
            }
        }
        views.emojiPickerTabRow.tabs.onTabClicked = { position ->
            adapter.scrollToSection(position)
            views.emojiPickerTabRow.tabs.setSelectedTab(position)
        }
        views.emojiPickerTabRow.onQueryChanged = { query -> onQuery(query) }
    }

    fun setSections(sections: List<EmojiPickerSection>) {
        this.sections = sections
        views.emojiPickerTabRow.tabs.setTabs(sections)
        views.emojiPickerTabRow.tabs.setSelectedTab(0)
        if (views.emojiPickerTabRow.isSearching) return
        showSections(sections)
    }

    private fun onQuery(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            showSections(sections)
            views.emojiPickerRecycler.scrollToPosition(0)
            return
        }
        val filter = sectionFilter ?: return
        searchJob = viewScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val filtered = withContext(Dispatchers.Default) { filter(sections, query) }
            showSections(filtered)
            views.emojiPickerRecycler.scrollToPosition(0)
        }
    }

    private fun showSections(sections: List<EmojiPickerSection>) {
        adapter.update(sections)
        views.emojiPickerNoResults.isVisible = sections.isEmpty()
    }

    fun setSearchEnabled(enabled: Boolean) = views.emojiPickerTabRow.setSearchEnabled(enabled)

    fun setTrailingAction(
            @DrawableRes iconRes: Int,
            @StringRes contentDescriptionRes: Int,
            onPressChanged: (pressed: Boolean) -> Unit,
            onClick: () -> Unit,
    ) = views.emojiPickerTabRow.setTrailingAction(iconRes, contentDescriptionRes, onPressChanged, onClick)

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
        private const val SEARCH_DEBOUNCE_MS = 150L
        private const val GRID_VIEW_CACHE = 40
    }
}
