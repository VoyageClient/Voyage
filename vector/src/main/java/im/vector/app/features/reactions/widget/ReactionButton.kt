/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.reactions.widget

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.EmojiSpanify
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.utils.TextUtils
import im.vector.app.databinding.ReactionButtonBinding
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import javax.inject.Inject

/**
 * An animated reaction button.
 * Displays a String reaction (emoji or mxc:// image), with a count, and that can be selected or
 * not (toggle).
 */
@AndroidEntryPoint
class ReactionButton @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        defStyleRes: Int = im.vector.lib.ui.styles.R.style.TimelineReactionView
) :
        LinearLayout(context, attrs, defStyleAttr, defStyleRes), View.OnClickListener, View.OnLongClickListener {

    @Inject lateinit var emojiSpanify: EmojiSpanify
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder

    private val views: ReactionButtonBinding

    var reactedListener: ReactedListener? = null

    var reactionCount = 11
        set(value) {
            field = value
            views.reactionCount.text = TextUtils.formatCountToShortDecimal(value)
        }

    var reactionString = "😀"
        set(value) {
            field = value
            applyReactionContent(value)
        }

    // When true, custom image-emoji (mxc://) reactions are not fetched — the ❓ placeholder is kept,
    // mirroring the room's media-hiding setting. Set before [reactionString].
    var blockImages = false

    private var isChecked: Boolean = false
    private var onDrawable: Drawable? = null
    private var offDrawable: Drawable? = null

    init {
        inflate(context, R.layout.reaction_button, this)
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        views = ReactionButtonBinding.bind(this)
        views.reactionCount.text = TextUtils.formatCountToShortDecimal(reactionCount)
        context.withStyledAttributes(attrs, im.vector.lib.ui.styles.R.styleable.ReactionButton, defStyleAttr) {
            onDrawable = ContextCompat.getDrawable(context, R.drawable.reaction_rounded_rect_shape)
            offDrawable = ContextCompat.getDrawable(context, R.drawable.reaction_rounded_rect_shape_off)
            getString(im.vector.lib.ui.styles.R.styleable.ReactionButton_emoji)?.let {
                reactionString = it
            }
            reactionCount = getInt(im.vector.lib.ui.styles.R.styleable.ReactionButton_reaction_count, 0)
            val status = getBoolean(im.vector.lib.ui.styles.R.styleable.ReactionButton_toggled, false)
            setChecked(status)
        }

        setOnClickListener(this)
        setOnLongClickListener(this)
    }

    private fun applyReactionContent(value: String) {
        if (!value.isMxcUrl()) {
            // Plain emoji / unicode reaction.
            Glide.with(views.reactionImage).clear(views.reactionImage)
            views.reactionImage.isVisible = false
            views.reactionText.setReactionTextLayoutForEmoji()
            views.reactionText.isVisible = true
            views.reactionText.text = emojiSpanify.spanify(value)
            return
        }
        // Image reaction. Show a ❓ placeholder at the exact size the loaded image will occupy
        // so the row doesn't reflow when Glide swaps the bitmap in.
        views.reactionText.setReactionTextLayoutForImagePlaceholder()
        views.reactionText.text = QUESTION_MARK_EMOJI
        views.reactionText.isVisible = true
        views.reactionImage.isVisible = false
        if (blockImages) {
            // Media hidden for this room: keep the ❓ and don't fetch the image.
            Glide.with(views.reactionImage).clear(views.reactionImage)
            return
        }
        val resolved = activeSessionHolder.getSafeActiveSession()
                ?.contentUrlResolver()
                ?.resolveFullSize(value)
        if (resolved == null) {
            // Malformed mxc or no active session — leave the ❓ visible permanently.
            Glide.with(views.reactionImage).clear(views.reactionImage)
            return
        }
        val loadSizePx = (IMAGE_SIZE_DP * resources.displayMetrics.density * IMAGE_OVERSAMPLE_FACTOR).toInt()
        Glide.with(views.reactionImage)
                .load(resolved)
                .override(loadSizePx, loadSizePx)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        views.reactionImage.isVisible = false
                        views.reactionText.isVisible = true
                        return false
                    }

                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        views.reactionText.isVisible = false
                        views.reactionImage.isVisible = true
                        return false
                    }
                })
                .into(views.reactionImage)
    }

    private fun TextView.setReactionTextLayoutForImagePlaceholder() {
        val px = (IMAGE_SIZE_DP * resources.displayMetrics.density).toInt()
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
            width = px
            height = px
        }
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun TextView.setReactionTextLayoutForEmoji() {
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }

    /**
     * This triggers the entire functionality of the button such as icon changes,
     * animations, listeners etc.
     */
    override fun onClick(v: View) {
        if (!isEnabled) {
            return
        }
        isChecked = !isChecked
        background = if (isChecked) onDrawable else offDrawable

        if (isChecked) {
            reactedListener?.onReacted(this)
            views.reactionText.animate().cancel()
            views.reactionText.scaleX = 0f
            views.reactionText.scaleY = 0f
        } else {
            reactedListener?.onUnReacted(this)
        }
    }

    override fun onLongClick(v: View?): Boolean {
        reactedListener?.onLongClick(this)
        return reactedListener != null
    }

    /**
     * Sets the initial state of the button to liked or unliked.
     */
    fun setChecked(status: Boolean?) {
        if (status!!) {
            isChecked = true
            background = onDrawable
        } else {
            isChecked = false
            background = offDrawable
        }
    }

    interface ReactedListener {
        fun onReacted(reactionButton: ReactionButton)
        fun onUnReacted(reactionButton: ReactionButton)
        fun onLongClick(reactionButton: ReactionButton)
    }

    companion object {
        private const val QUESTION_MARK_EMOJI = "❓"
        private const val IMAGE_SIZE_DP = 20
        private const val IMAGE_OVERSAMPLE_FACTOR = 2f
    }
}
