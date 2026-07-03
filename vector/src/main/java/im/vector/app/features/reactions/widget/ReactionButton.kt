/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.reactions.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.EmojiSpanify
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.extensions.layoutDirectionCompat
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.ui.PerformanceMode
import im.vector.app.core.utils.TextUtils
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.databinding.ReactionButtonBinding
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import javax.inject.Inject
import kotlin.math.roundToInt

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
        LinearLayout(ContextThemeWrapper(context, defStyleRes), attrs, defStyleAttr), View.OnClickListener, View.OnLongClickListener {

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
        layoutDirectionCompat = View.LAYOUT_DIRECTION_LOCALE
        views = ReactionButtonBinding.bind(this)
        views.reactionCount.text = TextUtils.formatCountToShortDecimal(reactionCount)
        context.withStyledAttributes(attrs, im.vector.lib.ui.styles.R.styleable.ReactionButton, defStyleAttr) {
            onDrawable = ContextCompat.getDrawable(context, R.drawable.reaction_rounded_rect_shape)
            offDrawable = ContextCompat.getDrawable(context, R.drawable.reaction_rounded_rect_shape_off)
            tintDrawablesFromTheme()
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

    // All colors are applied in code: theme attrs inside the drawable XML don't resolve pre-21
    // (solid/wrong pills on ICS), and the "on" fill is additionally recoloured from the themed
    // element-green to the accent — keeping the theme's alpha — so the highlight matches the outline.
    private fun tintDrawablesFromTheme() {
        val accent = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorPrimary)
        val themedFill = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_reaction_background_on)
        val themedOff = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_reaction_background_off)
        (onDrawable?.mutate() as? GradientDrawable)?.let { drawable ->
            drawable.setColor(ColorUtils.setAlphaComponent(accent, Color.alpha(themedFill)))
            drawable.setStroke(resources.displayMetrics.density.roundToInt(), accent)
            onDrawable = drawable
        }
        (offDrawable?.mutate() as? GradientDrawable)?.let { drawable ->
            drawable.setColor(themedOff)
            offDrawable = drawable
        }
    }

    private fun applyReactionContent(value: String) {
        // A recycled button may have been caught mid-bounce (scale < 1) — rebinding must show the glyph.
        views.reactionText.animate().cancel()
        views.reactionText.scaleX = 1f
        views.reactionText.scaleY = 1f
        if (!value.isMxcUrl()) {
            // Plain emoji / unicode reaction.
            GlideApp.with(views.reactionImage).clear(views.reactionImage)
            views.reactionImage.isVisible = false
            views.reactionText.setReactionTextLayoutForEmoji()
            views.reactionText.isVisible = true
            views.reactionText.text = emojiSpanify.spanify(value)
            return
        }
        // Image reaction. Show a ❓ placeholder at the exact size the loaded image will occupy
        // so the row doesn't reflow when Glide swaps the bitmap in.
        views.reactionText.setReactionTextLayoutForImagePlaceholder()
        // Spanify so ❓ renders via EmojiCompat on devices (KitKat) that lack the glyph, instead of tofu.
        views.reactionText.text = emojiSpanify.spanify(QUESTION_MARK_EMOJI)
        views.reactionText.isVisible = true
        views.reactionImage.isVisible = false
        if (blockImages) {
            // Media hidden for this room: keep the ❓ and don't fetch the image.
            GlideApp.with(views.reactionImage).clear(views.reactionImage)
            return
        }
        val resolved = activeSessionHolder.getSafeActiveSession()
                ?.contentUrlResolver()
                ?.resolveFullSize(value)
        if (resolved == null) {
            // Malformed mxc or no active session — leave the ❓ visible permanently.
            GlideApp.with(views.reactionImage).clear(views.reactionImage)
            return
        }
        val loadSizePx = (IMAGE_SIZE_DP * resources.displayMetrics.density * IMAGE_OVERSAMPLE_FACTOR).toInt()
        GlideApp.with(views.reactionImage)
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
        backgroundCompat = if (isChecked) onDrawable else offDrawable

        if (isChecked) {
            reactedListener?.onReacted(this)
            views.reactionText.animate().cancel()
            if (PerformanceMode.enabled) {
                views.reactionText.scaleX = 1f
                views.reactionText.scaleY = 1f
            } else {
                // Bounce the emoji in; without the follow-up animation, zeroing the scale left the
                // glyph invisible for good.
                views.reactionText.scaleX = 0f
                views.reactionText.scaleY = 0f
                views.reactionText.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(300)
                        .setInterpolator(OvershootInterpolator())
                        .start()
            }
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
            backgroundCompat = onDrawable
        } else {
            isChecked = false
            backgroundCompat = offDrawable
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
