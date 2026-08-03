/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile.mutualrooms

import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass
abstract class MutualRoomItem : VectorEpoxyModel<MutualRoomItem.Holder>(R.layout.item_mutual_room) {

    @EpoxyAttribute lateinit var avatarRenderer: AvatarRenderer
    @EpoxyAttribute lateinit var matrixItem: MatrixItem
    @EpoxyAttribute var indented: Boolean = false
    @EpoxyAttribute var header: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onClickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.name.text = matrixItem.getBestName().prepareForDisplay()
        holder.name.setTypeface(null, if (header) Typeface.BOLD else Typeface.NORMAL)
        avatarRenderer.render(matrixItem, holder.avatar)

        val density = holder.view.resources.displayMetrics.density
        val basePadding = (16 * density).toInt()
        val indent = if (indented) (24 * density).toInt() else 0
        ViewCompat.setPaddingRelative(holder.root, basePadding + indent, holder.root.paddingTop, basePadding, holder.root.paddingBottom)

        holder.view.onClick(onClickListener)
        holder.view.isClickable = onClickListener != null
    }

    override fun unbind(holder: Holder) {
        avatarRenderer.clear(holder.avatar)
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val root by bind<View>(R.id.mutualRoomRoot)
        val avatar by bind<ImageView>(R.id.mutualRoomAvatar)
        val name by bind<TextView>(R.id.mutualRoomName)
    }
}
