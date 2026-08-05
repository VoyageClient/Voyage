/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.pinned

import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass
abstract class PinnedMessageItem : VectorEpoxyModel<PinnedMessageItem.Holder>(R.layout.item_pinned_message) {

    @EpoxyAttribute
    lateinit var avatarRenderer: AvatarRenderer

    @EpoxyAttribute
    lateinit var matrixItem: MatrixItem

    @EpoxyAttribute
    lateinit var senderName: CharSequence

    @EpoxyAttribute
    lateinit var body: CharSequence

    @EpoxyAttribute
    var formattedDate: String? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var itemClickListener: ClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var overflowClickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.view.onClick(itemClickListener)
        holder.overflow.isVisible = overflowClickListener != null
        holder.overflow.onClick(overflowClickListener)
        avatarRenderer.render(matrixItem, holder.avatar)
        holder.sender.text = senderName.prepareForDisplay()
        holder.body.text = body
        holder.timestamp.text = formattedDate
    }

    class Holder : VectorEpoxyHolder() {
        val avatar by bind<ImageView>(R.id.pinnedMessageAvatar)
        val sender by bind<TextView>(R.id.pinnedMessageSender)
        val timestamp by bind<TextView>(R.id.pinnedMessageTimestamp)
        val body by bind<TextView>(R.id.pinnedMessageBody)
        val overflow by bind<ImageButton>(R.id.pinnedMessageOverflow)
    }
}
