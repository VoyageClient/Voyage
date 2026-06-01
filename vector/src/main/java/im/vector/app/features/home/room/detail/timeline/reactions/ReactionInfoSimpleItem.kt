/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.reactions

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl

/**
 * Item displaying an emoji or image reaction (single line with reaction, author, time).
 */
@EpoxyModelClass
abstract class ReactionInfoSimpleItem : VectorEpoxyModel<ReactionInfoSimpleItem.Holder>(R.layout.item_simple_reaction_info) {

    @EpoxyAttribute
    lateinit var reactionKey: EpoxyCharSequence

    @EpoxyAttribute
    lateinit var authorDisplayName: String

    @EpoxyAttribute
    var timeStamp: String? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var userClicked: ClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var activeSessionHolder: ActiveSessionHolder? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        val key = reactionKey.charSequence.toString()
        if (key.isMxcUrl()) {
            bindImageReaction(holder, key)
        } else {
            Glide.with(holder.imageReactionView).clear(holder.imageReactionView)
            holder.imageReactionView.isVisible = false
            holder.emojiReactionView.applyEmojiLayout()
            holder.emojiReactionView.isVisible = true
            holder.emojiReactionView.text = reactionKey.charSequence
        }
        holder.displayNameView.text = authorDisplayName
        timeStamp?.let {
            holder.timeStampView.text = it
            holder.timeStampView.isVisible = true
        } ?: run {
            holder.timeStampView.isVisible = false
        }
        holder.view.onClick(userClicked)
    }

    private fun bindImageReaction(holder: Holder, mxcUrl: String) {
        // ❓ placeholder sized to the loaded image's footprint so the row doesn't reflow when
        // the bitmap arrives.
        holder.emojiReactionView.applyImagePlaceholderLayout()
        holder.emojiReactionView.text = QUESTION_MARK_EMOJI
        holder.emojiReactionView.isVisible = true
        holder.imageReactionView.isVisible = false
        val resolved = activeSessionHolder?.getSafeActiveSession()
                ?.contentUrlResolver()
                ?.resolveFullSize(mxcUrl)
        if (resolved == null) {
            Glide.with(holder.imageReactionView).clear(holder.imageReactionView)
            return
        }
        val loadSizePx = (IMAGE_SIZE_DP * holder.imageReactionView.resources.displayMetrics.density * IMAGE_OVERSAMPLE_FACTOR).toInt()
        Glide.with(holder.imageReactionView)
                .load(resolved)
                .override(loadSizePx, loadSizePx)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        holder.imageReactionView.isVisible = false
                        holder.emojiReactionView.isVisible = true
                        return false
                    }

                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        holder.emojiReactionView.isVisible = false
                        holder.imageReactionView.isVisible = true
                        return false
                    }
                })
                .into(holder.imageReactionView)
    }

    private fun TextView.applyImagePlaceholderLayout() {
        val px = (IMAGE_SIZE_DP * resources.displayMetrics.density).toInt()
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
            width = px
            height = px
        }
        gravity = Gravity.CENTER
    }

    private fun TextView.applyEmojiLayout() {
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }

    class Holder : VectorEpoxyHolder() {
        val emojiReactionView by bind<TextView>(R.id.itemSimpleReactionInfoKey)
        val imageReactionView by bind<ImageView>(R.id.itemSimpleReactionInfoImage)
        val displayNameView by bind<TextView>(R.id.itemSimpleReactionInfoMemberName)
        val timeStampView by bind<TextView>(R.id.itemSimpleReactionInfoTime)
    }

    companion object {
        private const val QUESTION_MARK_EMOJI = "❓"
        private const val IMAGE_SIZE_DP = 28
        private const val IMAGE_OVERSAMPLE_FACTOR = 2f
    }
}
