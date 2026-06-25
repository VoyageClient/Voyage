/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.reactions

import android.annotation.SuppressLint
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.R
import im.vector.app.core.glide.GlideApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Renders the reaction picker grid: a mix of custom-emote (image) packs and unicode emoji categories,
 * each as a header-prefixed section. Tabs in the activity map 1:1 to these sections.
 */
class EmojiRecyclerAdapter @Inject constructor() :
        RecyclerView.Adapter<EmojiRecyclerAdapter.ViewHolder>() {

    var reactionClickListener: ReactionClickListener? = null
    var interactionListener: InteractionListener? = null

    private var sections: List<EmojiPickerSection> = emptyList()
    private var mRecyclerView: RecyclerView? = null

    private var currentFirstVisibleSection = 0

    // Only let scrolling drive the selected tab when the user is actually dragging/flinging the list.
    // Programmatic jumps (from a tab tap) can't always pin the target header to the top — for the last
    // sections findFirstCompletelyVisible lands in the previous section and would mis-select the tab.
    private var userScrolling = false

    @SuppressLint("NotifyDataSetChanged")
    fun update(sections: List<EmojiPickerSection>) {
        this.sections = sections
        notifyDataSetChanged()
    }

    private val itemClickListener = View.OnClickListener { view ->
        mRecyclerView?.getChildLayoutPosition(view)?.let { itemPosition ->
            if (itemPosition != RecyclerView.NO_POSITION && !isSection(itemPosition)) {
                when (val item = itemAt(itemPosition)) {
                    is EmojiPickerItem.Unicode -> reactionClickListener?.onReactionSelected(item.glyph)
                    is EmojiPickerItem.Emote -> reactionClickListener?.onReactionSelected(item.key)
                    null -> Unit
                }
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.mRecyclerView = recyclerView

        val gridLayoutManager = GridLayoutManager(recyclerView.context, 8)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (isSection(position)) gridLayoutManager.spanCount else 1
            }
        }.apply {
            isSpanIndexCacheEnabled = true
        }
        recyclerView.layoutManager = gridLayoutManager

        recyclerView.itemAnimator = DefaultItemAnimator().apply {
            supportsChangeAnimations = false
        }

        recyclerView.recycledViewPool.setMaxRecycledViews(R.layout.grid_item_emoji, 300)
        recyclerView.addOnScrollListener(scrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.mRecyclerView = null
        recyclerView.removeOnScrollListener(scrollListener)
        staticLayoutCache.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun scrollToSection(section: Int) {
        if (section < 0 || section >= sections.size) return
        // Pin the section header to the top and pre-set the tracked section so the post-jump scroll event
        // doesn't bounce the tab indicator through neighbouring sections.
        currentFirstVisibleSection = section
        val layoutManager = mRecyclerView?.layoutManager as? GridLayoutManager
        layoutManager?.scrollToPositionWithOffset(getSectionOffset(section) - 1, 0)
                ?: mRecyclerView?.scrollToPosition(getSectionOffset(section) - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        itemView.setOnClickListener(itemClickListener)
        return when (viewType) {
            R.layout.grid_section_header -> SectionViewHolder(itemView)
            R.layout.grid_item_emote -> EmoteViewHolder(itemView)
            else -> EmojiViewHolder(itemView)
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (isSection(position)) return R.layout.grid_section_header
        return when (itemAt(position)) {
            is EmojiPickerItem.Emote -> R.layout.grid_item_emote
            else -> R.layout.grid_item_emoji
        }
    }

    private fun isSection(position: Int): Boolean {
        var sectionOffset = 1
        sections.forEach { section ->
            if (position == sectionOffset - 1) return true
            sectionOffset += section.items.size + 1
        }
        return false
    }

    private fun getSectionForAbsoluteIndex(position: Int): Int {
        var sectionOffset = 1
        var index = 0
        sections.forEach { section ->
            val lastItemInSection = sectionOffset + section.items.size - 1
            if (position <= lastItemInSection) return index
            sectionOffset = lastItemInSection + 2
            index++
        }
        return (sections.size - 1).coerceAtLeast(0)
    }

    private fun getSectionOffset(section: Int): Int {
        var sectionOffset = 1
        sections.forEachIndexed { index, s ->
            if (section == index) return sectionOffset
            sectionOffset += s.items.size + 1
        }
        return sectionOffset
    }

    private fun itemAt(position: Int): EmojiPickerItem? {
        val section = getSectionForAbsoluteIndex(position)
        val sectionOffset = getSectionOffset(section)
        return sections.getOrNull(section)?.items?.getOrNull(position - sectionOffset)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (isSection(position)) {
            (holder as SectionViewHolder).bind(sections[getSectionForAbsoluteIndex(position)].name)
            return
        }
        when (val item = itemAt(position)) {
            is EmojiPickerItem.Unicode -> (holder as EmojiViewHolder).bind(item.glyph)
            is EmojiPickerItem.Emote -> (holder as EmoteViewHolder).bind(item)
            null -> Unit
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        if (holder is EmoteViewHolder) holder.clear()
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = sections.sumOf { 1 /* header */ + it.items.size }

    abstract class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private class EmojiViewHolder(itemView: View) : ViewHolder(itemView) {
        private val emojiView: EmojiDrawView = itemView.findViewById(R.id.grid_item_emoji_text)
        private val placeHolder: View = itemView.findViewById(R.id.grid_item_place_holder)

        fun bind(glyph: String) {
            emojiView.emoji = glyph
            emojiView.mLayout = getStaticLayoutForEmoji(glyph)
            emojiView.contentDescription = glyph
            placeHolder.visibility = View.GONE
        }
    }

    private class EmoteViewHolder(itemView: View) : ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.grid_item_emote_image)

        fun bind(item: EmojiPickerItem.Emote) {
            imageView.contentDescription = item.contentDescription
            // Downsample to cell size so animated emotes play without a full-resolution decode.
            GlideApp.with(imageView).load(item.resolvedUrl).override(96, 96).into(imageView)
        }

        fun clear() {
            GlideApp.with(imageView.context.applicationContext).clear(imageView)
        }
    }

    private class SectionViewHolder(itemView: View) : ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.section_header_textview)
        fun bind(name: String) {
            textView.text = name
        }
    }

    companion object {
        private val staticLayoutCache = HashMap<String, StaticLayout>()

        private fun getStaticLayoutForEmoji(emoji: String): StaticLayout {
            return staticLayoutCache.getOrPut(emoji) {
                // Lay out at the text's natural width (single line) so multi-emoji / text reactions
                // ("👌😂", "hello") aren't wrapped/clipped; EmojiDrawView scales them down to fit the cell.
                val width = kotlin.math.ceil(EmojiDrawView.tPaint.measureText(emoji)).toInt().coerceAtLeast(EmojiDrawView.emojiSize)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(emoji, 0, emoji.length, EmojiDrawView.tPaint, width)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setLineSpacing(0f, 1f)
                            .setIncludePad(true)
                            .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(emoji, EmojiDrawView.tPaint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, true)
                }
            }
        }
    }

    interface InteractionListener {
        fun getCoroutineScope(): CoroutineScope
        fun firstVisibleSectionChange(section: Int)
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            when (newState) {
                RecyclerView.SCROLL_STATE_DRAGGING -> userScrolling = true
                RecyclerView.SCROLL_STATE_IDLE -> userScrolling = false
            }
        }

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            if (!userScrolling) return
            val visible = (recyclerView.layoutManager as GridLayoutManager).findFirstCompletelyVisibleItemPosition()
            interactionListener?.getCoroutineScope()?.launch {
                val section = getSectionForAbsoluteIndex(visible)
                if (section != currentFirstVisibleSection) {
                    currentFirstVisibleSection = section
                    interactionListener?.getCoroutineScope()?.launch(Dispatchers.Main) {
                        interactionListener?.firstVisibleSectionChange(currentFirstVisibleSection)
                    }
                }
            }
        }
    }
}
