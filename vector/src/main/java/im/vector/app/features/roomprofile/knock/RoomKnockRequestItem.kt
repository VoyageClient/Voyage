/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.knock

import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.google.android.material.button.MaterialButton
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass
abstract class RoomKnockRequestItem : VectorEpoxyModel<RoomKnockRequestItem.Holder>(R.layout.item_room_knock_request) {

    @EpoxyAttribute lateinit var avatarRenderer: AvatarRenderer
    @EpoxyAttribute lateinit var matrixItem: MatrixItem
    @EpoxyAttribute var reason: String? = null
    @EpoxyAttribute var inProgress: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var acceptListener: ClickListener? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var declineListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        avatarRenderer.render(matrixItem, holder.avatar)
        holder.name.text = matrixItem.getBestName().prepareForDisplay()
        holder.userId.setTextOrHide(matrixItem.id.takeIf { it != matrixItem.getBestName() }?.neutralizeDirectionOverrides())
        holder.reason.setTextOrHide(reason?.prepareForDisplay())

        holder.progress.isVisible = inProgress
        holder.accept.isVisible = !inProgress
        holder.decline.isVisible = !inProgress
        holder.accept.onClick(acceptListener)
        holder.decline.onClick(declineListener)
    }

    class Holder : VectorEpoxyHolder() {
        val avatar by bind<ImageView>(R.id.knockRequestAvatar)
        val name by bind<TextView>(R.id.knockRequestName)
        val userId by bind<TextView>(R.id.knockRequestUserId)
        val reason by bind<TextView>(R.id.knockRequestReason)
        val accept by bind<MaterialButton>(R.id.knockRequestAccept)
        val decline by bind<MaterialButton>(R.id.knockRequestDecline)
        val progress by bind<ProgressBar>(R.id.knockRequestProgress)
    }
}
