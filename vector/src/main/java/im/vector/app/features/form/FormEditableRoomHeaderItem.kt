/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.form

import android.net.Uri
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.bumptech.glide.request.RequestOptions
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.glide.GlideApp
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.BannerRenderer
import org.matrix.android.sdk.api.util.MatrixItem

/**
 * Editable room banner (MSC4221) + avatar, laid out as the room profile header renders them:
 * banner on top (tinted tap-to-add block when unset), avatar overlapping its bottom edge.
 */
@EpoxyModelClass
abstract class FormEditableRoomHeaderItem : VectorEpoxyModel<FormEditableRoomHeaderItem.Holder>(R.layout.item_editable_room_header) {

    @EpoxyAttribute
    var avatarRenderer: AvatarRenderer? = null

    @EpoxyAttribute
    var bannerRenderer: BannerRenderer? = null

    @EpoxyAttribute
    var matrixItem: MatrixItem? = null

    @EpoxyAttribute
    var avatarImageUri: Uri? = null

    @EpoxyAttribute
    var avatarEnabled: Boolean = true

    // Whether the room has an m.room.avatar to delete — the rendered avatar may be a DM fallback, which is not deletable.
    @EpoxyAttribute
    var hasRoomAvatar: Boolean = false

    @EpoxyAttribute
    var bannerMxcUrl: String? = null

    @EpoxyAttribute
    var bannerImageUri: Uri? = null

    @EpoxyAttribute
    var bannerEnabled: Boolean = true

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var avatarClickListener: ClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var avatarDeleteListener: ClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var bannerClickListener: ClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var bannerDeleteListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)

        holder.bannerContainer.onClick(bannerClickListener?.takeIf { bannerEnabled })
        val hasBanner = bannerImageUri != null || !bannerMxcUrl.isNullOrEmpty()
        if (bannerImageUri != null) {
            GlideApp.with(holder.bannerImage)
                    .load(bannerImageUri)
                    .centerCrop()
                    .into(holder.bannerImage)
        } else {
            bannerRenderer?.render(bannerMxcUrl, holder.bannerImage)
        }
        holder.bannerAddIcon.isVisible = !hasBanner
        holder.bannerDelete.isVisible = bannerEnabled && hasBanner
        holder.bannerDelete.onClick(bannerDeleteListener?.takeIf { bannerEnabled })

        holder.avatarContainer.onClick(avatarClickListener?.takeIf { avatarEnabled })
        if (matrixItem != null) {
            avatarRenderer?.render(matrixItem!!, holder.avatarImage)
        } else {
            GlideApp.with(holder.avatarImage)
                    .load(avatarImageUri)
                    .apply(RequestOptions.circleCropTransform())
                    .into(holder.avatarImage)
        }
        val hasAvatar = avatarImageUri != null || matrixItem != null
        bannerRenderer?.applyAvatarStroke(holder.avatarImage, matrixItem, hasAvatar)
        holder.avatarDelete.isVisible = avatarEnabled && (avatarImageUri != null || hasRoomAvatar)
        holder.avatarDelete.onClick(avatarDeleteListener?.takeIf { avatarEnabled })
    }

    class Holder : VectorEpoxyHolder() {
        val bannerContainer by bind<View>(R.id.itemEditableRoomHeaderBannerContainer)
        val bannerAddIcon by bind<ImageView>(R.id.itemEditableRoomHeaderBannerAddIcon)
        val bannerImage by bind<ImageView>(R.id.itemEditableRoomHeaderBannerImage)
        val bannerDelete by bind<View>(R.id.itemEditableRoomHeaderBannerDelete)
        val avatarContainer by bind<View>(R.id.itemEditableRoomHeaderAvatarContainer)
        val avatarImage by bind<ImageView>(R.id.itemEditableRoomHeaderAvatarImage)
        val avatarDelete by bind<View>(R.id.itemEditableRoomHeaderAvatarDelete)
    }
}
