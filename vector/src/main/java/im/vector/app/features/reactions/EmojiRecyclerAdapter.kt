/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.reactions

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.emoji2.text.EmojiCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import im.vector.app.R
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.glide.GridImagePreloader
import im.vector.app.features.imagepack.EmoteFrameCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Renders the reaction picker grid: a mix of custom-emote (image) packs and unicode emoji categories,
 * each as a header-prefixed section. Tabs in the activity map 1:1 to these sections.
 */
private const val EMOTE_CELL_PX = 96
private const val ITEM_VIEW_CACHE_SIZE = 300

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
        rebuildPositionTables()
        notifyDataSetChanged()
        mRecyclerView?.context?.let { context ->
            val emotes = sections.flatMap { section -> section.items }.filterIsInstance<EmojiPickerItem.Emote>()
            val mxcByResolvedUrl = emotes.mapNotNull { emote -> emote.resolvedUrl?.let { it to emote.key } }.toMap()
            GridImagePreloader.warm(
                    key = "emotes",
                    context = context,
                    urls = emotes.mapNotNull { it.resolvedUrl },
                    size = EMOTE_CELL_PX,
                    animated = false,
                    keepFrameFor = { resolvedUrl -> mxcByResolvedUrl[resolvedUrl] },
            )
        }
    }

    private val itemClickListener = View.OnClickListener { view ->
        mRecyclerView?.getChildLayoutPosition(view)?.let { itemPosition ->
            if (itemPosition != RecyclerView.NO_POSITION && !isSection(itemPosition)) {
                when (val item = itemAt(itemPosition)) {
                    is EmojiPickerItem.Unicode -> reactionClickListener?.onReactionSelected(item.glyph)
                    is EmojiPickerItem.Emote -> {
                        // What the cell drew is what the reaction or message will show at once.
                        (view.findViewById(R.id.grid_item_emote_image) as? ImageView)
                                ?.let { EmoteFrameCache.captureFrom(it, item.key) }
                        reactionClickListener?.onReactionSelected(item.key)
                    }
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

        // Nothing here animates in or out — the whole grid is replaced at once — and an animator keeps
        // its own copies of every changed holder.
        recyclerView.itemAnimator = null
        // The grid's own bounds never depend on its contents, so a data change need not remeasure it.
        recyclerView.setHasFixedSize(true)
        // Two screens' worth of already-bound cells, so scrolling back over what was just passed
        // rebinds nothing: at ~150 cells to a screen the default of 2 is a rounding error here.
        recyclerView.setItemViewCacheSize(ITEM_VIEW_CACHE_SIZE)

        recyclerView.recycledViewPool.setMaxRecycledViews(R.layout.grid_item_emoji, 400)
        recyclerView.recycledViewPool.setMaxRecycledViews(R.layout.grid_item_emote, 400)
        recyclerView.addOnScrollListener(scrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.mRecyclerView = null
        GridImagePreloader.cancel("emotes")
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

    // Position tables, rebuilt with the data. Every one of these is asked per position, and the span
    // size lookup is asked for positions far beyond the viewport — walking the section list each time
    // made the cost of a scroll grow with how far down the grid it happened.
    private var sectionOfPosition = IntArray(0)
    private var headerPositions = IntArray(0)
    private var sectionOffsets = IntArray(0)
    private var itemOfPosition = arrayOfNulls<EmojiPickerItem>(0)

    private fun rebuildPositionTables() {
        val total = sections.sumOf { 1 + it.items.size }
        val sectionOf = IntArray(total)
        val items = arrayOfNulls<EmojiPickerItem>(total)
        val headers = IntArray(sections.size)
        val offsets = IntArray(sections.size)
        var position = 0
        sections.forEachIndexed { index, section ->
            headers[index] = position
            sectionOf[position] = index
            position++
            offsets[index] = position
            section.items.forEach { item ->
                sectionOf[position] = index
                items[position] = item
                position++
            }
        }
        sectionOfPosition = sectionOf
        itemOfPosition = items
        headerPositions = headers
        sectionOffsets = offsets
    }

    private fun isSection(position: Int): Boolean =
            position in itemOfPosition.indices && itemOfPosition[position] == null

    private fun getSectionForAbsoluteIndex(position: Int): Int =
            sectionOfPosition.getOrNull(position) ?: (sections.size - 1).coerceAtLeast(0)

    private fun getSectionOffset(section: Int): Int = sectionOffsets.getOrNull(section) ?: 1

    private fun itemAt(position: Int): EmojiPickerItem? = itemOfPosition.getOrNull(position)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (isSection(position)) {
            (holder as SectionViewHolder).bind(sections[getSectionForAbsoluteIndex(position)].name)
        } else {
            when (val item = itemAt(position)) {
                is EmojiPickerItem.Unicode -> (holder as EmojiViewHolder).bind(item.glyph)
                is EmojiPickerItem.Emote -> (holder as EmoteViewHolder).bind(item)
                null -> Unit
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        if (holder is EmoteViewHolder) holder.clear()
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = itemOfPosition.size

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
            // Drawn straight from memory, like the unicode cells' sprites: a cell scrolled back to
            // repaints in its bind rather than blanking and waiting for a fresh request.
            EmoteFrameCache.get(item.key)?.let {
                imageView.setImageBitmap(it)
                return
            }
            imageView.setImageDrawable(null)
            // A still frame, at cell size. Animated emotes are the norm in a pack, and an animated
            // drawable is delivered *synchronously* on a memory-cache hit — constructing one and
            // starting it (which decodes a frame) cost 10-50ms of the bind, per cell. Unicode cells are
            // fast for the same reason: they draw a ready bitmap.
            GlideApp.with(imageView.context)
                    .asBitmap()
                    .load(item.resolvedUrl)
                    .override(EMOTE_CELL_PX, EMOTE_CELL_PX)
                    // Keep the decoded cell, not just the original: a pack's files are full-size, and
                    // the default strategy caches only those — so every rebind decoded one again.
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .addListener(object : RequestListener<Bitmap> {
                        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean) = false

                        override fun onResourceReady(
                                resource: Bitmap,
                                model: Any,
                                target: Target<Bitmap>?,
                                dataSource: DataSource,
                                isFirstResource: Boolean,
                        ): Boolean {
                            // Keep it for every later bind of this emote, here and in the timeline.
                            EmoteFrameCache.put(item.key, resource)
                            return false
                        }
                    })
                    .into(imageView)
        }

        fun clear() {
            // Not Glide.clear(): a retriever lookup and a cancel per recycled cell cost ~15% of a
            // scrolling second, and the next bind's into() cancels the old request for this view anyway.
            imageView.setImageDrawable(null)
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
                // In Twemoji mode a multi-glyph reaction has no single sprite, so span each emoji inside it;
                // otherwise process through EmojiCompat so the grid uses the bundled font like the composer.
                val source = EmojiDrawView.twemojiSpanify?.invoke(emoji) ?: emojiCompatProcess(emoji)
                // Single-line natural width so multi-glyph reactions aren't wrapped/clipped (EmojiDrawView
                // scales to fit). Measure the PROCESSED source — getDesiredWidth accounts for its spans.
                val width = kotlin.math.ceil(Layout.getDesiredWidth(source, EmojiDrawView.tPaint)).toInt().coerceAtLeast(EmojiDrawView.emojiSize)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(source, 0, source.length, EmojiDrawView.tPaint, width)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setLineSpacing(0f, 1f)
                            .setIncludePad(true)
                            .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(source, EmojiDrawView.tPaint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, true)
                }
            }
        }

        private fun emojiCompatProcess(emoji: String): CharSequence {
            return try {
                if (EmojiCompat.isConfigured()) {
                    val emojiCompat = EmojiCompat.get()
                    if (emojiCompat.loadState == EmojiCompat.LOAD_STATE_SUCCEEDED) {
                        emojiCompat.process(emoji) ?: emoji
                    } else {
                        emoji
                    }
                } else {
                    emoji
                }
            } catch (throwable: Throwable) {
                emoji
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
