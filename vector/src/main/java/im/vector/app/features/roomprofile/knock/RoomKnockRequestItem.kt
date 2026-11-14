/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.knock

import android.view.View
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
import im.vector.app.features.home.room.detail.timeline.tools.clampForMaxLines
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass
abstract class RoomKnockRequestItem : VectorEpoxyModel<RoomKnockRequestItem.Holder>(R.layout.item_room_knock_request) {

    @EpoxyAttribute lateinit var avatarRenderer: AvatarRenderer
    @EpoxyAttribute lateinit var matrixItem: MatrixItem
    @EpoxyAttribute var reason: String? = null
    @EpoxyAttribute var reasonExpanded: Boolean = false
    @EpoxyAttribute var requestTime: String? = null
    @EpoxyAttribute var canAccept: Boolean = false
    @EpoxyAttribute var canDecline: Boolean = false
    @EpoxyAttribute var inProgress: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var clickListener: ClickListener? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var reasonClickListener: ClickListener? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var acceptListener: ClickListener? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var declineListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.view.onClick(clickListener)
        avatarRenderer.render(matrixItem, holder.avatar)
        holder.name.text = matrixItem.getBestName().prepareForDisplay()
        holder.time.setTextOrHide(requestTime)
        holder.userId.setTextOrHide(matrixItem.id.takeIf { it != matrixItem.getBestName() }?.neutralizeDirectionOverrides())
        val reasonLines = if (reasonExpanded) Int.MAX_VALUE else REASON_COLLAPSED_LINES
        holder.reason.setTextOrHide(
                reason?.let { holder.view.context.getString(CommonStrings.room_knock_request_reason, it).clampForMaxLines(reasonLines).prepareForDisplay() }
        )
        holder.reason.maxLines = reasonLines
        holder.reason.onClick(reasonClickListener)

        holder.actions.isVisible = canAccept || canDecline
        holder.accept.isVisible = canAccept
        holder.decline.isVisible = canDecline
        holder.buttonGap.isVisible = canAccept && canDecline
        // INVISIBLE rather than GONE keeps the row height while the spinner sits where the buttons were.
        holder.buttons.visibility = if (inProgress) View.INVISIBLE else View.VISIBLE
        holder.progress.isVisible = inProgress
        holder.accept.onClick(acceptListener)
        holder.decline.onClick(declineListener)
    }

    companion object {
        private const val REASON_COLLAPSED_LINES = 3
    }

    class Holder : VectorEpoxyHolder() {
        val avatar by bind<ImageView>(R.id.knockRequestAvatar)
        val name by bind<TextView>(R.id.knockRequestName)
        val time by bind<TextView>(R.id.knockRequestTime)
        val userId by bind<TextView>(R.id.knockRequestUserId)
        val reason by bind<TextView>(R.id.knockRequestReason)
        val actions by bind<View>(R.id.knockRequestActions)
        val buttons by bind<View>(R.id.knockRequestButtons)
        val buttonGap by bind<View>(R.id.knockRequestButtonGap)
        val accept by bind<MaterialButton>(R.id.knockRequestAccept)
        val decline by bind<MaterialButton>(R.id.knockRequestDecline)
        val progress by bind<ProgressBar>(R.id.knockRequestProgress)
    }
}
