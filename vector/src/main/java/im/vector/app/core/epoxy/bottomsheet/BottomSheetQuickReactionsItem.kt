/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.epoxy.bottomsheet

import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.EmojiCompatFontProvider
import im.vector.app.R
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.utils.DimensionConverter

/**
 * A quick reaction list for bottom sheet. Emojis wrap onto more rows by default; in compact mode they
 * stay on a single row that scrolls horizontally when there are too many to fit.
 */
@EpoxyModelClass
abstract class BottomSheetQuickReactionsItem : VectorEpoxyModel<BottomSheetQuickReactionsItem.Holder>(R.layout.item_bottom_sheet_quick_reaction) {

    @EpoxyAttribute
    lateinit var fontProvider: EmojiCompatFontProvider

    @EpoxyAttribute
    lateinit var texts: List<String>

    // Parallel to [texts]: a resolved thumbnail URL for custom-emote (mxc) reactions, null for plain emojis.
    @EpoxyAttribute
    var resolvedUrls: List<String?> = emptyList()

    @EpoxyAttribute
    lateinit var selecteds: List<Boolean>

    @EpoxyAttribute
    var compact: Boolean = false

    @EpoxyAttribute
    var listener: Listener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        val context = holder.view.context
        val padding = DimensionConverter(context.resources).dpToPx(4)

        holder.addedViews.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        holder.addedViews.clear()

        holder.wrapContainer.isVisible = !compact
        holder.scroll.isVisible = compact

        val imageSize = DimensionConverter(context.resources).dpToPx(28)
        val ids = IntArray(texts.size)
        texts.forEachIndexed { index, emoji ->
            val selected = selecteds.getOrElse(index) { false }
            val resolvedUrl = resolvedUrls.getOrNull(index)
            val itemView: View = if (resolvedUrl != null) {
                // Custom-emote (mxc) reaction: render the image rather than its raw url text.
                android.widget.ImageView(context).apply {
                    id = ViewCompat.generateViewId()
                    layoutParams = ViewGroup.LayoutParams(imageSize + padding * 2, imageSize + padding * 2)
                    setPadding(padding, padding, padding, padding)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    alpha = if (selected) 0.2f else 1f
                    onClick { listener?.didSelect(emoji, !selected) }
                    im.vector.app.core.glide.GlideApp.with(this).load(resolvedUrl).into(this)
                }
            } else {
                TextView(ContextThemeWrapper(context, im.vector.lib.ui.styles.R.style.Widget_Vector_TextView_Title), null, 0).apply {
                    id = ViewCompat.generateViewId()
                    setPadding(padding, padding, padding, padding)
                    typeface = fontProvider.typeface ?: Typeface.DEFAULT
                    text = emoji.prepareForDisplay()
                    alpha = if (selected) 0.2f else 1f
                    onClick { listener?.didSelect(emoji, !selected) }
                }
            }
            holder.addedViews.add(itemView)
            if (compact) {
                // Weighted so leftover width is shared evenly (emojis spread out); on overflow there
                // is no leftover and the row scrolls horizontally instead.
                (itemView as? TextView)?.gravity = Gravity.CENTER
                // Images need an explicit size (WRAP_CONTENT would blow up to the bitmap); text wraps as before.
                val width = if (itemView is android.widget.ImageView) imageSize + padding * 2 else LinearLayout.LayoutParams.WRAP_CONTENT
                val height = if (itemView is android.widget.ImageView) imageSize + padding * 2 else LinearLayout.LayoutParams.WRAP_CONTENT
                holder.row.addView(itemView, LinearLayout.LayoutParams(width, height, 1f))
            } else {
                holder.wrapContainer.addView(itemView)
                ids[index] = itemView.id
            }
        }
        if (!compact) {
            holder.flow.referencedIds = ids
        }
    }

    class Holder : VectorEpoxyHolder() {
        val wrapContainer by bind<ConstraintLayout>(R.id.reactionsWrapContainer)
        val flow by bind<Flow>(R.id.reactionsFlowHelper)
        val scroll by bind<HorizontalScrollView>(R.id.reactionsScroll)
        val row by bind<LinearLayout>(R.id.reactionsRow)
        val addedViews = mutableListOf<View>()
    }

    interface Listener {
        fun didSelect(emoji: String, selected: Boolean)
    }
}
