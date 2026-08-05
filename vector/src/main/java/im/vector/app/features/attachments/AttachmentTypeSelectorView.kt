/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.util.AttributeSet
import android.util.Pair
import android.view.LayoutInflater
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import im.vector.app.R
import im.vector.app.core.epoxy.onClick
import im.vector.app.databinding.ViewAttachmentTypeSelectorBinding
import im.vector.app.features.attachments.AttachmentTypeSelectorView.Callback
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import kotlin.math.max

private const val ANIMATION_DURATION = 250
private const val DISABLED_ALPHA = 0.4f

/**
 * This class is the view presenting choices for picking attachments.
 * It will return result through [Callback].
 *
 * It covers the composer's input row, and shares the composer's parent so the two stay aligned
 * through keyboard changes, the reply preview opening and the bottom sheet resizing it.
 */
class AttachmentTypeSelectorView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Callback {
        fun onTypeSelected(type: AttachmentType)
    }

    var callback: Callback? = null

    private val views = ViewAttachmentTypeSelectorBinding.inflate(LayoutInflater.from(context), this, true)

    private var anchor: View? = null

    val isOpen: Boolean get() = isVisible

    /** Notified when the selector opens or closes, so callers can gate back-press handling. */
    var onOpenChanged: ((Boolean) -> Unit)? = null

    init {
        views.attachmentGalleryButton.configure(AttachmentType.GALLERY)
        views.attachmentCameraButton.configure(AttachmentType.CAMERA)
        views.attachmentFileButton.configure(AttachmentType.FILE)
        views.attachmentStickersButton.configure(AttachmentType.STICKER)
        views.attachmentLocalStickersButton.configure(AttachmentType.STICKER_LOCAL)
        views.attachmentVoiceFileButton.configure(AttachmentType.VOICE_FILE)
        views.attachmentPollButton.configure(AttachmentType.POLL)
        views.attachmentLocationButton.configure(AttachmentType.LOCATION)

        // Swallow taps so they can't reach the composer underneath.
        isClickable = true
        isVisible = false

        views.attachmentCloseButton.onClick { hide() }
    }

    /** Match the classic composer: same background, and a bare "+" glyph rotated into an X. */
    fun applyClassicComposerStyle() {
        // The inflated root carries its own ?android:colorBackground, which would paint over this view's.
        views.root.setBackgroundColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_toolbar_background))
        val size = resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.composer_classic_button_size)
        val startMargin = resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.composer_classic_button_margin)
        views.attachmentCloseButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            width = size
            height = size
            // Sit exactly where the composer's "+" does, so the glyph doesn't jump when it rotates.
            MarginLayoutParamsCompat.setMarginStart(this, startMargin)
            leftMargin = startMargin
            topMargin = 0
            bottomMargin = 0
        }
        views.attachmentCloseButton.setPadding(resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.composer_classic_plus_padding))
        views.attachmentCloseButton.scaleType = ImageView.ScaleType.FIT_CENTER
        views.attachmentCloseButton.setImageResource(R.drawable.ic_plus)
        ImageViewCompat.setImageTintList(
                views.attachmentCloseButton,
                ColorStateList.valueOf(ThemeUtils.getColor(context, androidx.appcompat.R.attr.colorAccent))
        )
        AttachmentType.values().forEach { buttonForType(it).background = null }
    }

    fun containsScreenPoint(x: Float, y: Float): Boolean {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return x >= location[0] && x <= location[0] + width && y >= location[1] && y <= location[1] + height
    }

    fun show(anchor: View) {
        this.anchor = anchor
        isVisible = true
        onOpenChanged?.invoke(true)
        animateOpen()
        doOnNextLayout { animateWindowInCircular(anchor, this) }
    }

    fun hide() {
        if (!isVisible) return
        onOpenChanged?.invoke(false)
        animateClose()

        val capturedAnchor = anchor
        if (capturedAnchor != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            animateWindowOutCircular(capturedAnchor, this)
        } else {
            animateWindowOutTranslate(this)
        }
    }

    private fun animateOpen() {
        views.attachmentCloseButton.animate()
                .setDuration(200)
                .rotation(135f)
    }

    private fun animateClose() {
        views.attachmentCloseButton.animate()
                .setDuration(200)
                .rotation(0f)
    }

    fun setAttachmentVisibility(type: AttachmentType, isVisible: Boolean) {
        buttonForType(type).isVisible = isVisible
    }

    /**
     * Keep the button visible but dim it and ignore clicks, to signal an option that exists but is
     * unavailable on this device (e.g. location, which needs maplibre / API 21+).
     */
    fun setAttachmentEnabled(type: AttachmentType, isEnabled: Boolean) {
        buttonForType(type).apply {
            this.isEnabled = isEnabled
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
    }

    private fun buttonForType(type: AttachmentType): ImageButton = when (type) {
        AttachmentType.CAMERA -> views.attachmentCameraButton
        AttachmentType.GALLERY -> views.attachmentGalleryButton
        AttachmentType.FILE -> views.attachmentFileButton
        AttachmentType.STICKER -> views.attachmentStickersButton
        AttachmentType.STICKER_LOCAL -> views.attachmentLocalStickersButton
        AttachmentType.VOICE_FILE -> views.attachmentVoiceFileButton
        AttachmentType.POLL -> views.attachmentPollButton
        AttachmentType.LOCATION -> views.attachmentLocationButton
    }

    private fun animateWindowInCircular(anchor: View, contentView: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val coordinates = getClickCoordinates(anchor, contentView)
        val animator = ViewAnimationUtils.createCircularReveal(
                contentView,
                coordinates.first,
                coordinates.second,
                0f,
                max(contentView.width, contentView.height).toFloat()
        )
        animator.duration = ANIMATION_DURATION.toLong()
        animator.start()
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun animateWindowOutCircular(anchor: View, contentView: View) {
        val coordinates = getClickCoordinates(anchor, contentView)
        val animator = ViewAnimationUtils.createCircularReveal(
                contentView,
                coordinates.first,
                coordinates.second,
                max(contentView.width, contentView.height).toFloat(),
                0f
        )

        animator.duration = ANIMATION_DURATION.toLong()
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isVisible = false
            }
        })
        animator.start()
    }

    private fun animateWindowOutTranslate(contentView: View) {
        val animation = TranslateAnimation(0f, 0f, 0f, (contentView.top + contentView.height).toFloat())
        animation.duration = ANIMATION_DURATION.toLong()
        animation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}

            override fun onAnimationEnd(animation: Animation) {
                isVisible = false
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })

        contentView.startAnimation(animation)
    }

    private fun getClickCoordinates(anchor: View, contentView: View): Pair<Int, Int> {
        val anchorCoordinates = IntArray(2)
        anchor.getLocationOnScreen(anchorCoordinates)
        val contentCoordinates = IntArray(2)
        contentView.getLocationOnScreen(contentCoordinates)
        val x = anchorCoordinates[0] - contentCoordinates[0] + anchor.width / 2
        val y = anchorCoordinates[1] - contentCoordinates[1]
        return Pair(x, y)
    }

    private fun ImageButton.configure(type: AttachmentType): ImageButton {
        this.setOnClickListener(TypeClickListener(type))
        TooltipCompat.setTooltipText(this, context.getString(attachmentTooltipLabels.getValue(type)))
        return this
    }

    private inner class TypeClickListener(private val type: AttachmentType) : View.OnClickListener {

        override fun onClick(v: View) {
            hide()
            callback?.onTypeSelected(type)
        }
    }

    /**
     * The all possible types to pick with their required permissions and tooltip resource.
     */
    private companion object {
        private val attachmentTooltipLabels: Map<AttachmentType, Int> = AttachmentType.values().associateWith {
            when (it) {
                AttachmentType.CAMERA -> CommonStrings.tooltip_attachment_photo
                AttachmentType.GALLERY -> CommonStrings.tooltip_attachment_gallery
                AttachmentType.FILE -> CommonStrings.tooltip_attachment_file
                AttachmentType.STICKER -> CommonStrings.tooltip_attachment_sticker_online
                AttachmentType.STICKER_LOCAL -> CommonStrings.tooltip_attachment_sticker
                AttachmentType.VOICE_FILE -> CommonStrings.tooltip_attachment_voice_file
                AttachmentType.POLL -> CommonStrings.tooltip_attachment_poll
                AttachmentType.LOCATION -> CommonStrings.tooltip_attachment_location
            }
        }
    }
}
