/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.epoxy.profiles

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.ui.colorpicker.ColorSwatches
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.themes.ThemeUtils
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass
abstract class ProfileActionItem : VectorEpoxyModel<ProfileActionItem.Holder>(R.layout.item_profile_action) {

    @EpoxyAttribute
    lateinit var title: String

    @EpoxyAttribute
    var subtitle: String? = null

    @EpoxyAttribute
    var iconRes: Int = 0

    @EpoxyAttribute
    var tintIcon: Boolean = true

    @EpoxyAttribute
    var editableRes: Int = R.drawable.ic_arrow_right

    @EpoxyAttribute
    var accessoryRes: Int = 0

    @EpoxyAttribute
    var accessoryMatrixItem: MatrixItem? = null

    @EpoxyAttribute
    var profileColorGeneration: Long = 0

    @EpoxyAttribute
    @ColorInt
    var accessoryColor: Int? = null

    @EpoxyAttribute
    var avatarRenderer: AvatarRenderer? = null

    @EpoxyAttribute
    var editable: Boolean = true

    @EpoxyAttribute
    var notificationBadgeVisible: Boolean = false

    @EpoxyAttribute
    var destructive: Boolean = false

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var listener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.view.onClick(listener)
        if (listener == null) {
            holder.view.isClickable = false
        }
        holder.title.text = title
        val titleTintColor = if (destructive) {
            ThemeUtils.getColor(holder.view.context, com.google.android.material.R.attr.colorError)
        } else {
            ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
        }
        val iconTintColor = if (destructive) {
            ThemeUtils.getColor(holder.view.context, com.google.android.material.R.attr.colorError)
        } else {
            ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        }
        holder.title.setTextColor(titleTintColor)
        // Subtitles carry user content (names, room names): render emoji and box direction overrides
        holder.subtitle.setTextOrHide(subtitle?.prepareForDisplay())
        if (iconRes != 0) {
            holder.icon.setImageResource(iconRes)
            if (tintIcon) {
                ImageViewCompat.setImageTintList(holder.icon, ColorStateList.valueOf(iconTintColor))
            } else {
                ImageViewCompat.setImageTintList(holder.icon, null)
            }
            holder.icon.isVisible = true
        } else {
            holder.icon.isVisible = false
        }

        if (accessoryRes != 0) {
            holder.clearAccessoryFade()
            holder.accessoryColor = null
            holder.secondaryAccessory.updateLayoutParams {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            holder.secondaryAccessory.setImageResource(accessoryRes)
            holder.secondaryAccessory.isVisible = true
        } else if (accessoryMatrixItem != null) {
            holder.clearAccessoryFade()
            holder.accessoryColor = null
            // Same size as the settings avatar widget; wrap_content can't size an async avatar load.
            val size = (40 * holder.view.resources.displayMetrics.density).toInt()
            holder.secondaryAccessory.updateLayoutParams {
                width = size
                height = size
            }
            avatarRenderer?.render(accessoryMatrixItem!!, holder.secondaryAccessory)
            holder.secondaryAccessory.isVisible = true
        } else if (accessoryColor != null) {
            val size = (40 * holder.view.resources.displayMetrics.density).toInt()
            holder.secondaryAccessory.updateLayoutParams {
                width = size
                height = size
            }
            val next = ColorSwatches.round(accessoryColor!!)
            val previous = holder.secondaryAccessory.drawable
            if (holder.accessoryFadeColor == accessoryColor && holder.secondaryAccessory.drawable === holder.accessoryFadeDrawable) {
                // Keep the transition that a duplicate profile update is already showing.
            } else if (holder.accessoryColor != null && holder.accessoryColor != accessoryColor && previous != null) {
                holder.clearAccessoryFade()
                val layered = LayerDrawable(arrayOf(next, previous))
                holder.secondaryAccessory.setImageDrawable(layered)
                lateinit var animator: ValueAnimator
                animator = ValueAnimator.ofInt(255, 0).apply {
                    duration = COLOR_FADE_MS
                    addUpdateListener {
                        previous.alpha = it.animatedValue as Int
                        layered.invalidateSelf()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (holder.accessoryFadeAnimator !== animator) return
                            holder.clearAccessoryFade()
                            previous.alpha = 255
                            if (holder.secondaryAccessory.drawable === layered) holder.secondaryAccessory.setImageDrawable(next)
                        }
                    })
                }
                holder.accessoryFadeColor = accessoryColor
                holder.accessoryFadeDrawable = layered
                holder.accessoryFadeAnimator = animator
                animator.start()
            } else {
                holder.clearAccessoryFade()
                holder.secondaryAccessory.setImageDrawable(next)
            }
            holder.accessoryColor = accessoryColor
            holder.secondaryAccessory.isVisible = true
        } else {
            holder.clearAccessoryFade()
            holder.accessoryColor = null
            holder.secondaryAccessory.isVisible = false
        }

        holder.notificationBadge.isVisible = notificationBadgeVisible

        if (editableRes != 0 && editable) {
            val tintColorSecondary = if (destructive) {
                titleTintColor
            } else {
                ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
            }
            holder.editable.setImageResource(editableRes)
            ImageViewCompat.setImageTintList(holder.editable, ColorStateList.valueOf(tintColorSecondary))
            holder.editable.isVisible = true
        } else {
            holder.editable.isVisible = false
        }
    }

    class Holder : VectorEpoxyHolder() {
        val icon by bind<ImageView>(R.id.actionIcon)
        val title by bind<TextView>(R.id.actionTitle)
        val subtitle by bind<TextView>(R.id.actionSubtitle)
        val editable by bind<ImageView>(R.id.actionEditable)
        val secondaryAccessory by bind<ImageView>(R.id.actionSecondaryAccessory)
        var accessoryColor: Int? = null
        var accessoryFadeColor: Int? = null
        var accessoryFadeDrawable: Drawable? = null
        var accessoryFadeAnimator: ValueAnimator? = null
        val notificationBadge by bind<android.view.View>(R.id.actionNotificationBadge)

        fun clearAccessoryFade() {
            val animator = accessoryFadeAnimator
            accessoryFadeAnimator = null
            accessoryFadeColor = null
            accessoryFadeDrawable = null
            animator?.cancel()
        }
    }

    private companion object {
        const val COLOR_FADE_MS = 220L
    }
}
