/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.form

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.glide.RoundedCornersPercent
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.themes.ThemeUtils
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass
abstract class FormEditableSquareAvatarItem : VectorEpoxyModel<FormEditableSquareAvatarItem.Holder>(R.layout.item_editable_square_avatar) {

    @EpoxyAttribute
    var avatarRenderer: AvatarRenderer? = null

    @EpoxyAttribute
    var matrixItem: MatrixItem? = null

    @EpoxyAttribute
    var enabled: Boolean = true

    @EpoxyAttribute
    var imageUri: Uri? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var clickListener: ClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var deleteListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.imageContainer.onClick(clickListener?.takeIf { enabled })
        holder.imageContainer.backgroundCompat = spacePlaceholderBackground(holder)
        when {
            imageUri != null -> {
                GlideApp.with(holder.image)
                        .load(imageUri)
                        .transform(MultiTransformation(CenterCrop(), RoundedCornersPercent(AvatarRenderer.ROUNDED_CORNER_PERCENT)))
                        .into(holder.image)
            }
            matrixItem != null -> {
                avatarRenderer?.render(matrixItem!!, holder.image)
            }
            else -> {
                avatarRenderer?.clear(holder.image)
                // avatarRenderer is optional, so the slot has to be emptied without it too.
                holder.image.setImageDrawable(null)
            }
        }
        holder.delete.isVisible = enabled && (imageUri != null || matrixItem?.avatarUrl?.isNotEmpty() == true)
        holder.delete.onClick(deleteListener?.takeIf { enabled })
    }

    override fun unbind(holder: Holder) {
        avatarRenderer?.clear(holder.image)
        super.unbind(holder)
    }

    // Spaces always render ROUNDED, so the empty slot has to match the corner the picked image gets.
    private fun spacePlaceholderBackground(holder: Holder): Drawable {
        val size = holder.imageContainer.layoutParams.width
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = size * AvatarRenderer.ROUNDED_CORNER_PERCENT
            setColor(
                    ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_reaction_background_off)
            )
        }
    }

    class Holder : VectorEpoxyHolder() {
        val imageContainer by bind<View>(R.id.itemEditableAvatarImageContainer)
        val image by bind<ImageView>(R.id.itemEditableAvatarImage)
        val delete by bind<View>(R.id.itemEditableAvatarDelete)
    }
}
